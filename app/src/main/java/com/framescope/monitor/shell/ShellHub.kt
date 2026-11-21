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

    private val _status = MutableStateFlow(AccessStatus())
    val status: StateFlow<AccessStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _adbReady = MutableStateFlow(false)
    private val _adbReason = MutableStateFlow("")
    val adbAuthState: StateFlow<AdbAuthState> = adb.authState

    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            }.collect { _status.value = it }
        }
    }

    suspend fun refresh(forceReconnect: Boolean = false): AccessStatus = withContext(Dispatchers.IO) {
        val state = shizukuRunner.refresh(forceReconnect)
        // A privileged shell may hand the app the permission it needs to read
        // the frame timelines by itself. It is asked for once.
        if (state == ShizukuRunner.ConnectionState.READY) {
            tryGrantDumpPermission()
        }
        val adbOk = if (adb.authorisationStored || _adbReady.value || forceReconnect) {
            runCatching { adb.isAvailable() }.getOrDefault(false)
        } else {
            false
        }
        publishAdbState(adbOk)
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    /**
     * One-time local ADB setup.
     *
     * The user only taps this once. The app looks for the phone's own `adbd`,
     * moves it to a fixed loopback port, and keeps the RSA key after the
     * "Always allow" prompt. Later launches reconnect silently.
     */
    suspend fun setupAdbBridge(): String = withContext(Dispatchers.IO) {
        adb.retarget(LOOPBACK, adbPort)

        if (runCatching { adb.isAvailable() }.getOrDefault(false)) {
            publishAdbState(true)
            return@withContext adbSuccessMessage()
        }

        val repairer = adb as AdbBridgeRepairer
        val discovered = runCatching { repairer.discoverLocalAdbPort() }.getOrNull()
        if (discovered != null) {
            val switched = runCatching {
                repairer.requestTcpPort(adbPort, discovered)
            }.getOrDefault(false)
            if (switched) {
                delay(1_500)
                if (runCatching { adb.isAvailable() }.getOrDefault(false)) {
                    publishAdbState(true)
                    return@withContext adbSuccessMessage()
                }
            }
        }

        // Shizuku can prepare a classic loopback bridge even when the wireless
        // debugging port speaks TLS and cannot be used by a plain ADB client.
        val helper: ShellRunner? = shizukuRunner
            .takeIf { it.connectionState.value == ShizukuRunner.ConnectionState.READY }
        if (bootstrapAdbPort(helper)) {
            publishAdbState(true)
            return@withContext adbSuccessMessage()
        }

        publishAdbState(false)
        val reason = adb.unavailableReason()
        "Could not open the local ADB bridge. $reason " +
            "Turn on Wireless debugging, accept the prompt with “Always allow”, then try again."
    }

    private suspend fun bootstrapAdbPort(helper: ShellRunner?): Boolean {
        val command = "setprop persist.adb.tcp.port $adbPort; " +
            "setprop service.adb.tcp.port $adbPort; stop adbd; start adbd"
        val accepted = runCatching {
            when {
                helper != null -> helper.exec(command, 8_000).ok
                hasDumpPermission() -> execLocalShell(command, 8_000).ok
                else -> false
            }
        }.getOrDefault(false)
        if (!accepted) return false
        repeat(6) {
            delay(700)
            if (runCatching { adb.isAvailable() }.getOrDefault(false)) return true
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
            "Local ADB bridge has not been set up yet."
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
                detail = "Local ADB bridge connected on $LOOPBACK:$adbPort. It reconnects automatically.",
                checkedAt = System.currentTimeMillis(),
                permissionGranted = true,
                binderAvailable = false,
                userServiceConnected = false,
                commandVerified = true,
                shellSource = "Local ADB bridge",
                adbReady = true,
                adbAuthorised = true,
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
                "Grant FrameScope Shizuku permission to enable privileged operations."
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
    }
}
