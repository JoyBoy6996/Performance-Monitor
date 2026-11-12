package com.framescope.monitor.shell

enum class ShellExecutionStatus {
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED
}

/** Result of a single shell command execution. */
data class ShellResult(
    val code: Int,
    val out: String,
    val err: String = "",
    val status: ShellExecutionStatus = if (code == 0) {
        ShellExecutionStatus.SUCCESS
    } else {
        ShellExecutionStatus.FAILURE
    },
    val executionId: String? = null
) {
    val ok: Boolean get() = status == ShellExecutionStatus.SUCCESS
    val text: String get() = if (out.isNotBlank()) out else err
}

/** The Shizuku shell execution contract. */
interface ShellRunner {
    suspend fun isAvailable(): Boolean

    /** Extra detail shown in the UI when [isAvailable] is false. */
    suspend fun unavailableReason(): String = ""

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult
}

class ShellUnavailable(message: String) : Exception(message)
