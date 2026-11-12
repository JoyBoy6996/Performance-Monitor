package com.framescope.monitor.shell

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream

/**
 * Runs commands through a running Shizuku service, which grants us the `shell`
 * (adb level) user without rooting the device.
 *
 * This is exactly the privilege level aShell / aShell You operate at, so
 * anything those apps can read, FrameScope can read too.
 */
class ShizukuRunner : ShellRunner {

    override val id = "shizuku"
    override val label = "Shizuku (shell)"

    override suspend fun isAvailable(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun unavailableReason(): String = when {
        !Shizuku.pingBinder() ->
            "Shizuku is not running. Start it once with ADB (see Setup) or from aShell / aShell You."
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
            "FrameScope still needs the Shizuku permission. Tap 'Request permission' in Setup."
        else -> ""
    }

    /** Shows the Shizuku permission dialog. Returns true when permission is already granted. */
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!Shizuku.pingBinder()) return@withContext false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext true
        Shizuku.requestPermission(REQUEST_CODE)
        false
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) throw ShellUnavailable("Shizuku is not running")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw ShellUnavailable("Shizuku permission not granted")
        }
        val binder = Shizuku.getBinder() ?: throw ShellUnavailable("Shizuku binder unavailable")
        val service = IShizukuService.Stub.asInterface(binder)

        val remote = service.newProcess(arrayOf("sh", "-c", command), null, "/")
        val outTask = async(Dispatchers.IO) { readAll(remote.inputStream) }
        val errTask = async(Dispatchers.IO) { readAll(remote.errorStream) }
        // We never write to stdin, closing it makes the remote process see EOF.
        runCatching { remote.outputStream.close() }

        val out = outTask.await()
        val err = errTask.await()
        val code = runCatching { remote.waitFor() }.getOrElse { -1 }
        runCatching { remote.destroy() }
        ShellResult(code, out, err)
    }

    private fun readAll(pfd: ParcelFileDescriptor?): String {
        if (pfd == null) return ""
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input: InputStream ->
                val buffer = ByteArray(8192)
                val sb = StringBuilder()
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sb.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                sb.toString()
            }
        }.getOrElse { "" }
    }

    companion object {
        const val REQUEST_CODE = 4101
    }
}
