package com.framescope.monitor.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal ADB client that talks the wire protocol straight to `adbd`.
 *
 * Setup is a single command on a PC:
 *
 *     adb tcpip 5555
 *
 * after which `adbd` listens on TCP 5555 and FrameScope can open a shell
 * session from the phone itself (loopback). On devices with
 * `ro.adb.secure=1` the phone shows the usual "Allow USB debugging?" prompt
 * once - after approving it, the stored key is reused silently.
 *
 * The implementation follows AOSP's protocol:
 *
 *  - `CNXN` carries the host banner, the device answers with its own banner
 *    which also advertises `shell_v2`.
 *  - `AUTH` is answered with the bare RSA signature of the 20 byte token.
 *    If the device does not know the key it sends a second token, and the
 *    client then offers its public key so the phone can ask for approval.
 *  - `shell,v2,raw:` streams stdout, stderr and the exit code as separate
 *    packets (`id byte + 32 bit length + payload`).
 */
/**
 * How far the debugging authorisation on the phone has come.
 */
enum class AdbAuthState {
    /** The phone has not been asked yet. */
    UNKNOWN,

    /** The key was handed over and the phone is waiting for the user. */
    PROMPT_SENT,

    /** The phone keeps the key, so no prompt will come back. */
    STORED
}

/**
 * What the hub needs from an ADB runner in order to repair the bridge.
 */
interface AdbBridgeRepairer {
    /** Finds the ADB daemon the phone started itself, or null. */
    suspend fun discoverLocalAdbPort(): Int?

    /** Asks that daemon to move to [target]. Returns true when it accepted. */
    suspend fun requestTcpPort(target: Int, sourcePort: Int): Boolean
}

