package com.framescope.monitor.shell

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Isolated service running in Shizuku's privileged environment (shell / UID 2000).
 *
 * This class is loaded and started directly by the Shizuku server via the official
 * UserService API. It executes commands with elevated shell privileges, handling
 * stdout, stderr, exit codes, process lifecycle, and cleanup.
 */
@Keep
class ShizukuUserService : IShizukuService.Stub {

    constructor() : super()

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    private class Execution {
        private var process: java.lang.Process? = null
        private var finished = false
        private var cancellationRequested = false

        @Synchronized
        fun attach(process: java.lang.Process): Boolean {
            if (finished) return false
            this.process = process
            if (cancellationRequested) process.destroyForcibly()
            return !cancellationRequested
        }

        @Synchronized
        fun cancel(): Boolean {
            if (finished) return false
            cancellationRequested = true
            process?.destroyForcibly()
            return true
        }

        @Synchronized
        fun finish(): Boolean {
            finished = true
            process = null
            return cancellationRequested
        }
    }

    private val executions = ConcurrentHashMap<String, Execution>()
    private val cancellationsBeforeStart = ConcurrentHashMap<String, Long>()
    private val recentlyFinished = ConcurrentHashMap<String, Long>()
    private val executionLock = Any()
    private val destroyed = AtomicBoolean(false)

    override fun destroy() {
        synchronized(executionLock) {
            if (!destroyed.compareAndSet(false, true)) return
            executions.values.forEach { it.cancel() }
            cancellationsBeforeStart.clear()
            recentlyFinished.clear()
        }
    }

