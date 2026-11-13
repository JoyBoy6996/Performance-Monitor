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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 * Privileged execution hub powered by Shizuku, with automatic fallback to
 * permanent one-time ADB permissions (DUMP + PACKAGE_USAGE_STATS).
 */
class ShellHub(
    val shizukuRunner: ShizukuRunner,
    private val appContext: Context? = null
) {

    constructor(context: Context?) : this(ShizukuRunner(context), context?.applicationContext)
    constructor() : this(ShizukuRunner(null), null)

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

    fun hasDumpPermission(): Boolean {
        val ctx = appContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED
    }

    suspend fun refresh(forceReconnect: Boolean = false): AccessStatus = withContext(Dispatchers.IO) {
        val state = shizukuRunner.refresh(forceReconnect)
        _status.value = accessStatus(
            state,
            shizukuRunner.connectionDetails.value,
            shizukuRunner.errorMessage.value
        )
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult = withContext(Dispatchers.IO) {
        val shizukuReady = shizukuRunner.connectionState.value == ShizukuRunner.ConnectionState.READY
        if (!shizukuReady && hasDumpPermission()) {
            return@withContext execLocalShell(command, timeoutMs)
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
            if (hasDumpPermission()) {
                return@withContext execLocalShell(command, timeoutMs)
            }
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

    private fun execLocalShell(command: String, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()
            val outThread = Thread {
                runCatching { process.inputStream.bufferedReader().use { stdoutBuf.append(it.readText()) } }
            }.apply { isDaemon = true; start() }
            val errThread = Thread {
                runCatching { process.errorStream.bufferedReader().use { stderrBuf.append(it.readText()) } }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs.coerceIn(100L, 30_000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(200L)
                errThread.join(200L)
                return ShellResult(-1, stdoutBuf.toString(), "Command timed out", ShellExecutionStatus.TIMEOUT)
            }
            outThread.join(500L)
            errThread.join(500L)
            val code = process.exitValue()
            val status = if (code == 0) ShellExecutionStatus.SUCCESS else ShellExecutionStatus.FAILURE
            _lastError.value = null
            ShellResult(code, stdoutBuf.toString(), stderrBuf.toString(), status)
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Local shell failed"
            ShellResult(-1, "", e.message.orEmpty(), ShellExecutionStatus.FAILURE)
        }
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
                    "Shizuku is not running. Grant DUMP via ADB once or start Shizuku."
                ShizukuRunner.ConnectionState.PERMISSION_DENIED ->
                    "Grant FrameScope Shizuku permission or run pm grant DUMP via ADB."
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