class AdbTcpRunner(
    @Volatile var host: String = "127.0.0.1",
    @Volatile var port: Int = 5555,
    private val keys: AdbKeyStore
) : ShellRunner, AdbBridgeRepairer {

    override val id = "adb"
    override val label = "Local ADB bridge"

    private val mutex = Mutex()
    private var session: AdbSession? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastFailureAt = 0L

    /**
     * Whether the key was already handed to the phone in this run.
     *
     * The phone asks "Allow USB debugging?" every time it is handed a key it
     * does not know yet. Offering it again on the next retry would stack up
     * prompts, so the key is offered once and the app waits for the user.
     * A manual re-check clears the flag.
     */
    @Volatile
    private var keyOfferUsed = false

    /**
     * Whether the key was already offered to the phone's key store.
     *
     * Writing the key is done once per run, unless the phone asks for the key
     * again - in that case it has to be written again.
     */
    @Volatile
    private var keyStoreWriteAttempted = false

    /** How far the authorisation on the phone has come. */
    private val _authState = MutableStateFlow(AdbAuthState.UNKNOWN)
    val authState: StateFlow<AdbAuthState> = _authState.asStateFlow()

    init {
        // A key that the phone already keeps is accepted without a prompt.
        if (keys.authorisationStored) _authState.value = AdbAuthState.STORED
    }

    override suspend fun isAvailable(): Boolean = mutex.withLock {
        val opened = session?.takeIf { it.isAlive } ?: try {
            openSession(force = true)
        } catch (e: ShellUnavailable) {
            lastError = e.message
            lastFailureAt = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            lastError = e.message ?: "Unknown error"
            lastFailureAt = System.currentTimeMillis()
            null
        }
        if (opened == null) {
            closeSession()
            return@withLock false
        }
        val probe = try {
            opened.exec(PROBE_COMMAND, PROBE_TIMEOUT_MS)
        } catch (e: Exception) {
            lastError = e.message ?: "The ADB bridge did not answer"
            closeSession()
            return@withLock false
        }
        if (!probe.out.contains(PROBE_MARK)) {
            lastError = "The ADB bridge on $host:$port answered, but running commands failed."
            closeSession()
            return@withLock false
        }
        lastError = null
        session = opened
        true
    }

    /** The fingerprint the phone shows in its debugging prompt. */
    fun fingerprint(): String = keys.publicKeyFingerprint()

    override suspend fun unavailableReason(): String = lastError
        ?: "No ADB bridge on $host:$port. Turn on Wireless debugging in Developer options and " +
            "tap \"Find the bridge\" in Settings."

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = mutex.withLock {
        val active = session?.takeIf { it.isAlive } ?: try {
            openSession()
        } catch (e: ShellUnavailable) {
            lastError = e.message
            lastFailureAt = System.currentTimeMillis()
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Unknown error"
            lastFailureAt = System.currentTimeMillis()
            throw ShellUnavailable(lastError ?: "Cannot reach the ADB bridge")
        }
        try {
            active.exec(command, timeoutMs)
        } catch (e: ShellUnavailable) {
            lastError = e.message
            closeSession()
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "The ADB command failed"
            closeSession()
            throw ShellUnavailable(lastError ?: "The ADB command failed")
        }
    }

    private fun openSession(force: Boolean = false): AdbSession =
        openSessionAt(host, port, force, HANDSHAKE_TIMEOUT_MS)

    /** Opens an authenticated session with an explicit endpoint. */
    private fun openSessionAt(
        targetHost: String,
        targetPort: Int,
        force: Boolean = false,
        handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS
    ): AdbSession {
        // A bridge that just refused us is not retried in a tight loop, but a
        // manual re-check always gets a fresh attempt.
        val cooling = System.currentTimeMillis() - lastFailureAt
        if (!force && cooling < FAILURE_COOLDOWN_MS && lastError != null) {
            throw ShellUnavailable(lastError!!)
        }
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            // The phone may ask the user to approve our key, which takes time.
            socket.soTimeout = handshakeTimeoutMs
            val session = AdbSession(
                socket,
                keys,
                canOfferKey = { !keyOfferUsed },
                onKeyOffered = {
                    keyOfferUsed = true
                    _authState.value = AdbAuthState.PROMPT_SENT
                }
            )
            session.handshake()
            // The key was handed over, or the phone already knew it. Either
            // way it is now worth making sure the phone keeps it for good,
            // because a key that is only remembered for the running session
            // makes the phone ask again after every reconnect.
            if (session.keyWasOffered || (!keys.authorisationStored && !keyStoreWriteAttempted)) {
                keyStoreWriteAttempted = true
                storeAuthorisationOnPhone(session)
            }
            lastFailureAt = 0L
            lastError = null
            return session
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw ShellUnavailable(describe(e, targetHost, targetPort))
        }
    }

    /**
     * Puts the key into the phone's own key store.
     *
     * The phone keeps authorised keys in `/data/misc/adb/adb_keys`. A key that
     * is in there is accepted silently, so the "Allow USB debugging?" prompt
     * does not come back - not after a reconnect, and not after a reboot.
     *
     * Some phones ignore the "Always allow from this computer" box for a
     * connection that arrives over the network, which is exactly the case
     * here: the bridge runs on the phone itself. Writing the key takes that
     * dependency away.
     */
    private fun storeAuthorisationOnPhone(session: AdbSession) {
        val line = keys.publicKeyPayload().replace("\"", "")
        val command = buildString {
            append("if grep -q ").append(COMMENT_MARK).append(" /data/misc/adb/adb_keys 2>/dev/null; ")
            append("then echo ").append(KEYS_MARKER).append("; ")
            append("elif echo \"").append(line).append("\" >> /data/misc/adb/adb_keys 2>/dev/null && ")
            append("grep -q ").append(COMMENT_MARK).append(" /data/misc/adb/adb_keys 2>/dev/null; ")
            append("then echo ").append(KEYS_MARKER).append("; ")
            append("elif cat /data/misc/adb/adb_keys > /data/misc/adb/.framescope.tmp 2>/dev/null && ")
            append("echo \"").append(line).append("\" >> /data/misc/adb/.framescope.tmp 2>/dev/null && ")
            append("cp /data/misc/adb/.framescope.tmp /data/misc/adb/adb_keys 2>/dev/null; ")
            append("then echo ").append(KEYS_MARKER).append("; rm -f /data/misc/adb/.framescope.tmp; ")
            append("else echo ").append(KEYS_FAILED).append("; fi")
        }
        val stored = runCatching {
            session.exec(command, STORE_KEY_TIMEOUT_MS).out.contains(KEYS_MARKER)
        }.getOrDefault(false)
        if (stored) {
            keys.markAuthorisationStored()
            _authState.value = AdbAuthState.STORED
        }
    }

    /**
     * Connects to a port and speaks the first line of the ADB handshake.
     *
     * The key is not offered here: the daemon answers a connection with an
     * authentication token, which is enough to recognise it, and handing over
     * a key the phone does not know yet would raise a prompt.
     */
    private fun openRawSession(port: Int): AdbSession {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(LOOPBACK, port), SCAN_CONNECT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = SCAN_HANDSHAKE_MS
            return AdbSession(socket, keys)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Finds the ADB daemon that the phone's own wireless debugging started.
     *
     * Android hands the wireless debugging service a random port, so the app
     * cannot know it up front. A short scan of the loopback interface finds
     * it, and the daemon can then be told to move to a fixed port, which is
     * what makes the bridge work without a computer and without Wi-Fi.
     */
    override suspend fun discoverLocalAdbPort(): Int? {
        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "adbd-scan").apply { isDaemon = true }
        }
        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        try {
            for (candidate in SCAN_RANGE) {
                pool.execute {
                    if (found.get() >= 0) return@execute
                    val socket = Socket()
                    val open = try {
                        socket.connect(InetSocketAddress(LOOPBACK, candidate), SCAN_CONNECT_MS)
                        true
                    } catch (e: Exception) {
                        false
                    } finally {
                        runCatching { socket.close() }
                    }
                    if (!open) return@execute
                    // Something is listening: it only counts if it speaks ADB.
                    // The check stays anonymous - no key is handed over - because
                    // every key the phone has not seen raises another
                    // "Allow USB debugging?" prompt.
                    val session = try {
                        openRawSession(candidate)
                    } catch (e: Exception) {
                        null
                    }
                    if (session != null) {
                        val speaksAdb = runCatching { session.probeAdbd() }.getOrDefault(false)
                        session.close()
                        if (speaksAdb && found.compareAndSet(-1, candidate)) latch.countDown()
                    }
                }
            }
            latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            pool.shutdownNow()
        }
        return found.get().takeIf { it >= 0 }
    }

    /**
     * Asks the daemon to move to a fixed port.
     *
     * This is the request the `adb tcpip` command sends: the service string
     * `tcpip:<port>`. The daemon sets the port and restarts itself, which
     * drops this connection on purpose.
     */
    override suspend fun requestTcpPort(target: Int, sourcePort: Int): Boolean {
        val session = try {
            openSessionAt(host, sourcePort, force = true, handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS)
        } catch (e: Exception) {
            return false
        }
        return try {
            // Ask for a persistent port first: where the system honours it the
            // bridge is back by itself after a reboot.
            runCatching {
                session.exec("setprop persist.adb.tcp.port $target", 5_000)
            }
            session.openService("tcpip:$target")
        } catch (e: Exception) {
            false
        } finally {
            session.close()
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /**
     * Allows the key to be handed over again.
     *
     * Used by the manual re-check: the user can ask for a new prompt after
     * the first one was missed or dismissed.
     */
    fun resetAuthorisation() {
        keyOfferUsed = false
        closeSession()
        lastError = null
        lastFailureAt = 0L
        if (!keys.authorisationStored) _authState.value = AdbAuthState.UNKNOWN
    }

    /** Points the runner at another endpoint and drops the current session. */
    fun retarget(newHost: String, newPort: Int) {
        if (newHost == host && newPort == port) return
        host = newHost
        port = newPort
        closeSession()
        lastError = null
        lastFailureAt = 0L
    }

    private fun describe(e: Exception, atHost: String = host, atPort: Int = port): String = when (e) {
        is java.net.ConnectException ->
            "Nothing is listening on $atHost:$atPort."
        is java.net.SocketTimeoutException ->
            "The ADB bridge on $atHost:$atPort did not answer in time."
        else -> e.message ?: "Cannot reach the ADB bridge on $atHost:$atPort."
    }

    /** A single authenticated connection to `adbd`; commands are multiplexed over it. */
    private class AdbSession(
        private val socket: Socket,
        private val keys: AdbKeyStore,
        private val canOfferKey: () -> Boolean = { true },
        private val onKeyOffered: () -> Unit = {}
    ) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private var localId = 0
        private var closed = false

        var supportsShellV2: Boolean = false
            private set

        /** True once the key has been handed over to the device. */
        private var offeredKey = false

        /** True when this session had to hand the key over. */
        val keyWasOffered: Boolean get() = offeredKey

        val isAlive: Boolean get() = !closed && socket.isConnected && !socket.isClosed

        /**
         * Checks that the daemon on the other end speaks ADB.
         *
         * The daemon answers a connection with an authentication token. No key
         * is handed over, so this raises no prompt on the phone.
         */
        fun probeAdbd(): Boolean {
            val banner = "host::features=shell_v2,cmd\u0000"
            send("CNXN", 0x01000001, MAX_PAYLOAD, banner.toByteArray(Charsets.UTF_8))
            val msg = readMessage() ?: return false
            return msg.command == "AUTH" || msg.command == "CNXN"
        }

        /**
         * Hands the key to the device so the phone can ask the user.
         *
         * The prompt stays on screen until it is answered, so the wait is
         * stretched and the key is not offered again for a while: a second
         * offer would raise a second prompt over the first one.
         */
        private fun offerKey() {
            if (!canOfferKey()) {
                throw ShellUnavailable(
                    "Waiting for the \"Allow USB debugging?\" confirmation on the phone. " +
                        "Unlock the screen and tap Allow. If the prompt is gone, tick " +
                        "\"Always allow from this computer\" and tap Re-check in Settings."
                )
            }
            send("AUTH", AUTH_RSAPUBLICKEY, 0, keys.publicKeyPayload().toByteArray(Charsets.UTF_8))
            offeredKey = true
            onKeyOffered()
            // The user needs time to find the prompt and tap it.
            runCatching { socket.soTimeout = PROMPT_WAIT_TIMEOUT_MS }
        }

        fun handshake() {
            val banner = "host::features=shell_v2,cmd\u0000"
            send("CNXN", 0x01000001, MAX_PAYLOAD, banner.toByteArray(Charsets.UTF_8))

            var tokens = 0
            while (true) {
                val msg = try {
                    readMessage()
                } catch (e: SocketTimeoutException) {
                    throw ShellUnavailable(
                        if (offeredKey) {
                            "Waiting for the \"Allow USB debugging?\" prompt on the phone. " +
                                "Unlock the screen and confirm it, then retry."
                        } else {
                            "The ADB bridge did not answer in time."
                        }
                    )
                } ?: throw ShellUnavailable("The ADB bridge closed the connection during the handshake.")
                when (msg.command) {
                    "CNXN" -> {
                        val deviceBanner = String(msg.data, Charsets.UTF_8).trim { it <= ' ' }
                        supportsShellV2 = deviceBanner.contains("shell_v2")
                        return
                    }
                    "AUTH" -> {
                        if (msg.arg0 == AUTH_TOKEN) {
                            tokens++
                            when {
                                tokens == 1 -> {
                                    val signature = keys.sign(msg.data)
                                    if (signature != null) {
                                        send("AUTH", AUTH_SIGNATURE, 0, signature)
                                    } else {
                                        offerKey()
                                    }
                                }
                                !offeredKey -> {
                                    // The device did not know our key: hand it over so the phone
                                    // can show the "Allow USB debugging?" prompt.
                                    offerKey()
                                }
                                else -> throw ShellUnavailable(
                                    "The phone rejected the ADB key. Unlock the screen, open the " +
                                        "\"Allow USB debugging?\" prompt, tick \"Always allow from " +
                                        "this computer\" and confirm it, then tap Re-check in Settings."
                                )
                            }
                        }
                    }
                    "STLS" -> throw ShellUnavailable("The ADB bridge asked for TLS, which is not supported.")
                    else -> Unit
                }
            }
        }

        /**
         * Opens a service on the device without running a command.
         *
         * Used for `tcpip:<port>`, which the daemon answers and then restarts
         * itself, so the connection is expected to drop right after.
         */
        fun openService(service: String): Boolean {
            socket.soTimeout = 4_000
            val id = ++localId
            send("OPEN", id, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
            repeat(8) {
                val msg = try {
                    readMessage()
                } catch (e: Exception) {
                    return true
                } ?: return true
                if (msg.command == "OKAY") return true
                if (msg.command == "FAIL") return false
            }
            return true
        }

        fun exec(command: String, timeoutMs: Long): ShellResult {
            socket.soTimeout = timeoutMs.toInt().coerceIn(500, 120_000)
            val service = if (supportsShellV2) "shell,v2,raw:" else "shell:"
            val id = ++localId
            send("OPEN", id, 0, (service + command + "\u0000").toByteArray(Charsets.UTF_8))

            val raw = ByteArrayOutputStream()
            var timedOut = false
            while (true) {
                val msg = try {
                    readMessage()
                } catch (e: SocketTimeoutException) {
                    timedOut = true
                    null
                } ?: break
                when (msg.command) {
                    "OKAY" -> Unit
                    "WRTE" -> if (msg.arg1 == id || msg.arg0 == id) raw.write(msg.data)
                    "CLSE" -> if (msg.arg1 == id || msg.arg0 == id) break
                    "AUTH" -> throw ShellUnavailable("The device asked for ADB authorisation again.")
                    else -> Unit
                }
            }

            val bytes = raw.toByteArray()
            var out: String
            var err = ""
            var exit = -1
            if (supportsShellV2) {
                val parsed = parseShellV2(bytes)
                out = parsed.stdout
                err = parsed.stderr
                exit = parsed.exitCode
                if (out.isEmpty() && err.isEmpty() && exit < 0 && bytes.isNotEmpty()) {
                    // The device ignored the "v2" argument and sent a plain stream.
                    out = String(bytes, Charsets.UTF_8)
                }
            } else {
                out = String(bytes, Charsets.UTF_8)
            }

            if (timedOut) {
                throw ShellUnavailable(
                    "The command timed out after ${timeoutMs}ms. " +
                        (if (out.isBlank()) "" else "Partial output was received.")
                )
            }
            return ShellResult(exit, out, err)
        }

        private fun parseShellV2(raw: ByteArray): ParsedShell {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exit = -1
            var offset = 0
            while (offset + 5 <= raw.size) {
                val id = raw[offset].toInt() and 0xFF
                val length = (raw[offset + 1].toInt() and 0xFF) or
                    ((raw[offset + 2].toInt() and 0xFF) shl 8) or
                    ((raw[offset + 3].toInt() and 0xFF) shl 16) or
                    ((raw[offset + 4].toInt() and 0xFF) shl 24)
                if (length < 0 || offset + 5 + length > raw.size) break
                val payload = raw.copyOfRange(offset + 5, offset + 5 + length)
                when (id) {
                    SHELL_ID_STDOUT -> stdout.write(payload)
                    SHELL_ID_STDERR -> stderr.write(payload)
                    SHELL_ID_EXIT -> if (payload.isNotEmpty()) exit = payload[0].toInt() and 0xFF
                }
                offset += 5 + length
            }
            return ParsedShell(
                stdout.toString(Charsets.UTF_8.name()),
                stderr.toString(Charsets.UTF_8.name()),
                exit
            )
        }

        private data class ParsedShell(val stdout: String, val stderr: String, val exitCode: Int)

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }

        private fun send(command: String, arg0: Int, arg1: Int, data: ByteArray) {
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            header.put(command.toByteArray(Charsets.US_ASCII))
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(data.size)
            header.putInt(crc32(data))
            header.putInt(command.toByteArray(Charsets.US_ASCII).toIntLittleEndian() xor 0xFFFFFFFF.toInt())
            output.write(header.array())
            output.write(data)
            output.flush()
        }

        private fun readMessage(): Msg? {
            val header = ByteArray(24)
            if (!readFully(header)) return null
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = String(header, 0, 4, Charsets.US_ASCII)
            val arg0 = bb.getInt(4)
            val arg1 = bb.getInt(8)
            val length = bb.getInt(12)
            val magic = bb.getInt(20)
            if (magic != (command.toByteArray(Charsets.US_ASCII).toIntLittleEndian() xor 0xFFFFFFFF.toInt())) {
                throw ShellUnavailable("The ADB bridge sent a malformed packet.")
            }
            val data = ByteArray(maxOf(length, 0))
            if (data.isNotEmpty() && !readFully(data)) return null
            return Msg(command, arg0, arg1, data)
        }

        private fun readFully(buffer: ByteArray): Boolean {
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) return false
                read += n
            }
            return true
        }

        private fun crc32(data: ByteArray): Int {
            val crc = java.util.zip.CRC32()
            crc.update(data)
            return crc.value.toInt()
        }
    }

    private data class Msg(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val SHELL_ID_STDOUT = 1
        private const val SHELL_ID_STDERR = 2
        private const val SHELL_ID_EXIT = 3
        private const val MAX_PAYLOAD = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 30_000
        /** How long the connection waits while the user answers the prompt. */
        private const val PROMPT_WAIT_TIMEOUT_MS = 90_000
        private const val FAILURE_COOLDOWN_MS = 4_000L
        private const val PROBE_TIMEOUT_MS = 6_000L
        private const val PROBE_MARK = "__framescope_ok__"

        // The loopback interface: the bridge always lives on the phone itself.
        private const val LOOPBACK = "127.0.0.1"

        // Android puts the wireless debugging daemon somewhere in this range.
        private const val SCAN_FROM = 35_000
        private const val SCAN_TO = 45_000
        private val SCAN_RANGE = SCAN_FROM..SCAN_TO
        private const val SCAN_THREADS = 64
        private const val SCAN_CONNECT_MS = 80
        private const val SCAN_HANDSHAKE_MS = 2_500
        private const val SCAN_TIMEOUT_MS = 9_000L
        private const val PROBE_COMMAND = "echo $PROBE_MARK"

        /** How long the key may take to be written into the phone's key store. */
        private const val STORE_KEY_TIMEOUT_MS = 6_000L

        /** Printed by the phone when the key is in its own key store. */
        private const val KEYS_MARKER = "__framescope_keys_ok__"
        private const val KEYS_FAILED = "__framescope_keys_failed__"

        /** The part of the key line that identifies it in the key store. */
        private val COMMENT_MARK = AdbKeyStore.COMMENT
    }
}

private fun ByteArray.toIntLittleEndian(): Int {
    var value = 0
    for (i in indices.reversed()) {
        value = (value shl 8) or (this[i].toInt() and 0xFF)
    }
    return value
}
