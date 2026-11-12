package com.framescope.monitor.shell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AccessState { READY, NEEDS_SETUP, UNAVAILABLE }

data class AccessStatus(
    val backendId: String = "shizuku",
    val backendLabel: String = "Shizuku",
    val state: AccessState = AccessState.NEEDS_SETUP,
    val detail: String = "",
    val checkedAt: Long = 0L
)

/**
 * Privileged execution hub powered exclusively by Shizuku.
 *
 * Removes all legacy ADB, Root, and fallback logic, ensuring a robust,
 * unified Shizuku UserService execution layer.
 */
class ShellHub(
    val shizukuRunner: ShizukuRunner
) {

    constructor(context: Context? = null) : this(ShizukuRunner(context))

    private val _status = MutableStateFlow(AccessStatus())
    val status: StateFlow<AccessStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun refresh(): AccessStatus = withContext(Dispatchers.IO) {
        val state = shizukuRunner.refresh()
        val accessState = when (state) {
            ShizukuRunner.ConnectionState.READY -> AccessState.READY
            ShizukuRunner.ConnectionState.PERMISSION_DENIED -> AccessState.NEEDS_SETUP
            ShizukuRunner.ConnectionState.NOT_RUNNING -> AccessState.UNAVAILABLE
            ShizukuRunner.ConnectionState.CONNECTING -> AccessState.NEEDS_SETUP
            ShizukuRunner.ConnectionState.ERROR -> AccessState.UNAVAILABLE
        }

        val detail = if (accessState == AccessState.READY) "" else shizukuRunner.unavailableReason()

        _status.value = AccessStatus(
            backendId = shizukuRunner.id,
            backendLabel = shizukuRunner.label,
            state = accessState,
            detail = detail,
            checkedAt = System.currentTimeMillis()
        )
        _status.value
    }

    suspend fun requestPermission(): Boolean = shizukuRunner.requestPermission()

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult = withContext(Dispatchers.IO) {
        try {
            val result = shizukuRunner.exec(command, timeoutMs)
            _lastError.value = null
            result
        } catch (e: Exception) {
            _lastError.value = e.message ?: "Command failed"
            throw e
        }
    }

    suspend fun execOrNull(command: String, timeoutMs: Long = 8_000): ShellResult? = withContext(Dispatchers.IO) {
        runCatching { exec(command, timeoutMs) }.getOrNull()
    }

    fun cleanup() {
        shizukuRunner.cleanup()
    }
}
