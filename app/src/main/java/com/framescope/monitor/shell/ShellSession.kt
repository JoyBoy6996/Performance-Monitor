package com.framescope.monitor.shell

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A shell that stays open and answers many commands.
 *
 * Every command of the one-shot transport starts a process, and a process on a
 * phone is not free: it is a fork, an exec and a linker run while the app that
 * is being measured is trying to draw. The Scene-style guide calls the thing
 * that replaces this a daemon - one privileged process that keeps the counters
 * warm and answers in a single round trip. This class is that daemon for the
 * shell: the process is started once and then reused for every read.
 *
 * The protocol is a marker line. The command is written to the shell's stdin,
 * followed by a line that prints a unique marker and the exit code of the
 * command. Everything printed before the marker is the output of the command.
 *
 * The command is run with its standard input on `/dev/null`, so a command that
 * reads from stdin gets an empty file instead of eating the marker that ends
 * the answer.
 */
class ShellSession(
    val id: String = UUID.randomUUID().toString(),
    private val maxOutputBytes: Int = MAX_OUTPUT_BYTES
) {

    private val process: Process = ProcessBuilder("sh")
        .redirectErrorStream(false)
        .start()

    private val stdin: OutputStream = process.outputStream
    private val stdout: InputStream = process.inputStream

    /** The chunks of standard output that arrived, waiting to be read. */
    private val stdoutChunks = LinkedBlockingQueue<Chunk>()

    /** What the command wrote to its error stream. */
    private val stderrBuffer = StreamBuffer(maxOutputBytes)

    private val stdoutThread = Thread({ pumpStdout() }, "framescope-shell-stdout-$id").apply {
        isDaemon = true
        start()
    }

    private val stderrThread = Thread({ pumpStderr() }, "framescope-shell-stderr-$id").apply {
        isDaemon = true
        start()
    }

    @Volatile
    private var closed = false

    /** When the session was last used, on the wall clock. */
    @Volatile
    var lastUsedAtMs: Long = System.currentTimeMillis()
        private set

    /** True while the shell is still there. */
    val isAlive: Boolean get() = !closed && process.isAlive

    /**
     * Runs one command in the open shell.
     *
     * The result is the same shape as a one-shot command, so every caller can
     * treat the two the same. A command that runs into its timeout leaves the
     * shell in an unknown state, so the session is closed and the next command
     * starts a new one.
     */
    @Synchronized
    fun run(command: String, timeoutMs: Long): ShellResult {
        if (closed) {
            return ShellResult(
                code = -1,
                out = "",
                err = "The shell session is closed.",
                status = ShellExecutionStatus.FAILURE
            )
        }
        lastUsedAtMs = System.currentTimeMillis()

        val marker = "framescope-${UUID.randomUUID().toString().replace("-", "")}"
        val script = buildString {
            // The command runs in a subshell: a command that ends the shell
            // (`exit`) must not take the session with it, and every command
            // has to start from the same clean state a one-shot command had.
            // The redirection is on the subshell, so it covers the whole
            // command and not only its last part - a pipeline whose last
            // stage reads from stdin would otherwise swallow the pipe.
            append("( ")
            append(command)
            append(" ) </dev/null\n")
            append("__framescope_status=${'$'}?\n")
            append("printf '%s:%s\\n' '")
            append(marker)
            append("' \"${'$'}__framescope_status\"\n")
        }
        try {
            stdin.write(script.toByteArray(Charsets.UTF_8))
            stdin.flush()
        } catch (e: IOException) {
            closed = true
            return ShellResult(
                code = -1,
                out = "",
                err = "The shell session ended: ${e.message.orEmpty()}",
                status = ShellExecutionStatus.FAILURE
            )
        }
        stderrBuffer.reset()

        return when (val outcome = readUntilMarker(marker, timeoutMs)) {
            is Outcome.Found -> {
                // The marker is printed after the command is done, so everything
                // the command wrote to its error stream is already in the pipe.
                // The thread that reads it may not have picked it up yet.
                awaitStderrSettled()
                ShellResult(
                    code = outcome.exitCode,
                    out = outcome.stdout,
                    err = stderrBuffer.asString(),
                    status = if (outcome.exitCode == 0) {
                        ShellExecutionStatus.SUCCESS
                    } else {
                        ShellExecutionStatus.FAILURE
                    }
                )
            }

            Outcome.TimedOut -> {
                // The shell is stuck inside a command that never ends. Its
                // streams are out of sync, so it cannot be used again.
                close()
                ShellResult(
                    code = -1,
                    out = "",
                    err = "The command timed out after ${timeoutMs}ms.",
                    status = ShellExecutionStatus.TIMEOUT
                )
            }

            Outcome.Closed -> {
                close()
                ShellResult(
                    code = -1,
                    out = "",
                    err = "The shell session ended.",
                    status = ShellExecutionStatus.FAILURE
                )
            }
        }
    }

    /** Closes the shell and every stream it owns. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            stdin.close()
        } catch (_: IOException) {
            // The shell is going away anyway.
        }
        try {
            stdout.close()
        } catch (_: IOException) {
            // The shell is going away anyway.
        }
        process.destroyForcibly()
        stdoutChunks.clear()
    }

    /** What one read produced. */
    private sealed class Outcome {
        /** The marker arrived; the command is finished. */
        data class Found(val exitCode: Int, val stdout: String) : Outcome()

        /** The command did not finish in time. */
        data object TimedOut : Outcome()

        /** The shell died. */
        data object Closed : Outcome()
    }

    /**
     * Reads the output until the marker of this command shows up.
     *
     * The marker is the last thing the shell prints, so it is looked for in the
     * tail of what arrived. The output itself is kept up to the limit and is
     * marked as truncated when it was longer.
     */
    private fun readUntilMarker(marker: String, timeoutMs: Long): Outcome {
        val markerBytes = marker.toByteArray(Charsets.UTF_8)
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        val collected = ByteArrayOutputStream()
        // The marker is looked for in the tail of what arrived: an answer that
        // was too long for the limit no longer holds the marker, but the tail
        // still does.
        val tail = ByteArray(markerBytes.size + 32)
        var tailSize = 0
        var truncated = false

        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) return Outcome.TimedOut
            val chunk = stdoutChunks.poll(remainingMs, TimeUnit.MILLISECONDS) ?: return Outcome.TimedOut
            if (chunk.endOfOutput) return Outcome.Closed

            val room = maxOutputBytes - collected.size()
            if (room <= 0) {
                truncated = true
            } else {
                val take = minOf(room, chunk.bytes.size)
                collected.write(chunk.bytes, 0, take)
                if (take < chunk.bytes.size) truncated = true
            }

            tailSize = appendToTail(tail, tailSize, chunk.bytes)
            val tailBytes = tail.copyOf(tailSize)
            val markerAt = indexOf(tailBytes, markerBytes, 0)
            if (markerAt < 0) continue

            val after = String(
                tailBytes,
                markerAt + markerBytes.size,
                tailBytes.size - markerAt - markerBytes.size,
                Charsets.UTF_8
            )
            val exitCode = after.trim().removePrefix(":").trim().toIntOrNull() ?: -1
            val bytes = collected.toByteArray()
            val markerInOutput = indexOf(bytes, markerBytes, 0)
            val stdout = if (markerInOutput >= 0) {
                String(bytes, 0, markerInOutput, Charsets.UTF_8)
            } else {
                String(bytes, Charsets.UTF_8)
            }
            return Outcome.Found(
                exitCode = exitCode,
                stdout = if (truncated) {
                    "$stdout\n[output truncated after $maxOutputBytes bytes]"
                } else {
                    stdout
                }
            )
        }
    }

    /** Keeps the last bytes of the stream, so the marker can be found in them. */
    private fun appendToTail(tail: ByteArray, size: Int, chunk: ByteArray): Int {
        if (chunk.size >= tail.size) {
            System.arraycopy(chunk, chunk.size - tail.size, tail, 0, tail.size)
            return tail.size
        }
        val keep = minOf(size, tail.size - chunk.size)
        System.arraycopy(tail, size - keep, tail, 0, keep)
        System.arraycopy(chunk, 0, tail, keep, chunk.size)
        return keep + chunk.size
    }

    /** The first place [needle] shows up in [haystack], at or after [from]. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        var index = from.coerceIn(0, last)
        outer@ while (index <= last) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) {
                    index++
                    continue@outer
                }
            }
            return index
        }
        return -1
    }

    /**
     * Waits until the error output has stopped growing.
     *
     * The command is finished when the marker arrives, so what is left is only
     * the work of the thread that reads the error stream. It is given a moment
     * to catch up, and the wait ends as soon as nothing more arrives.
     */
    private fun awaitStderrSettled() {
        val deadline = System.nanoTime() + STDERR_SETTLE_TIMEOUT_NS
        var lastSize = -1
        while (System.nanoTime() < deadline) {
            val size = stderrBuffer.size()
            if (size == lastSize) return
            lastSize = size
            try {
                Thread.sleep(STDERR_SETTLE_STEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /** Moves the standard output into the queue in blocks. */
    private fun pumpStdout() {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val read = stdout.read(buffer)
                if (read < 0) break
                if (read > 0) stdoutChunks.put(Chunk(buffer.copyOf(read)))
            }
        } catch (_: IOException) {
            // The session was closed while the shell was still talking.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        stdoutChunks.put(Chunk(ByteArray(0), endOfOutput = true))
    }

    /** Moves the error output into the buffer. */
    private fun pumpStderr() {
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val read = process.errorStream.read(buffer)
                if (read < 0) break
                if (read > 0) stderrBuffer.append(buffer, read)
            }
        } catch (_: IOException) {
            // The session was closed while the shell was still talking.
        }
    }

    /** A byte that marks the end of the output. */
    private companion object {
        /** How much one command may print. */
        const val MAX_OUTPUT_BYTES = 64 * 1024

        /** How long the error output may take to arrive. */
        const val STDERR_SETTLE_TIMEOUT_NS = 60_000_000L

        /** How often the error output is looked at while it arrives. */
        const val STDERR_SETTLE_STEP_MS = 2L
    }

    /** A block of standard output, or the end of it. */
    private class Chunk(val bytes: ByteArray, val endOfOutput: Boolean = false)

    /** The error output of one command, bounded in size. */
    private class StreamBuffer(private val limitBytes: Int) {
        private val buffer = ByteArrayOutputStream(limitBytes)
        private var truncated = false

        @Synchronized
        fun append(bytes: ByteArray, length: Int) {
            val remaining = limitBytes - buffer.size()
            if (remaining > 0) buffer.write(bytes, 0, minOf(length, remaining))
            if (length > remaining) truncated = true
        }

        @Synchronized
        fun reset() {
            buffer.reset()
            truncated = false
        }

        @Synchronized
        fun size(): Int = buffer.size()

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
}
