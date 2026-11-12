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

/**
 * Minimal ADB client that communicates with `adbd` over local TCP.
 *
 * Implements persistent RSA authentication, single-time key exchange,
 * and exponential backoff reconnection logic to ensure prompts are never looped.
 */
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

    @Volatile
    private var failureCount = 0

    @Volatile
    private var keyOfferUsed = false

    private val _authState = MutableStateFlow(AdbAuthState.UNKNOWN)
    val authState: StateFlow<AdbAuthState> = _authState.asStateFlow()

    init {
        if (keys.authorisationStored) {
            _authState.value = AdbAuthState.STORED
            keyOfferUsed = true
        }
    }

    fun retarget(newHost: String, newPort: Int) {
        if (host != newHost || port != newPort) {
            host = newHost
            port = newPort
            closeSession()
        }
    }

    fun resetAuthorisation() {
        keyOfferUsed = false
        failureCount = 0
        lastFailureAt = 0L
        _authState.value = if (keys.authorisationStored) AdbAuthState.STORED else AdbAuthState.UNKNOWN
        closeSession()
    }

    override suspend fun isAvailable(): Boolean = mutex.withLock {
        val current = session
        if (current != null && current.isAlive) {
            return@withLock true
        }

        val opened = try {
            openSession(force = true)
        } catch (e: Exception) {
            lastError = e.message
            lastFailureAt = System.currentTimeMillis()
            failureCount++
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
        failureCount = 0
        session = opened
        true
    }

    fun fingerprint(): String = keys.publicKeyFingerprint()

    override suspend fun unavailableReason(): String = lastError
        ?: "No ADB bridge on $host:$port. Turn on Wireless debugging in Developer options."

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = mutex.withLock {
        val active = session?.takeIf { it.isAlive } ?: try {
            openSession()
        } catch (e: ShellUnavailable) {
            lastError = e.message
            lastFailureAt = System.currentTimeMillis()
            failureCount++
            throw e
        } catch (e: Exception) {
            lastError = e.message ?: "Unknown error"
            lastFailureAt = System.currentTimeMillis()
            failureCount++
            throw ShellUnavailable(lastError ?: "Cannot reach the ADB bridge")
        }

        try {
            val res = active.exec(command, timeoutMs)
            failureCount = 0
            lastError = null
            res
        } catch (e: Exception) {
            lastError = e.message ?: "The ADB command failed"
            closeSession()
            failureCount++
            throw ShellUnavailable(lastError ?: "The ADB command failed")
        }
    }

    private fun openSession(force: Boolean = false): AdbSession =
        openSessionAt(host, port, force, HANDSHAKE_TIMEOUT_MS)

    private fun openSessionAt(
        targetHost: String,
        targetPort: Int,
        force: Boolean = false,
        handshakeTimeoutMs: Int = HANDSHAKE_TIMEOUT_MS
    ): AdbSession {
        val cooling = System.currentTimeMillis() - lastFailureAt
        val requiredBackoff = (BASE_BACKOFF_MS * (1 shl minOf(failureCount, 4))).coerceIn(BASE_BACKOFF_MS, MAX_BACKOFF_MS)
        if (!force && cooling < requiredBackoff && lastError != null) {
            throw ShellUnavailable(lastError!!)
        }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = handshakeTimeoutMs

            val session = AdbSession(
                socket = socket,
                keys = keys,
                canOfferKey = { !keyOfferUsed },
                onKeyOffered = {
                    keyOfferUsed = true
                    _authState.value = AdbAuthState.PROMPT_SENT
                }
            )

            session.handshake()

            // If handshake completed, authorization is now valid and remembered
            keys.markAuthorisationStored()
            _authState.value = AdbAuthState.STORED
            lastFailureAt = 0L
            failureCount = 0
            lastError = null
            return session
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw ShellUnavailable(describe(e, targetHost, targetPort))
        }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun describe(e: Exception, targetHost: String, targetPort: Int): String = when (e) {
        is ShellUnavailable -> e.message ?: "ADB connection error"
        is java.net.ConnectException -> "Nothing is listening on $targetHost:$targetPort. Enable Wireless debugging."
        is SocketTimeoutException -> "The ADB bridge on $targetHost:$targetPort timed out."
        else -> e.message ?: "Cannot reach the ADB bridge on $targetHost:$targetPort"
    }

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

                    val s = try {
                        openRawSession(candidate)
                    } catch (e: Exception) {
                        null
                    }
                    if (s != null) {
                        val speaksAdb = runCatching { s.probeAdbd() }.getOrDefault(false)
                        s.close()
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

    override suspend fun requestTcpPort(target: Int, sourcePort: Int): Boolean {
        val s = try {
            openSessionAt(host, sourcePort, force = true, handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS)
        } catch (e: Exception) {
            return false
        }
        return try {
            s.openService("tcpip:$target")
            true
        } catch (e: Exception) {
            false
        } finally {
            s.close()
        }
    }

    private class AdbSession(
        private val socket: Socket,
        private val keys: AdbKeyStore,
        private val canOfferKey: () -> Boolean = { true },
        private val onKeyOffered: () -> Unit = {}
    ) {
        private val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
        private val output: OutputStream = socket.getOutputStream()
        private var localId = 0
        var supportsShellV2 = false
            private set
        var offeredKey = false
            private set

        val isAlive: Boolean
            get() = !socket.isClosed && socket.isConnected && !socket.isInputShutdown && !socket.isOutputShutdown

        fun close() {
            runCatching { socket.close() }
        }

        fun probeAdbd(): Boolean {
            val banner = "host::\u0000"
            send("CNXN", 0x01000001, MAX_PAYLOAD, banner.toByteArray(Charsets.UTF_8))
            val response = readMessage() ?: return false
            return response.command == "AUTH" || response.command == "CNXN"
        }

        private fun offerKey() {
            if (!canOfferKey()) {
                throw ShellUnavailable(
                    "Waiting for wireless debugging authorization to be confirmed on the phone."
                )
            }
            send("AUTH", AUTH_RSAPUBLICKEY, 0, keys.publicKeyPayload().toByteArray(Charsets.UTF_8))
            offeredKey = true
            onKeyOffered()
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
                            "Waiting for \"Allow wireless debugging?\" confirmation on your phone screen."
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
                                    offerKey()
                                }
                                else -> throw ShellUnavailable(
                                    "ADB authentication rejected. Please ensure \"Always allow\" is checked."
                                )
                            }
                        }
                    }
                    "STLS" -> throw ShellUnavailable("TLS requested by ADB daemon which is not supported.")
                    else -> Unit
                }
            }
        }

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
                    "AUTH" -> throw ShellUnavailable("Device requested ADB re-authorisation.")
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
                    out = String(bytes, Charsets.UTF_8)
                }
            } else {
                out = String(bytes, Charsets.UTF_8)
            }

            if (timedOut) {
                throw ShellUnavailable("The command timed out after ${timeoutMs}ms.")
            }

            return ShellResult(code = exit, out = out, err = err)
        }

        private data class ShellV2Output(val stdout: String, val stderr: String, val exitCode: Int)

        private fun parseShellV2(payload: ByteArray): ShellV2Output {
            var i = 0
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exit = -1
            while (i + 5 <= payload.size) {
                val id = payload[i].toInt() and 0xFF
                val len = (payload[i + 1].toInt() and 0xFF) or
                    ((payload[i + 2].toInt() and 0xFF) shl 8) or
                    ((payload[i + 3].toInt() and 0xFF) shl 16) or
                    ((payload[i + 4].toInt() and 0xFF) shl 24)
                i += 5
                if (len < 0 || i + len > payload.size) break
                when (id) {
                    SHELL_ID_STDOUT -> stdout.write(payload, i, len)
                    SHELL_ID_STDERR -> stderr.write(payload, i, len)
                    SHELL_ID_EXIT -> if (len >= 1) exit = payload[i].toInt() and 0xFF
                }
                i += len
            }
            return ShellV2Output(
                stdout = String(stdout.toByteArray(), Charsets.UTF_8),
                stderr = String(stderr.toByteArray(), Charsets.UTF_8),
                exitCode = exit
            )
        }

        private fun send(command: String, arg0: Int, arg1: Int, data: ByteArray) {
            val cmd = commandToMagic(command)
            val magic = cmd xor -0x1
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(cmd)
            header.putInt(arg0)
            header.putInt(arg1)
            header.putInt(data.size)
            header.putInt(dataChecksum(data))
            header.putInt(magic)
            synchronized(output) {
                output.write(header.array())
                if (data.isNotEmpty()) output.write(data)
                output.flush()
            }
        }

        private fun readMessage(): AdbMessage? {
            val header = ByteArray(24)
            var read = 0
            while (read < 24) {
                val count = input.read(header, read, 24 - read)
                if (count < 0) return null
                read += count
            }
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = magicToCommand(buf.getInt())
            val arg0 = buf.getInt()
            val arg1 = buf.getInt()
            val length = buf.getInt()
            val checksum = buf.getInt()
            val magic = buf.getInt()

            if (length < 0 || length > MAX_PAYLOAD) return null
            val data = ByteArray(length)
            var dataRead = 0
            while (dataRead < length) {
                val count = input.read(data, dataRead, length - dataRead)
                if (count < 0) return null
                dataRead += count
            }
            return AdbMessage(command, arg0, arg1, data)
        }

        private fun dataChecksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += (b.toInt() and 0xFF)
            return sum
        }

        private fun commandToMagic(cmd: String): Int {
            require(cmd.length == 4)
            return (cmd[0].code and 0xFF) or
                ((cmd[1].code and 0xFF) shl 8) or
                ((cmd[2].code and 0xFF) shl 16) or
                ((cmd[3].code and 0xFF) shl 24)
        }

        private fun magicToCommand(magic: Int): String {
            val chars = charArrayOf(
                (magic and 0xFF).toChar(),
                ((magic shr 8) and 0xFF).toChar(),
                ((magic shr 16) and 0xFF).toChar(),
                ((magic shr 24) and 0xFF).toChar()
            )
            return String(chars)
        }
    }

    private data class AdbMessage(
        val command: String,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray
    )

    companion object {
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val SHELL_ID_STDOUT = 1
        private const val SHELL_ID_STDERR = 2
        private const val SHELL_ID_EXIT = 3
        private const val MAX_PAYLOAD = 256 * 1024
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val HANDSHAKE_TIMEOUT_MS = 25_000
        private const val PROMPT_WAIT_TIMEOUT_MS = 60_000
        private const val BASE_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PROBE_MARK = "__framescope_ok__"

        private const val LOOPBACK = "127.0.0.1"
        private const val SCAN_FROM = 35_000
        private const val SCAN_TO = 45_000
        private val SCAN_RANGE = SCAN_FROM..SCAN_TO
        private const val SCAN_THREADS = 48
        private const val SCAN_CONNECT_MS = 80
        private const val SCAN_HANDSHAKE_MS = 2_000
        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val PROBE_COMMAND = "echo $PROBE_MARK"
    }
}
