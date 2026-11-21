package com.framescope.monitor.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AccessState { READY, NEEDS_SETUP, UNAVAILABLE }

data class AccessStatus(
    val state: AccessState = AccessState.NEEDS_SETUP,
    val detail: String = "",
    val checkedAt: Long = 0L,
    val permissionGranted: Boolean = false,
    val binderAvailable: Boolean = false,
    val userServiceConnected: Boolean = false,
    val commandVerified: Boolean = false,
    /** Which pipe actually answered the last command. */
    val shellSource: String = "Shizuku",
    val adbReady: Boolean = false,
    val adbAuthorised: Boolean = false,
    val adbFingerprint: String = ""
)

/**
 * Privileged execution hub.
 *
 * Order of preference:
 *
 * - The app's own shell, when the user granted DUMP once. It is kept first
 *   because it gives the whole answer without a binder limit.
 * - Shizuku UserService, the default and safest route.
 * - A local ADB bridge on 127.0.0.1, restored as a convenience fallback. Its
 *   key is stored once, so after the one-time authorization the app reconnects
 *   by itself. It is only used when the two routes above did not answer.
 *
 * Only one transport serves a command. A failed transport never produces a
 * made-up value: callers still receive `--` through [ShellUnavailable].
 */
class ShellHub(
    val shizukuRunner: ShizukuRunner,
    private val appContext: Context? = null,
    private val adbPort: Int = DEFAULT_ADB_PORT
) {

    constructor(context: Context? = null) : this(
        ShizukuRunner(context),
        context?.applicationContext,
        DEFAULT_ADB_PORT
    )

    private val adbKeys = AdbKeyStore(
        appContext?.filesDir
            ?: File(System.getProperty("java.io.tmpdir", "."), "framescope-adb-test"),
        appContext
    )
    private val adb = AdbTcpRunner(port = adbPort, keys = adbKeys)
    private val adbRepairer: AdbBridgeRepairer get() = adb

    private val _status = MutableStateFlow(AccessStatus())
    val status: StateFlow<AccessStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _adbReady = MutableStateFlow(false)
    private val _adbReason = MutableStateFlow("")
    val adbAuthState: StateFlow<AdbAuthState> = adb.authState

    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adbSetupMutex = Mutex()

    // Shizuku often becomes READY a moment after the permission popup is closed.
    // Refresh() alone cannot open the bridge then, because the key is not stored
    // yet. Remember the transition so setup runs once by itself.
    private var lastShizukuReady = false
    private var adbAutoSetupAttempted = false

    init {
        statusScope.launch {
            combine(
                shizukuRunner.connectionState,
                shizukuRunner.connectionDetails,
                shizukuRunner.errorMessage,
                _adbReady,
                _adbReason
            ) { state, details, error, adbReady, adbReason ->
                accessStatus(state, details, error, adbReady, adbReason)
            }.collect { status ->
                _status.value = status
                val ready = shizukuRunner.connectionState.value == ShizukuRunner.ConnectionState.READY
                if (!ready) {
                    lastShizukuReady = false
                } else if (!lastShizukuReady) {
                    lastShizukuReady = true
                    if (!adbAutoSetupAttempted && !adb.authorisationStored && !_adbReady.value) {
                        adbAutoSetupAttempted = true
                        statusScope.launch { runCatching { setupAdbBridge() } }
                    }
                }
            }
        }
    }

    suspend fun refresh(forceReconnect: Boolean = false): AccessStatus = withContext(Dispatchers.IO) {
        val state = shizukuRunner.refresh(forceReconnect)
        // A privileged shell may hand the app the permission it needs to read
        // the frame timelines by itself. It is asked for once.
        if (state == ShizukuRunner.ConnectionState.READY) {
            tryGrantDumpPermission()
        }
        val adbWanted = adb.authorisationStored || _adbReady.value || forceReconnect
        val adbOk = if (!adbWanted) {
            false
        } else {
            runCatching { ensureAdbBridge(allowSetup = forceReconnect) }.getOrDefault(false)
        }
        publishAdbState(adbOk)
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    /**
     * One-time local ADB setup.
     *
     * Shizuku opens the loopback port using USB debugging - no wireless
     * debugging and no PC are required when it is available. If it is not, the
     * app looks for an already-open local ADB port and moves it to the fixed
     * port. The RSA key is saved as soon as the phone accepts it.
     */
    suspend fun setupAdbBridge(): String = withContext(Dispatchers.IO) {
        adbSetupMutex.withLock {
            adb.retarget(LOOPBACK, adbPort)

            // A prompt that was offered but never approved leaves the runner with
            // no way to offer the key again. An explicit setup must show a fresh
            // "Allow USB debugging?" dialog so the user can tick Always allow.
            if (adb.authState.value == AdbAuthState.PROMPT_SENT && !adb.authorisationStored) {
                adb.resetAuthorisation()
                publishAdbState(false)
            }

            // Give Shizuku one fresh chance first. Without a privileged shell the
            // app cannot install its key or open an ADB port on a stock ROM.
            if (shizukuHelper() == null) {
                runCatching { shizukuRunner.refresh(forceReconnect = true) }
            }

            // The USB debugging prompt the user sees while starting Shizuku is for
            // Shizuku's key, not ours. Install our own key with the already-available
            // privileged shell first, so FrameScope never needs a second prompt.
            val keyInstalled = trustKeyWithShell(shizukuHelper())

            if (runCatching { ensureAdbBridge(allowSetup = true) }.getOrDefault(false)) {
                finishAdbSetup(keyInstalled)
            } else {
                publishAdbState(false)
                watchForPromptApproval()
                adbSetupFailureMessage()
            }
        }
    }

    /**
     * Brings the loopback bridge back silently after a reboot or a debugging
     * toggle. The key stays saved, so this must not show the prompt again.
     *
     * A normal app cannot open an ADB socket from a USB cable: only a
     * privileged shell can start adbd. Shizuku is therefore tried first, then
     * any existing local ADB port is moved to the fixed port.
     */
    private suspend fun ensureAdbBridge(allowSetup: Boolean = false): Boolean {
        if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true

        val helper = shizukuHelper()
        if (helper != null && runCatching { bootstrapAdbPort(helper) }.getOrDefault(false)) {
            return true
        }

        val mayDiscover = allowSetup || helper != null || adb.authorisationStored
        if (mayDiscover) {
            val discovered = runCatching { adbRepairer.discoverLocalAdbPort() }.getOrNull()
            if (discovered != null && discovered != adbPort &&
                runCatching { adbRepairer.requestTcpPort(adbPort, discovered) }.getOrDefault(false)
            ) {
                delay(1_200)
                if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
            }
        }

        if (hasDumpPermission() && runCatching { bootstrapAdbPort(null) }.getOrDefault(false)) {
            return true
        }
        return false
    }

    private fun shizukuHelper(): ShellRunner? = shizukuRunner
        .takeIf { it.connectionState.value == ShizukuRunner.ConnectionState.READY }

    /** Uses Shizuku (or DUMP) to place our key on the phone before ADB starts. */
    private suspend fun trustKeyWithShell(helper: ShellRunner?): Boolean {
        if (helper == null && !hasDumpPermission()) return false
        return adb.trustKeyWith { command, timeout ->
            when {
                helper != null -> helper.exec(command, timeout)
                else -> execLocalShell(command, timeout)
            }
        }
    }

    /** Tells the truth about which prerequisite is actually missing. */
    private suspend fun adbSetupFailureMessage(): String {
        val reason = adb.unavailableReason()
        val shellHint = when (shizukuRunner.connectionState.value) {
            ShizukuRunner.ConnectionState.READY ->
                "Shizuku is running but could not start adbd on $LOOPBACK:$adbPort."
            ShizukuRunner.ConnectionState.NOT_RUNNING ->
                "Start Shizuku and grant permission. With USB debugging ON, " +
                    "Qstats can then open the bridge by itself."
            ShizukuRunner.ConnectionState.PERMISSION_DENIED ->
                "Grant Qstats the Shizuku permission so it can open the bridge."
            else -> "Shizuku is not ready yet."
        }
        return "Could not open the local ADB bridge. $reason $shellHint " +
            "Watch the phone for the “Allow USB debugging?” dialog, tick “Always allow” " +
            "and tap Allow, then tap Reconnect. If no dialog appears, connect USB to a PC " +
            "once, run 'adb tcpip 5555', unplug, then tap Set up bridge here. " +
            "Or turn on Wireless debugging and tap Set up bridge again."
    }

    /**
     * The phone prompt is often approved a moment after the socket timed out.
     * Keep checking briefly instead of stranding the user on a permanent
     * "waiting for the prompt" screen.
     */
    private fun watchForPromptApproval() {
        statusScope.launch {
            repeat(PROMPT_WATCH_ATTEMPTS) {
                delay(PROMPT_WATCH_INTERVAL_MS)
                if (_adbReady.value) return@launch
                if (adb.authState.value != AdbAuthState.PROMPT_SENT &&
                    !adb.authorisationStored
                ) return@launch
                if (runCatching { adb.isAvailable() }.getOrDefault(false)) {
                    publishAdbState(true)
                    return@launch
                }
            }
        }
    }

    /**
     * A working bridge is a working bridge. ensureAdbBridge() already ran a real
     * command on it, so shell access is live regardless of whether the ROM kept
     * our key. Persistence only affects auto-reconnect later; it must never
     * throw away a session that is answering right now.
     */
    private suspend fun finishAdbSetup(keyInstalledByShell: Boolean): String {
        // ensureAdbBridge() already ran a real probe on this session. isAvailable()
        // confirms it is still alive without offering a key or closing anything.
        // Never call verifyStoredKey() here: it closes the session, so a ROM that
        // will not keep the key would throw away a bridge that works right now.
        val live = runCatching { adb.isAvailable() }.getOrDefault(false)
        if (!live) {
            publishAdbState(false)
            return "The bridge stopped answering right after setup. " + adbSetupFailureMessage()
        }

        publishAdbState(true)
        val keyStored = adb.authorisationStored || keyInstalledByShell
        return if (keyStored) {
            adbSuccessMessage()
        } else {
            "Local ADB bridge is live on $LOOPBACK:$adbPort — live FPS and telemetry work " +
                "right now. This phone did not save Qstats' key, so after a reboot or " +
                "USB debugging toggle repeat once: PC + USB cable, 'adb tcpip 5555', then " +
                "Set up bridge here and tick “Always allow”. No Shizuku needed."
        }
    }

    private suspend fun bootstrapAdbPort(helper: ShellRunner?): Boolean {
        // ROMs differ in which property they honour. Try the known variants
        // instead of giving up after one rejected command.
        val commands = listOf(
            "setprop service.adb.tcp.port $adbPort; " +
                "setprop persist.adb.tcp.port $adbPort; stop adbd; start adbd",
            "setprop service.adb.tcp.port $adbPort; setprop ctl.restart adbd",
            "setprop persist.adb.tcp.port $adbPort; stop adbd; start adbd"
        )
        for (command in commands) {
            val accepted = runCatching {
                when {
                    helper != null -> helper.exec(command, 8_000).ok
                    hasDumpPermission() -> execLocalShell(command, 8_000).ok
                    else -> false
                }
            }.getOrDefault(false)
            if (!accepted) continue
            repeat(6) {
                delay(700)
                if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
            }
        }
        // A rejected restart must not leave adbd stopped. Bring it back before
        // reporting failure, so Wireless debugging still works.
        runCatching {
            when {
                helper != null -> helper.exec("start adbd", 3_000)
                hasDumpPermission() -> execLocalShell("start adbd", 3_000)
                else -> null
            }
        }
        return false
    }

    private suspend fun publishAdbState(ready: Boolean) {
        _adbReady.value = ready
        _adbReason.value = if (ready) {
            ""
        } else if (adb.authorisationStored) {
            adb.unavailableReason()
        } else {
            "Local ADB bridge is off. Start Shizuku to open it, or tap Set up bridge."
        }
        _status.value = accessStatus(
            shizukuRunner.connectionState.value,
            shizukuRunner.connectionDetails.value,
            shizukuRunner.errorMessage.value,
            _adbReady.value,
            _adbReason.value
        )
    }

    private fun adbSuccessMessage(): String =
        "Local ADB bridge is live on $LOOPBACK:$adbPort. It reconnects automatically."

    fun adbFingerprint(): String = adb.fingerprint()

    suspend fun adbReason(): String = adb.unavailableReason()

    /** Drops the stored trust so the phone shows the pairing prompt again. */
    suspend fun resetAdbAuthorisation() {
        adb.resetAuthorisation()
        adbKeys.clearAuthorisationStored()
        adbAutoSetupAttempted = false
        publishAdbState(false)
    }

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult = withContext(Dispatchers.IO) {
        // The app's own shell is tried first when it holds the DUMP permission.
        // A command that is not there takes the whole answer with it, so a
        // shell that answers nothing falls through to the next transport.
        if (hasDumpPermission()) {
            val local = execLocalShell(command, timeoutMs)
            if (local.ok && local.out.isNotBlank()) {
                _lastError.value = null
                return@withContext local
            }
        }

        if (shizukuRunner.connectionState.value == ShizukuRunner.ConnectionState.READY) {
            try {
                val result = shizukuRunner.exec(command, timeoutMs)
                _lastError.value = if (result.status == ShellExecutionStatus.SUCCESS) {
                    null
                } else {
                    result.err.ifBlank { "Command ${result.status.name.lowercase()}." }
                }
                return@withContext result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Shizuku may die between checks. Give the local bridge a turn.
            }
        }

        if (_adbReady.value || adb.authorisationStored) {
            try {
                val result = adb.exec(command, timeoutMs)
                _adbReady.value = true
                _lastError.value = null
                return@withContext result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _adbReady.value = false
                _lastError.value = e.message ?: "Local ADB bridge did not answer"
                throw e
            }
        }

        val reason = shizukuRunner.errorMessage.value?.takeIf { it.isNotBlank() }
            ?: "No privileged shell is connected. Start Shizuku or set up the local ADB bridge."
        _lastError.value = reason
        throw ShellUnavailable(reason)
    }

    suspend fun execOrNull(command: String, timeoutMs: Long = 8_000): ShellResult? =
        withContext(Dispatchers.IO) {
            try {
                exec(command, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ShellUnavailable) {
                null
            }
        }

    /** True when the user granted the app the right to run dumpsys itself. */
    fun hasDumpPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Runs a command in the app's own shell.
     *
     * The process runs with the app's own rights, so it may be given the
     * permission to read the frame timelines of every app on the phone. The
     * answer is not cut short on the way out, which matters: the dump of a
     * game is bigger than what a binder call may carry.
     */
    private fun execLocalShell(command: String, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
                }
            }.apply { isDaemon = true; start() }
            val errThread = Thread {
                runCatching {
                    process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs.coerceIn(100L, 30_000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(200L)
                errThread.join(200L)
                return ShellResult(-1, stdout.toString(), "Command timed out", ShellExecutionStatus.TIMEOUT)
            }
            outThread.join(800L)
            errThread.join(800L)
            val code = process.exitValue()
            val status = if (code == 0) ShellExecutionStatus.SUCCESS else ShellExecutionStatus.FAILURE
            _lastError.value = null
            ShellResult(code, stdout.toString(), stderr.toString(), status)
        } catch (e: Exception) {
            _lastError.value = e.message ?: "The app's own shell failed"
            ShellResult(-1, "", e.message.orEmpty(), ShellExecutionStatus.FAILURE)
        }
    }

    /**
     * Asks for the DUMP permission once, through Shizuku.
     *
     * The permission can only be granted by hand, but a privileged shell may
     * do it for the app it belongs to. When it works, the app no longer needs
     * Shizuku for the frame rate.
     */
    private var dumpGrantAttempted = false

    suspend fun tryGrantDumpPermission(): Boolean {
        if (dumpGrantAttempted) return hasDumpPermission()
        val ctx = appContext ?: return false
        dumpGrantAttempted = true
        if (hasDumpPermission()) return true
        return runCatching {
            val result = shizukuRunner.exec(
                "pm grant ${ctx.packageName} ${Manifest.permission.DUMP}",
                5_000
            )
            result.ok && hasDumpPermission()
        }.getOrDefault(false)
    }

    fun cleanup() {
        statusScope.cancel()
        adb.cleanup()
        shizukuRunner.cleanup()
    }

    private fun accessStatus(
        state: ShizukuRunner.ConnectionState,
        details: ShizukuRunner.ConnectionDetails,
        error: String?,
        adbReady: Boolean,
        adbReason: String
    ): AccessStatus {
        val adbAuthorised = adb.authorisationStored
        val fingerprint = adb.fingerprint()

        // Shizuku is the preferred route whenever it answers.
        if (state == ShizukuRunner.ConnectionState.READY) {
            return AccessStatus(
                state = AccessState.READY,
                detail = "",
                checkedAt = System.currentTimeMillis(),
                permissionGranted = details.permissionGranted,
                binderAvailable = details.binderAvailable,
                userServiceConnected = details.userServiceConnected,
                commandVerified = details.commandVerified,
                shellSource = "Shizuku",
                adbReady = adbReady,
                adbAuthorised = adbAuthorised,
                adbFingerprint = fingerprint
            )
        }

        // The app's own shell is enough on its own: with the DUMP permission
        // it reads the frame timelines of every app without Shizuku.
        if (hasDumpPermission()) {
            return AccessStatus(
                state = AccessState.READY,
                detail = "One-Time ADB (DUMP) active",
                checkedAt = System.currentTimeMillis(),
                permissionGranted = true,
                binderAvailable = details.binderAvailable,
                userServiceConnected = false,
                commandVerified = true,
                shellSource = "App shell (DUMP)",
                adbReady = adbReady,
                adbAuthorised = adbAuthorised,
                adbFingerprint = fingerprint
            )
        }

        if (adbReady) {
            return AccessStatus(
                state = AccessState.READY,
                detail = if (adbAuthorised) {
                    "Local ADB bridge connected on $LOOPBACK:$adbPort. Key saved — it reconnects automatically."
                } else {
                    "Local ADB bridge connected on $LOOPBACK:$adbPort for this session. " +
                        "Key not saved — repeat setup once after reboot or USB debugging toggle."
                },
                checkedAt = System.currentTimeMillis(),
                permissionGranted = true,
                binderAvailable = false,
                userServiceConnected = false,
                commandVerified = true,
                shellSource = "Local ADB bridge",
                adbReady = true,
                adbAuthorised = adbAuthorised,
                adbFingerprint = fingerprint
            )
        }

        val accessState = when (state) {
            ShizukuRunner.ConnectionState.PERMISSION_DENIED,
            ShizukuRunner.ConnectionState.CONNECTING -> AccessState.NEEDS_SETUP
            ShizukuRunner.ConnectionState.NOT_RUNNING,
            ShizukuRunner.ConnectionState.ERROR -> AccessState.UNAVAILABLE
            ShizukuRunner.ConnectionState.READY -> AccessState.READY
        }
        val shizukuReason = error ?: when (state) {
            ShizukuRunner.ConnectionState.NOT_RUNNING ->
                "Shizuku is not running. Cached data remains available."
            ShizukuRunner.ConnectionState.PERMISSION_DENIED ->
                "Grant Qstats Shizuku permission to enable privileged operations."
            ShizukuRunner.ConnectionState.CONNECTING ->
                "Connecting to Shizuku UserService. Cached data remains available."
            ShizukuRunner.ConnectionState.ERROR -> "Shizuku UserService connection error."
            ShizukuRunner.ConnectionState.READY -> ""
        }
        val detail = if (adbAuthorised && adbReason.isNotBlank()) {
            "$adbReason $shizukuReason"
        } else {
            shizukuReason
        }
        return AccessStatus(
            state = accessState,
            detail = detail,
            checkedAt = System.currentTimeMillis(),
            permissionGranted = details.permissionGranted,
            binderAvailable = details.binderAvailable,
            userServiceConnected = details.userServiceConnected,
            commandVerified = details.commandVerified,
            shellSource = "None",
            adbReady = false,
            adbAuthorised = adbAuthorised,
            adbFingerprint = fingerprint
        )
    }

    companion object {
        const val DEFAULT_ADB_PORT = 5555
        private const val LOOPBACK = "127.0.0.1"
        private const val PROMPT_WATCH_ATTEMPTS = 30
        private const val PROMPT_WATCH_INTERVAL_MS = 2_000L
    }
}