    override fun exit() {
        destroy()
        exitProcess(0)
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun execute(executionId: String, command: String, timeoutMs: Long): Bundle {
        if (executionId.isBlank()) {
            return resultBundle(
                executionId.take(MAX_EXECUTION_ID_LENGTH),
                ShellExecutionStatus.FAILURE,
                -1,
                "",
                "Execution ID must not be blank"
            )
        }
        if (executionId.length > MAX_EXECUTION_ID_LENGTH) {
            return resultBundle(
                executionId.take(MAX_EXECUTION_ID_LENGTH),
                ShellExecutionStatus.FAILURE,
                -1,
                "",
                "Execution ID exceeds the maximum length of $MAX_EXECUTION_ID_LENGTH characters"
            )
        }
        if (command.length > MAX_COMMAND_LENGTH) {
            return resultBundle(
                executionId,
                ShellExecutionStatus.FAILURE,
                -1,
                "",
                "Command exceeds the maximum length of $MAX_COMMAND_LENGTH characters"
            )
        }
        if (destroyed.get()) {
            return resultBundle(
                executionId,
                ShellExecutionStatus.FAILURE,
                -1,
                "",
                "Shizuku UserService is shutting down"
            )
        }

        val execution = Execution()
        val registrationStatus = synchronized(executionLock) {
            pruneExpiredCancellations()
            when {
                destroyed.get() -> ShellExecutionStatus.FAILURE
                cancellationsBeforeStart.remove(executionId) != null -> {
                    rememberFinishedExecution(executionId)
                    ShellExecutionStatus.CANCELLED
                }
                recentlyFinished.containsKey(executionId) -> ShellExecutionStatus.FAILURE
                executions.putIfAbsent(executionId, execution) != null -> ShellExecutionStatus.FAILURE
                else -> null
            }
        }
        if (registrationStatus != null) {
            val message = when {
                registrationStatus == ShellExecutionStatus.CANCELLED -> "Command cancelled"
                destroyed.get() -> "Shizuku UserService is shutting down"
                else -> "Execution ID is already active"
            }
            return resultBundle(executionId, registrationStatus, -1, "", message)
        }

        val effectiveTimeout = timeoutMs.coerceIn(100L, 120_000L)
        var process: java.lang.Process? = null
        val stdoutBuffer = CapturedOutput(MAX_OUTPUT_BYTES_PER_STREAM)
        val stderrBuffer = CapturedOutput(MAX_OUTPUT_BYTES_PER_STREAM)
        var stdoutThread: Thread? = null
        var stderrThread: Thread? = null
        var interrupted = false
        try {
            val startedProcess = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            process = startedProcess
            if (!execution.attach(startedProcess)) {
                startedProcess.destroyForcibly()
            }

            stdoutThread = Thread(
                { drainStream(startedProcess.inputStream, stdoutBuffer) },
                "shizuku-stdout-$executionId"
            ).apply {
                isDaemon = true
                start()
            }
            stderrThread = Thread(
                { drainStream(startedProcess.errorStream, stderrBuffer) },
                "shizuku-stderr-$executionId"
            ).apply {
                isDaemon = true
                start()
            }
            startedProcess.outputStream.close()

            val completed = try {
                startedProcess.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                interrupted = true
                execution.cancel()
                false
            }
            val cancelled = execution.finish()
            if (!completed) {
                startedProcess.destroyForcibly()
                try {
                    startedProcess.waitFor(1_000, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            join(stdoutThread, if (completed) 1_000L else 250L)
            join(stderrThread, if (completed) 1_000L else 250L)

            val status = when {
                cancelled -> ShellExecutionStatus.CANCELLED
                completed && startedProcess.exitValue() == 0 -> ShellExecutionStatus.SUCCESS
                completed -> ShellExecutionStatus.FAILURE
                else -> ShellExecutionStatus.TIMEOUT
            }
            val error = when (status) {
                ShellExecutionStatus.CANCELLED -> appendMessage(stderrBuffer, "Command cancelled")
                ShellExecutionStatus.TIMEOUT -> appendMessage(
                    stderrBuffer,
                    "Command timed out after ${effectiveTimeout}ms"
                )
                else -> stderrBuffer.asString()
            }
            return resultBundle(
                executionId,
                status,
                if (completed) startedProcess.exitValue() else -1,
                stdoutBuffer.asString(),
                error
            )
        } catch (e: Exception) {
            process?.destroyForcibly()
            execution.finish()
            return resultBundle(
                executionId,
                ShellExecutionStatus.FAILURE,
                -1,
                stdoutBuffer.asString(),
                e.message ?: "Failed to start shell process"
            )
        } finally {
            execution.finish()
            synchronized(executionLock) {
                executions.remove(executionId, execution)
                rememberFinishedExecution(executionId)
            }
            join(stdoutThread, 250L)
            join(stderrThread, 250L)
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun cancel(executionId: String): Boolean {
        if (executionId.isBlank() || executionId.length > MAX_EXECUTION_ID_LENGTH || destroyed.get()) return false
        return synchronized(executionLock) {
            if (destroyed.get()) return@synchronized false
            pruneExpiredCancellations()
            val execution = executions[executionId]
            if (execution != null) {
                execution.cancel()
            } else if (recentlyFinished.containsKey(executionId)) {
                false
            } else if (cancellationsBeforeStart.size < MAX_PENDING_CANCELLATIONS) {
                cancellationsBeforeStart[executionId] = System.currentTimeMillis() + CANCELLATION_TTL_MS
                true
            } else {
                false
            }
        }
    }

    private class CapturedOutput(private val limitBytes: Int) {
        private val buffer = ByteArrayOutputStream(limitBytes)
        private var truncated = false

        @Synchronized
        fun append(bytes: ByteArray, length: Int) {
            val remaining = limitBytes - buffer.size()
            if (remaining > 0) buffer.write(bytes, 0, minOf(length, remaining))
            if (length > remaining) truncated = true
        }

        @Synchronized
        fun asString(): String {
            val contents = buffer.toString("UTF-8")
            return if (truncated) {
                "$contents\n[output truncated after $limitBytes bytes]"
            } else {
                contents
            }
        }
    }

    private fun drainStream(input: InputStream, output: CapturedOutput) {
        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } > 0) {
                output.append(buffer, bytesRead)
            }
        } catch (_: IOException) {
            // Closing a process stream during cancellation or timeout is expected.
        }
    }

    private fun join(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun appendMessage(buffer: CapturedOutput, message: String): String {
        val contents = buffer.asString()
        return if (contents.isBlank()) message else "$contents\n$message"
    }

    private fun resultBundle(
        executionId: String,
        status: ShellExecutionStatus,
        exitCode: Int,
        stdout: String,
        stderr: String
    ) = Bundle().apply {
        putString(KEY_EXECUTION_ID, executionId.take(MAX_EXECUTION_ID_LENGTH))
        putString(KEY_STATUS, status.name)
        putInt(KEY_EXIT_CODE, exitCode)
        putString(KEY_STDOUT, stdout.take(MAX_RESULT_TEXT_CHARS))
        putString(KEY_STDERR, stderr.take(MAX_RESULT_TEXT_CHARS))
    }

    private fun pruneExpiredCancellations() {
        val now = System.currentTimeMillis()
        cancellationsBeforeStart.entries.removeAll { it.value <= now }
        recentlyFinished.entries.removeAll { it.value <= now }
    }

    private fun rememberFinishedExecution(executionId: String) {
        if (recentlyFinished.size >= MAX_RECORDED_COMPLETIONS) {
            val oldest = recentlyFinished.entries.minByOrNull { it.value }
            if (oldest != null) recentlyFinished.remove(oldest.key, oldest.value)
        }
        recentlyFinished[executionId] = System.currentTimeMillis() + CANCELLATION_TTL_MS
    }

    internal val activeExecutionCount: Int
        get() = executions.size

    companion object {
        const val KEY_EXECUTION_ID = "execution_id"
        const val KEY_STATUS = "execution_status"
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        private const val MAX_EXECUTION_ID_LENGTH = 128
        internal const val MAX_COMMAND_LENGTH = 32 * 1024
        // Parcel strings use UTF-16, so this keeps both output fields well below Binder's transaction limit.
        internal const val MAX_OUTPUT_BYTES_PER_STREAM = 32 * 1024
        internal const val MAX_RESULT_TEXT_CHARS = MAX_OUTPUT_BYTES_PER_STREAM + 256
        private const val MAX_PENDING_CANCELLATIONS = 4_096
        private const val MAX_RECORDED_COMPLETIONS = 4_096
        private const val CANCELLATION_TTL_MS = 60_000L
    }
}
