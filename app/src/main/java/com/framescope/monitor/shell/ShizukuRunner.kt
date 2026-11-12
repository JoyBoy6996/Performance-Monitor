package com.framescope.monitor.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.framescope.monitor.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Executes privileged commands exclusively through the official Shizuku UserService API.
 *
 * Implements full lifecycle management:
 * 1. Shizuku binder tracking (received, dead)
 * 2. Permission requests and result callbacks
 * 3. UserService binding, unbinding, and remote death observation
 * 4. Verification of actual command execution before marking state as READY
 * 5. Reusable command execution with timeouts, stdout/stderr extraction, and cancellation cleanup
 */
class ShizukuRunner(
    private val context: Context? = null,
    private var mockService: IShizukuService? = null
) : ShellRunner {

    enum class ConnectionState {
        NOT_RUNNING,
        PERMISSION_DENIED,
        CONNECTING,
        READY,
        ERROR
    }

    override val id = "shizuku"
    override val label = "Shizuku (shell)"

    private val mutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.NOT_RUNNING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile
    private var service: IShizukuService? = mockService

    @Volatile
    private var isBound = false

    private val deathRecipient = IBinder.DeathRecipient {
        service = null
        isBound = false
        _connectionState.value = ConnectionState.NOT_RUNNING
        _errorMessage.value = "Shizuku UserService died unexpectedly"
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Received null binder from Shizuku UserService"
                return
            }

            runCatching { binder.linkToDeath(deathRecipient, 0) }

            val stub = IShizukuService.Stub.asInterface(binder)
            service = stub
            isBound = true

            // Verify actual command execution before declaring READY
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).run {
                kotlinx.coroutines.launch {
                    verifyService(stub)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku UserService disconnected"
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, resultCode ->
        if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
        if (resultCode == PackageManager.PERMISSION_GRANTED) {
            _errorMessage.value = null
            bindUserService()
        } else {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            _errorMessage.value = "Shizuku permission denied by user"
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        isBound = false
        _connectionState.value = ConnectionState.NOT_RUNNING
        _errorMessage.value = "Shizuku service is not running"
    }

    init {
        if (mockService == null) {
            runCatching {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            }
            checkAndBind()
        } else {
            _connectionState.value = ConnectionState.READY
        }
    }

    fun setMockServiceForTest(mock: IShizukuService?) {
        this.mockService = mock
        this.service = mock
        _connectionState.value = if (mock != null) ConnectionState.READY else ConnectionState.NOT_RUNNING
    }

    private fun checkAndBind() {
        if (mockService != null) {
            _connectionState.value = ConnectionState.READY
            return
        }

        val pingOk = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!pingOk) {
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running"
            return
        }

        val hasPermission = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        if (!hasPermission) {
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            _errorMessage.value = "Shizuku permission not granted"
            return
        }

        bindUserService()
    }

    private fun bindUserService() {
        if (isBound && service != null) return
        val ctx = context ?: return

        _connectionState.value = ConnectionState.CONNECTING

        val serviceArgs = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        runCatching {
            Shizuku.bindUserService(serviceArgs, userServiceConnection)
        }.onFailure { e ->
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Failed to bind Shizuku UserService: ${e.message}"
        }
    }

    private suspend fun verifyService(stub: IShizukuService) {
        val verificationProbe = runCatching {
            val bundle = stub.execute("echo $VERIFY_PROBE_TOKEN", 4_000)
            val stdout = bundle.getString(ShizukuUserService.KEY_STDOUT, "")
            val exitCode = bundle.getInt(ShizukuUserService.KEY_EXIT_CODE, -1)
            exitCode == 0 && stdout.contains(VERIFY_PROBE_TOKEN)
        }.getOrDefault(false)

        if (verificationProbe) {
            _connectionState.value = ConnectionState.READY
            _errorMessage.value = null
        } else {
            _connectionState.value = ConnectionState.ERROR
            _errorMessage.value = "Shizuku UserService command execution probe failed"
        }
    }

    override suspend fun isAvailable(): Boolean = mutex.withLock {
        if (mockService != null) return@withLock true
        if (_connectionState.value != ConnectionState.READY || service == null) {
            checkAndBind()
            return@withLock _connectionState.value == ConnectionState.READY && service != null
        }
        true
    }

    override suspend fun unavailableReason(): String = when (_connectionState.value) {
        ConnectionState.NOT_RUNNING ->
            "Shizuku is not running. Please start Shizuku on your phone."
        ConnectionState.PERMISSION_DENIED ->
            "Shizuku permission has not been granted to FrameScope."
        ConnectionState.CONNECTING ->
            "Connecting to Shizuku UserService..."
        ConnectionState.ERROR ->
            _errorMessage.value ?: "Shizuku UserService connection error"
        ConnectionState.READY -> ""
    }

    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (mockService != null) {
            _connectionState.value = ConnectionState.READY
            return@withContext true
        }

        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running"
            return@withContext false
        }

        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            bindUserService()
            return@withContext true
        }

        runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    suspend fun refresh(): ConnectionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkAndBind()
            val current = service
            if (current != null) {
                verifyService(current)
            }
            _connectionState.value
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val activeService = service ?: run {
            checkAndBind()
            service ?: throw ShellUnavailable(unavailableReason().ifBlank { "Shizuku UserService not available" })
        }

        val resultBundle = try {
            withTimeoutOrNull(timeoutMs + 3_000L) {
                activeService.execute(command, timeoutMs)
            } ?: throw ShellUnavailable("Command timed out after ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ShellUnavailable) {
            throw e
        } catch (e: Exception) {
            _errorMessage.value = e.message
            throw ShellUnavailable(e.message ?: "Failed to execute command via Shizuku UserService")
        }

        val exitCode = resultBundle.getInt(ShizukuUserService.KEY_EXIT_CODE, -1)
        val stdout = resultBundle.getString(ShizukuUserService.KEY_STDOUT, "")
        val stderr = resultBundle.getString(ShizukuUserService.KEY_STDERR, "")

        ShellResult(code = exitCode, out = stdout, err = stderr)
    }

    fun cleanup() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
        if (isBound && context != null) {
            runCatching {
                val serviceArgs = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, ShizukuUserService::class.java.name)
                )
                Shizuku.unbindUserService(serviceArgs, userServiceConnection, true)
            }
        }
        service = null
        isBound = false
    }

    companion object {
        const val REQUEST_CODE = 4101
        private const val VERIFY_PROBE_TOKEN = "__shizuku_ready__"
    }
}
