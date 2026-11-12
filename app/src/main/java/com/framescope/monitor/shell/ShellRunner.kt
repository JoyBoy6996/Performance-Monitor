package com.framescope.monitor.shell

/** Result of a single shell command execution. */
data class ShellResult(
    val code: Int,
    val out: String,
    val err: String = ""
) {
    val ok: Boolean get() = code == 0
    val text: String get() = if (out.isNotBlank()) out else err
}

/** A way of executing commands with elevated (shell / root) privileges. */
interface ShellRunner {
    val id: String
    val label: String

    /** Cheap check whether this backend can be used right now. */
    suspend fun isAvailable(): Boolean

    /** Extra detail shown in the UI when [isAvailable] is false. */
    suspend fun unavailableReason(): String = ""

    suspend fun exec(command: String, timeoutMs: Long = 8_000): ShellResult
}

class ShellUnavailable(message: String) : Exception(message)
