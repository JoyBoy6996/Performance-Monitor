package com.framescope.monitor.shell

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
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

    @Volatile
    private var currentProcess: java.lang.Process? = null

    override fun destroy() {
        runCatching { currentProcess?.destroyForcibly() }
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getUid(): Int {
        return Process.myUid()
    }

    override fun execute(command: String, timeoutMs: Long): Bundle {
        val bundle = Bundle()
        val effectiveTimeout = timeoutMs.coerceIn(100L, 120_000L)

        val process: java.lang.Process = try {
            ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
        } catch (e: Exception) {
            bundle.putInt(KEY_EXIT_CODE, -1)
            bundle.putString(KEY_STDOUT, "")
            bundle.putString(KEY_STDERR, e.message ?: "Failed to start shell process")
            return bundle
        }

        currentProcess = process

        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()

        val stdoutThread = Thread {
            drainStream(process.inputStream, stdoutBuffer)
        }.apply {
            isDaemon = true
            name = "shizuku-stdout"
            start()
        }

        val stderrThread = Thread {
            drainStream(process.errorStream, stderrBuffer)
        }.apply {
            isDaemon = true
            name = "shizuku-stderr"
            start()
        }

        // Close stdin so commands expecting EOF do not hang indefinitely
        runCatching { process.outputStream.close() }

        val completed = try {
            process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }

        val exitCode: Int
        if (completed) {
            exitCode = process.exitValue()
            runCatching { stdoutThread.join(500) }
            runCatching { stderrThread.join(500) }
        } else {
            exitCode = -1
            runCatching { process.destroyForcibly() }
            runCatching { stdoutThread.join(200) }
            runCatching { stderrThread.join(200) }
            synchronized(stderrBuffer) {
                stderrBuffer.write("\nCommand timed out after ${effectiveTimeout}ms\n".toByteArray(Charsets.UTF_8))
            }
        }

        currentProcess = null

        bundle.putInt(KEY_EXIT_CODE, exitCode)
        bundle.putString(KEY_STDOUT, stdoutBuffer.toString("UTF-8"))
        bundle.putString(KEY_STDERR, stderrBuffer.toString("UTF-8"))
        return bundle
    }

    private fun drainStream(input: InputStream, output: ByteArrayOutputStream) {
        runCatching {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } > 0) {
                synchronized(output) {
                    if (output.size() < MAX_OUTPUT_SIZE) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }
        }
    }

    companion object {
        const val KEY_EXIT_CODE = "exit_code"
        const val KEY_STDOUT = "stdout"
        const val KEY_STDERR = "stderr"
        private const val MAX_OUTPUT_SIZE = 2 * 1024 * 1024 // 2 MB safety cap
    }
}
