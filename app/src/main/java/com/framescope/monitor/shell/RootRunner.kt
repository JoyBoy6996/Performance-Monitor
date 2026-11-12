package com.framescope.monitor.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs commands through `su`, for rooted devices. */
class RootRunner : ShellRunner {

    override val id = "root"
    override val label = "Root (su)"

    override suspend fun isAvailable(): Boolean {
        val probe = ProcessBuilder("su", "-c", "id").redirectErrorStream(true)
        return runCatching {
            val p = probe.start()
            runCatching { p.outputStream.close() }
            val output = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().readText() }
                    .getOrNull()
                    ?.let { synchronized(output) { output.append(it) } }
            }
            reader.isDaemon = true
            reader.start()
            // `su` may sit there waiting for a password, so it gets a hard limit.
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return@runCatching false
            }
            reader.join(500)
            synchronized(output) { output.toString() }.contains("uid=0")
        }.getOrDefault(false)
    }

    override suspend fun unavailableReason(): String =
        "No root access. Install a root solution and grant the shell to FrameScope, or use Shizuku."

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
        p.outputStream.close()
        val out = async(Dispatchers.IO) { p.inputStream.bufferedReader().readText() }
        val err = async(Dispatchers.IO) { p.errorStream.bufferedReader().readText() }
        val code = if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.exitValue()
        } else {
            p.destroyForcibly()
            -1
        }
        ShellResult(code, out.await(), err.await())
    }
}
