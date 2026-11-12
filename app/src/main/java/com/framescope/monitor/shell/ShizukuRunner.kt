package com.framescope.monitor.shell

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    enum class PermissionState { UNKNOWN, GRANTED, DENIED, NOT_RUNNING }

    override val id = "shizuku"
    override val label = "Shizuku (shell)"

    private val _permissionState = MutableStateFlow(PermissionState.UNKNOWN)
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, resultCode ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        _permissionState.value = if (resultCode == PackageManager.PERMISSION_GRANTED) {
            PermissionState.GRANTED
        } else {
            PermissionState.DENIED
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _permissionState.value = PermissionState.NOT_RUNNING
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateStateFromBinder()
    }

    init {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addBinderReceivedListener(binderReceivedListener)
        }
        updateStateFromBinder()
    }

    private fun updateStateFromBinder() {
        _permissionState.value = when {
            !Shizuku.pingBinder() -> PermissionState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> PermissionState.GRANTED
            else -> PermissionState.DENIED
        }
    }

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

    /** Shows the Shizuku permission dialog. Returns true when permission is dispatched or already granted. */
    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!Shizuku.pingBinder()) {
            _permissionState.value = PermissionState.NOT_RUNNING
            return@withContext false
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            _permissionState.value = PermissionState.GRANTED
            return@withContext true
        }
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!Shizuku.pingBinder()) throw ShellUnavailable("Shizuku is not running")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw ShellUnavailable("Shizuku permission not granted")
        }
        val binder = Shizuku.getBinder() ?: throw ShellUnavailable("Shizuku binder unavailable")
        val service = IShizukuService.Stub.asInterface(binder)

        val remote = service.newProcess(arrayOf("sh", "-c", command), null, "/")
        try {
            withTimeout(timeoutMs + 2000L) {
                val outTask = async(Dispatchers.IO) { readAll(remote.inputStream) }
                val errTask = async(Dispatchers.IO) { readAll(remote.errorStream) }
                // We never write to stdin, closing it makes the remote process see EOF.
                runCatching { remote.outputStream?.close() }

                val code = withTimeoutOrNull(timeoutMs) {
                    remote.waitForTimeout(timeoutMs, "ms")
                    remote.exitValue()
                } ?: -1

                val out = outTask.await()
                val err = errTask.await()
                ShellResult(code, out, err)
            }
        } finally {
            runCatching { remote.destroy() }
        }
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
