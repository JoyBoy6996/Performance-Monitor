package com.framescope.monitor.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val commandVerified: Boolean = false
)

/**
 * Privileged execution hub.
 *
 * Two ways to run a privileged command:
 *
 * - The app's own shell, when the user granted it the DUMP permission once.
 *   The commands then run in the app's own process, where nothing cuts their
 *   output short and no binder is involved. This is the way the frame rate of
 *   a game was measured in the first place.
 * - Shizuku, which needs nothing from the user beyond its own approval.
 *
 * Shizuku is tried first when it is ready, because it asks nothing of the
 * user. The app's own shell is used when Shizuku is not there, and it is also
 * the path that carries the largest answers.
 */
class ShellHub(
    val shizukuRunner: ShizukuRunner,
    private val appContext: Context? = null
) {

    constructor(context: Context? = null) : this(ShizukuRunner(context), context?.applicationContext)

    private val _status = MutableStateFlow(AccessStatus())
    val status: StateFlow<AccessStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val statusScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        statusScope.launch {
            combine(
                shizukuRunner.connectionState,
                shizukuRunner.connectionDetails,
                shizukuRunner.errorMessage
            ) { state, details, error ->
                accessStatus(state, details, error)
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
        _status.value = accessStatus(
            state,
            shizukuRunner.connectionDetails.value,
            shizukuRunner.errorMessage.value
        )
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult = withContext(Dispatchers.IO) {
        // The app's own shell is tried first when it holds the DUMP permission.
        // A command that is not there takes the whole answer with it, so a
        // shell that answers nothing falls through to Shizuku.
        if (hasDumpPermission()) {
            val local = execLocalShell(command, timeoutMs)
            if (local.ok && local.out.isNotBlank()) return@withContext local
        }
        try {
            val result = shizukuRunner.exec(command, timeoutMs)
            _lastError.value = if (result.status == ShellExecutionStatus.SUCCESS) {
                null
            } else {
                result.err.ifBlank { "Command ${result.status.name.lowercase()}." }
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Command failed"
            throw e
        }
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
        shizukuRunner.cleanup()
    }

    private fun accessStatus(
        state: ShizukuRunner.ConnectionState,
        details: ShizukuRunner.ConnectionDetails,
        error: String?
    ): AccessStatus {
        // The app's own shell is enough on its own: with the DUMP permission
        // it reads the frame timelines of every app without Shizuku.
        if (state != ShizukuRunner.ConnectionState.READY && hasDumpPermission()) {
            return AccessStatus(
                state = AccessState.READY,
                detail = "One-Time ADB (DUMP) active",
                checkedAt = System.currentTimeMillis(),
                permissionGranted = true,
                binderAvailable = details.binderAvailable,
                userServiceConnected = false,
                commandVerified = true
            )
        }
        val accessState = when (state) {
            ShizukuRunner.ConnectionState.READY -> AccessState.READY
            ShizukuRunner.ConnectionState.PERMISSION_DENIED,
            ShizukuRunner.ConnectionState.CONNECTING -> AccessState.NEEDS_SETUP
            ShizukuRunner.ConnectionState.NOT_RUNNING,
            ShizukuRunner.ConnectionState.ERROR -> AccessState.UNAVAILABLE
        }
        val detail = if (accessState == AccessState.READY) "" else {
            error ?: when (state) {
                ShizukuRunner.ConnectionState.NOT_RUNNING ->
                    "Shizuku is not running. Cached data remains available."
                ShizukuRunner.ConnectionState.PERMISSION_DENIED ->
                    "Grant FrameScope Shizuku permission to enable privileged operations."
                ShizukuRunner.ConnectionState.CONNECTING ->
                    "Connecting to Shizuku UserService. Cached data remains available."
                ShizukuRunner.ConnectionState.ERROR -> "Shizuku UserService connection error."
                ShizukuRunner.ConnectionState.READY -> ""
            }
        }
        return AccessStatus(
            state = accessState,
            detail = detail,
            checkedAt = System.currentTimeMillis(),
            permissionGranted = details.permissionGranted,
            binderAvailable = details.binderAvailable,
            userServiceConnected = details.userServiceConnected,
            commandVerified = details.commandVerified
        )
    }
}
