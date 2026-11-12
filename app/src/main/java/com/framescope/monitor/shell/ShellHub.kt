package com.framescope.monitor.shell

import android.content.Context
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
 * Privileged execution hub powered exclusively by Shizuku.
 *
 * Provides the application's single Shizuku UserService execution layer.
 */
class ShellHub(
    val shizukuRunner: ShizukuRunner
) {

    constructor(context: Context? = null) : this(ShizukuRunner(context))

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

    suspend fun refresh(): AccessStatus = withContext(Dispatchers.IO) {
        val state = shizukuRunner.refresh()
        _status.value = accessStatus(
            state,
            shizukuRunner.connectionDetails.value,
            shizukuRunner.errorMessage.value
        )
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult = withContext(Dispatchers.IO) {
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

    fun cleanup() {
        statusScope.cancel()
        shizukuRunner.cleanup()
    }

    private fun accessStatus(
        state: ShizukuRunner.ConnectionState,
        details: ShizukuRunner.ConnectionDetails,
        error: String?
    ): AccessStatus {
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
