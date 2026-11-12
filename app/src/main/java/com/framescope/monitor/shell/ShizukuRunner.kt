package com.framescope.monitor.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.framescope.monitor.BuildConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    data class ConnectionDetails(
        val permissionGranted: Boolean = false,
        val binderAvailable: Boolean = false,
        val userServiceConnected: Boolean = false,
        val commandVerified: Boolean = false
    )

    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "framescope-shizuku-command").apply { isDaemon = true }
    }
    private val activeCommands = ConcurrentHashMap<String, IShizukuService>()
    private var retryJob: kotlinx.coroutines.Job? = null
    private var bindingRequested = false
    private var serviceBound = false
    private var linkedBinder: IBinder? = null
    private var linkedDeathRecipient: IBinder.DeathRecipient? = null
    @Volatile
    private var cleanedUp = false

    private val _connectionState = MutableStateFlow(ConnectionState.NOT_RUNNING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectionDetails = MutableStateFlow(ConnectionDetails())
    val connectionDetails: StateFlow<ConnectionDetails> = _connectionDetails.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile
    private var service: IShizukuService? = mockService

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                handleServiceDisconnection("Shizuku UserService returned a null binder")
                return
            }

            val deathRecipient = IBinder.DeathRecipient { handleUserServiceDeath(binder) }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                handleServiceDisconnection("Could not monitor Shizuku UserService binder: ${e.message}")
                return
            }

            val stub = IShizukuService.Stub.asInterface(binder)
            synchronized(lifecycleLock) {
                if (cleanedUp) {
                    try {
                        binder.unlinkToDeath(deathRecipient, 0)
                    } catch (_: NoSuchElementException) {
                        // Binder already removed the recipient after death.
                    }
                    return
                }
                unlinkDeathRecipientLocked()
                linkedBinder = binder
                linkedDeathRecipient = deathRecipient
                bindingRequested = false
                service = stub
                _connectionDetails.update {
                    it.copy(userServiceConnected = true, commandVerified = false)
                }
                _connectionState.value = ConnectionState.CONNECTING
                _errorMessage.value = null
            }
            connectionScope.launch { verifyService(stub) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleServiceDisconnection("Shizuku UserService disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleServiceDisconnection("Shizuku UserService binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleServiceDisconnection("Shizuku UserService returned a null binding")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, resultCode ->
        handlePermissionResult(requestCode, resultCode)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        handleShizukuBinderDeath()
    }

    internal fun handlePermissionResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_CODE) return
        if (resultCode == PackageManager.PERMISSION_GRANTED) {
            _connectionDetails.update { it.copy(permissionGranted = true) }
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = "Shizuku permission granted. Connecting to UserService."
            bindUserService()
        } else {
            handlePermissionDenied(
                "Shizuku permission was denied. Grant FrameScope access in Shizuku to continue."
            )
        }
    }

    internal fun handleShizukuBinderDeath() {
        synchronized(lifecycleLock) {
            _connectionDetails.update {
                it.copy(binderAvailable = false, userServiceConnected = false, commandVerified = false)
            }
            unlinkDeathRecipientLocked()
            service = null
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running. Start Shizuku to restore privileged operations."
        }
        releaseServiceBinding()
    }

    private fun handlePermissionDenied(message: String) {
        synchronized(lifecycleLock) {
            _connectionDetails.update {
                it.copy(permissionGranted = false, userServiceConnected = false, commandVerified = false)
            }
            service = null
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            _errorMessage.value = message
        }
        releaseServiceBinding()
    }

    init {
        if (mockService == null) {
            try {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            } catch (e: Exception) {
                markConnectionError("Could not register Shizuku lifecycle listeners: ${e.message}")
            }
            checkAndBind()
        } else {
            mockService?.let(::verifyMockService)
        }
    }

    internal fun setMockServiceForTest(mock: IShizukuService?) {
        mockService = mock
        service = mock
        if (mock != null) {
            verifyMockService(mock)
        } else {
            _connectionDetails.value = ConnectionDetails()
            _connectionState.value = ConnectionState.NOT_RUNNING
        }
    }

    private fun verifyMockService(mock: IShizukuService) {
        val executionId = UUID.randomUUID().toString()
        val verified = try {
            val result = mock.execute(executionId, "echo $VERIFY_PROBE_TOKEN", PROBE_TIMEOUT_MS)
            result.getString(ShizukuUserService.KEY_EXECUTION_ID) == executionId &&
                result.getString(ShizukuUserService.KEY_STATUS) == ShellExecutionStatus.SUCCESS.name &&
                result.getInt(ShizukuUserService.KEY_EXIT_CODE, -1) == 0 &&
                result.getString(ShizukuUserService.KEY_STDOUT, "").orEmpty().contains(VERIFY_PROBE_TOKEN)
        } catch (_: Exception) {
            false
        }
        _connectionDetails.value = ConnectionDetails(
            permissionGranted = true,
            binderAvailable = true,
            userServiceConnected = true,
            commandVerified = verified
        )
        _connectionState.value = if (verified) ConnectionState.READY else ConnectionState.ERROR
        _errorMessage.value = if (verified) null else "Shizuku command verification failed."
    }

    private fun checkAndBind() {
        if (mockService != null || cleanedUp) return

        val binderAvailable = try {
            Shizuku.pingBinder()
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
        _connectionDetails.update {
            it.copy(
                binderAvailable = binderAvailable,
                userServiceConnected = if (binderAvailable) it.userServiceConnected else false,
                commandVerified = if (binderAvailable) it.commandVerified else false
            )
        }
        if (!binderAvailable) {
            synchronized(lifecycleLock) {
                service = null
                unlinkDeathRecipientLocked()
            }
            releaseServiceBinding()
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running. Start Shizuku to restore privileged operations."
            return
        }

        val hasPermission = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
        _connectionDetails.update {
            it.copy(
                permissionGranted = hasPermission,
                userServiceConnected = if (hasPermission) it.userServiceConnected else false,
                commandVerified = if (hasPermission) it.commandVerified else false
            )
        }
        if (!hasPermission) {
            handlePermissionDenied("Grant FrameScope Shizuku permission to enable privileged operations.")
            return
        }
        bindUserService()
    }

    private fun bindUserService() {
        val ctx = context ?: return
        synchronized(lifecycleLock) {
            if (cleanedUp || bindingRequested || serviceBound) return
            bindingRequested = true
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShizukuUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        try {
            Shizuku.bindUserService(args, userServiceConnection)
            synchronized(lifecycleLock) {
                if (bindingRequested || service != null) serviceBound = true
            }
        } catch (e: Exception) {
            synchronized(lifecycleLock) {
                bindingRequested = false
                serviceBound = false
            }
            markConnectionError("Failed to bind Shizuku UserService: ${e.message}")
            scheduleReconnect()
        }
    }

    private suspend fun verifyService(stub: IShizukuService) {
        val executionId = UUID.randomUUID().toString()
        val verificationProbe = try {
            val result = stub.execute(executionId, "echo $VERIFY_PROBE_TOKEN", PROBE_TIMEOUT_MS)
            result.getString(ShizukuUserService.KEY_EXECUTION_ID) == executionId &&
                result.getString(ShizukuUserService.KEY_STATUS) == ShellExecutionStatus.SUCCESS.name &&
                result.getInt(ShizukuUserService.KEY_EXIT_CODE, -1) == 0 &&
                result.getString(ShizukuUserService.KEY_STDOUT, "").orEmpty().contains(VERIFY_PROBE_TOKEN)
        } catch (e: Exception) {
            if (service === stub) {
                markConnectionError("Shizuku UserService command verification failed: ${e.message}")
            }
            false
        }

        synchronized(lifecycleLock) {
            if (service !== stub || cleanedUp) return
            if (verificationProbe) {
                _connectionDetails.update { it.copy(userServiceConnected = true, commandVerified = true) }
                _connectionState.value = ConnectionState.READY
                _errorMessage.value = null
            } else if (_connectionState.value != ConnectionState.ERROR) {
                _connectionDetails.update { it.copy(commandVerified = false) }
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Shizuku connected, but the shell command verification failed."
            }
        }
    }

    private fun handleUserServiceDeath(deadBinder: IBinder) {
        val isCurrent = synchronized(lifecycleLock) {
            if (linkedBinder !== deadBinder) return
            unlinkDeathRecipientLocked()
            service = null
            bindingRequested = false
            _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = "Shizuku UserService stopped. Reconnecting..."
            true
        }
        if (isCurrent) {
            releaseServiceBinding()
            scheduleReconnect()
        }
    }

    internal fun handleServiceDisconnection(message: String = "Shizuku UserService disconnected") {
        synchronized(lifecycleLock) {
            unlinkDeathRecipientLocked()
            service = null
            bindingRequested = false
            _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
            _connectionState.value = if (_connectionDetails.value.binderAvailable) {
                ConnectionState.CONNECTING
            } else {
                ConnectionState.NOT_RUNNING
            }
            _errorMessage.value = "$message. Reconnecting..."
        }
        releaseServiceBinding()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        synchronized(lifecycleLock) {
            if (cleanedUp || mockService != null || retryJob?.isActive == true) return
            retryJob = connectionScope.launch {
                while (!cleanedUp && service == null) {
                    delay(RECONNECT_DELAY_MS)
                    checkAndBind()
                    if (_connectionState.value == ConnectionState.PERMISSION_DENIED) break
                }
            }
        }
    }

    private fun markConnectionError(message: String) {
        _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
        _connectionState.value = ConnectionState.ERROR
        _errorMessage.value = message
    }

    private fun unlinkDeathRecipientLocked() {
        val binder = linkedBinder
        val recipient = linkedDeathRecipient
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: NoSuchElementException) {
                // Binder already removed the recipient after death.
            }
        }
        linkedBinder = null
        linkedDeathRecipient = null
    }

    private fun releaseServiceBinding() {
        val ctx = context ?: return
        val shouldUnbind = synchronized(lifecycleLock) {
            val active = serviceBound || bindingRequested
            serviceBound = false
            bindingRequested = false
            active
        }
        if (!shouldUnbind) return
        try {
            Shizuku.unbindUserService(createUserServiceArgs(ctx), userServiceConnection, true)
        } catch (e: Exception) {
            if (!cleanedUp) {
                _errorMessage.value = "Could not release the previous Shizuku UserService binding: ${e.message}"
            }
        }
    }

    override suspend fun isAvailable(): Boolean = mutex.withLock {
        if (mockService != null) {
            return@withLock _connectionState.value == ConnectionState.READY && service != null
        }
        checkAndBind()
        if (_connectionState.value != ConnectionState.READY || service == null) {
            return@withLock _connectionState.value == ConnectionState.READY && service != null
        }
        true
    }

    override suspend fun unavailableReason(): String = when (_connectionState.value) {
        ConnectionState.NOT_RUNNING ->
            "Shizuku is not running. Start Shizuku to restore privileged operations."
        ConnectionState.PERMISSION_DENIED ->
            "Shizuku permission is not granted to FrameScope."
        ConnectionState.CONNECTING ->
            "Connecting to Shizuku UserService. Cached data remains available."
        ConnectionState.ERROR ->
            _errorMessage.value ?: "Shizuku UserService connection error."
        ConnectionState.READY -> ""
    }

    suspend fun requestPermission(): Boolean = withContext(Dispatchers.Main) {
        if (mockService != null) return@withContext _connectionState.value == ConnectionState.READY
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _connectionDetails.update { it.copy(binderAvailable = false) }
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running. Start Shizuku before granting access."
            return@withContext false
        }

        val alreadyGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (alreadyGranted) {
            _connectionDetails.update { it.copy(permissionGranted = true, binderAvailable = true) }
            bindUserService()
            return@withContext true
        }

        try {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (e: Exception) {
            markConnectionError("Could not request Shizuku permission: ${e.message}")
            false
        }
    }

    suspend fun refresh(): ConnectionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkAndBind()
            val current = service
            if (current != null && _connectionState.value != ConnectionState.READY) {
                verifyService(current)
            }
            _connectionState.value
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (mockService == null) checkAndBind()
        val activeService = service
            ?: throw ShellUnavailable(unavailableReason().ifBlank { "Shizuku UserService is not available." })
        if (_connectionState.value != ConnectionState.READY) {
            throw ShellUnavailable(unavailableReason().ifBlank { "Shizuku UserService is not verified." })
        }

        val executionId = UUID.randomUUID().toString()
        val effectiveTimeout = timeoutMs.coerceIn(MIN_COMMAND_TIMEOUT_MS, MAX_COMMAND_TIMEOUT_MS)
        val response = try {
            withTimeoutOrNull(effectiveTimeout + REMOTE_TIMEOUT_GRACE_MS) {
                executeRemote(activeService, executionId, command, effectiveTimeout)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ShellUnavailable) {
            throw e
        } catch (e: Exception) {
            if (service === activeService) {
                handleServiceDisconnection("Shizuku command connection failed: ${e.message}")
            }
            throw ShellUnavailable(e.message ?: "Shizuku command connection failed.")
        }
        if (response == null) {
            return@withContext ShellResult(
                code = -1,
                out = "",
                err = "Command timed out after ${effectiveTimeout}ms",
                status = ShellExecutionStatus.TIMEOUT,
                executionId = executionId
            )
        }

        val responseId = response.getString(ShizukuUserService.KEY_EXECUTION_ID)
        if (responseId != executionId) {
            throw ShellUnavailable("Shizuku returned a result for a different execution.")
        }
        val status = response.getString(ShizukuUserService.KEY_STATUS)
            ?.let { value -> ShellExecutionStatus.entries.firstOrNull { it.name == value } }
            ?: throw ShellUnavailable("Shizuku returned an invalid command result.")
        ShellResult(
            code = response.getInt(ShizukuUserService.KEY_EXIT_CODE, -1),
            out = response.getString(ShizukuUserService.KEY_STDOUT, "").orEmpty(),
            err = response.getString(ShizukuUserService.KEY_STDERR, "").orEmpty(),
            status = status,
            executionId = executionId
        )
    }

    private suspend fun executeRemote(
        activeService: IShizukuService,
        executionId: String,
        command: String,
        timeoutMs: Long
    ): android.os.Bundle {
        activeCommands[executionId] = activeService
        return try {
            suspendCancellableCoroutine { continuation ->
                try {
                    commandExecutor.execute {
                        if (!continuation.isActive) return@execute
                        try {
                            val result = activeService.execute(executionId, command, timeoutMs)
                            continuation.resume(result)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }

                continuation.invokeOnCancellation {
                    try {
                        commandExecutor.execute {
                            try {
                                activeService.cancel(executionId)
                            } catch (_: Exception) {
                                // The service may have disconnected during cancellation.
                            }
                        }
                    } catch (_: Exception) {
                        // The runner is being cleaned up.
                    }
                }
            }
        } finally {
            activeCommands.remove(executionId, activeService)
        }
    }

    fun cleanup() {
        synchronized(lifecycleLock) {
            if (cleanedUp) return
            cleanedUp = true
            retryJob?.cancel()
            unlinkDeathRecipientLocked()
            service = null
            _connectionDetails.value = ConnectionDetails()
            _connectionState.value = ConnectionState.NOT_RUNNING
        }
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {
            // Shizuku may already be stopped while application resources are being released.
        }
        releaseServiceBinding()
        activeCommands.forEach { (executionId, activeService) ->
            try {
                commandExecutor.execute {
                    try {
                        activeService.cancel(executionId)
                    } catch (_: Exception) {
                        // The remote service may already be gone during teardown.
                    }
                }
            } catch (_: Exception) {
                // The executor may already be shut down.
            }
        }
        connectionScope.cancel()
        commandExecutor.shutdown()
    }

    private fun createUserServiceArgs(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    companion object {
        const val REQUEST_CODE = 4101
        private const val VERIFY_PROBE_TOKEN = "__shizuku_ready__"
        private const val PROBE_TIMEOUT_MS = 4_000L
        private const val MIN_COMMAND_TIMEOUT_MS = 100L
        private const val MAX_COMMAND_TIMEOUT_MS = 120_000L
        private const val REMOTE_TIMEOUT_GRACE_MS = 3_000L
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}
