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
import kotlinx.coroutines.Job
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

internal interface ShizukuPlatform {
    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)
    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener)
    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener)
    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener)
    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener)
    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener)
    fun pingBinder(): Boolean
    fun checkSelfPermission(): Int
    fun requestPermission(requestCode: Int)
    fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection)
    fun unbindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean)
}

private object RealShizukuPlatform : ShizukuPlatform {
    override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) =
        Shizuku.addRequestPermissionResultListener(listener)
    override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }
    override fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) =
        Shizuku.addBinderReceivedListener(listener)
    override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }
    override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) =
        Shizuku.addBinderDeadListener(listener)
    override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }
    override fun pingBinder() = Shizuku.pingBinder()
    override fun checkSelfPermission() = Shizuku.checkSelfPermission()
    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)
    override fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection) =
        Shizuku.bindUserService(args, connection)
    override fun unbindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection, remove: Boolean) =
        Shizuku.unbindUserService(args, connection, remove)
}

internal object ShizukuRetryPolicy {
    const val MAX_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 30_000L

    fun delayMillis(attempt: Int): Long =
        (BASE_DELAY_MS * (1L shl (attempt - 1).coerceIn(0, 30)))
            .coerceAtMost(MAX_DELAY_MS)
}

class ShizukuRunner private constructor(
    private val context: Context? = null,
    private var mockService: IShizukuService?,
    private val shizukuPlatform: ShizukuPlatform,
    private val retryDelayMillis: (Int) -> Long
) : ShellRunner {

    constructor(context: Context? = null, mockService: IShizukuService? = null) :
        this(context, mockService, RealShizukuPlatform, ShizukuRetryPolicy::delayMillis)

    internal constructor(
        context: Context?,
        shizukuPlatform: ShizukuPlatform,
        retryDelayMillis: (Int) -> Long = ShizukuRetryPolicy::delayMillis
    ) : this(context, null, shizukuPlatform, retryDelayMillis)

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
    private var retryJob: Job? = null
    private var retryAttempts = 0
    private var nextBindingGeneration = 0L
    private var activeBinding: BindingAttempt? = null
    private var bindingReleaseInProgress = false
    private var reconnectAfterRelease = false
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

    private inner class BindingAttempt(
        val generation: Long,
        val args: Shizuku.UserServiceArgs
    ) {
        lateinit var connection: ServiceConnection
        var bindingAccepted = false
        var bindingCallInProgress = false
        var callbackObserved = false
        var releaseRequested = false
        var removeWhenReleased = false
        var released = false
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        var verificationJob: Job? = null
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, resultCode ->
        handlePermissionResult(requestCode, resultCode)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        checkAndBind(allowExhaustedRetry = true, forceAttempt = true)
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
            if (context != null) checkAndBind(allowExhaustedRetry = true, forceAttempt = true)
        } else {
            handlePermissionDenied(
                "Shizuku permission was denied. Grant FrameScope access in Shizuku to continue."
            )
        }
    }

    internal fun handleShizukuBinderDeath() {
        if (mockService == null && runCatching { shizukuPlatform.pingBinder() }.getOrDefault(false)) return
        val attempt = synchronized(lifecycleLock) {
            val current = activeBinding
            if (current != null) detachAttemptLocked(current)
            service = null
            _connectionDetails.update {
                it.copy(binderAvailable = false, userServiceConnected = false, commandVerified = false)
            }
            _connectionState.value = ConnectionState.NOT_RUNNING
            _errorMessage.value = "Shizuku is not running. Start Shizuku to restore privileged operations."
            retryJob?.cancel()
            retryJob = null
            current
        }
        attempt?.let { releaseAttempt(it, remove = false) }
    }

    private fun handlePermissionDenied(message: String) {
        val attempt = synchronized(lifecycleLock) {
            val current = activeBinding
            if (current != null) detachAttemptLocked(current)
            _connectionDetails.update {
                it.copy(permissionGranted = false, userServiceConnected = false, commandVerified = false)
            }
            service = null
            _connectionState.value = ConnectionState.PERMISSION_DENIED
            _errorMessage.value = message
            retryJob?.cancel()
            retryJob = null
            current
        }
        attempt?.let { releaseAttempt(it, remove = false) }
    }

    init {
        if (mockService == null) {
            try {
                shizukuPlatform.addRequestPermissionResultListener(permissionListener)
                shizukuPlatform.addBinderReceivedListener(binderReceivedListener)
                shizukuPlatform.addBinderDeadListener(binderDeadListener)
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

    internal fun activeBindingGenerationForTest(): Long? =
        synchronized(lifecycleLock) { activeBinding?.generation }

    internal fun activeDeathRecipientForTest(): IBinder.DeathRecipient? =
        synchronized(lifecycleLock) { activeBinding?.deathRecipient }

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

    private fun checkAndBind(
        allowExhaustedRetry: Boolean = false,
        forceAttempt: Boolean = false
    ) {
        if (mockService != null || cleanedUp) return

        val binderAvailable = try {
            shizukuPlatform.pingBinder()
        } catch (_: Exception) {
            false
        }
        if (!binderAvailable) {
            val attempt = synchronized(lifecycleLock) {
                val current = activeBinding
                if (current != null) detachAttemptLocked(current)
                service = null
                _connectionDetails.update {
                    it.copy(binderAvailable = false, userServiceConnected = false, commandVerified = false)
                }
                _connectionState.value = ConnectionState.NOT_RUNNING
                _errorMessage.value = "Shizuku is not running. Start Shizuku to restore privileged operations."
                retryJob?.cancel()
                retryJob = null
                current
            }
            attempt?.let { releaseAttempt(it, remove = false) }
            return
        }

        val hasPermission = try {
            shizukuPlatform.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        _connectionDetails.update {
            it.copy(
                binderAvailable = true,
                permissionGranted = hasPermission,
                userServiceConnected = if (hasPermission) it.userServiceConnected else false,
                commandVerified = if (hasPermission) it.commandVerified else false
            )
        }
        if (!hasPermission) {
            handlePermissionDenied("Grant FrameScope Shizuku permission to enable privileged operations.")
            return
        }
        synchronized(lifecycleLock) {
            if (cleanedUp) return
            if (bindingReleaseInProgress) {
                if (forceAttempt) reconnectAfterRelease = true
                return
            }
            if (activeBinding != null || (_connectionState.value == ConnectionState.READY && service != null)) return
            if (retryJob?.isActive == true) {
                if (!forceAttempt) return
                retryJob?.cancel()
                retryJob = null
            }
            if (retryAttempts >= ShizukuRetryPolicy.MAX_ATTEMPTS && !allowExhaustedRetry) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value = "Shizuku UserService connection failed after ${ShizukuRetryPolicy.MAX_ATTEMPTS} retries."
                return
            }
        }
        bindUserService()
    }

    private fun bindUserService() {
        val ctx = context ?: return
        val args = createUserServiceArgs(ctx)
        val attempt = newBindingAttempt(args) ?: return
        try {
            shizukuPlatform.bindUserService(args, attempt.connection)
            completeBindingCall(attempt, accepted = true)
        } catch (e: Exception) {
            completeBindingCall(attempt, accepted = false)
            failBindingAttempt(attempt, "Failed to bind Shizuku UserService: ${e.message}")
        }
    }

    private fun newBindingAttempt(args: Shizuku.UserServiceArgs): BindingAttempt? =
        synchronized(lifecycleLock) {
            if (cleanedUp || activeBinding != null || bindingReleaseInProgress) return@synchronized null
            val attempt = BindingAttempt(++nextBindingGeneration, args)
            attempt.connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    noteBindingCallback(attempt)
                    handleServiceConnected(attempt, binder)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    noteBindingCallback(attempt)
                    handleBindingDisconnect(attempt, "Shizuku UserService disconnected")
                }

                override fun onBindingDied(name: ComponentName?) {
                    noteBindingCallback(attempt)
                    handleBindingDisconnect(attempt, "Shizuku UserService binding died")
                }

                override fun onNullBinding(name: ComponentName?) {
                    noteBindingCallback(attempt)
                    failBindingAttempt(attempt, "Shizuku UserService returned a null binding")
                }
            }
            attempt.bindingCallInProgress = true
            activeBinding = attempt
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null
            attempt
        }

    private fun noteBindingCallback(attempt: BindingAttempt) {
        synchronized(lifecycleLock) { attempt.callbackObserved = true }
    }

    private fun completeBindingCall(attempt: BindingAttempt, accepted: Boolean) {
        val releaseRequested = synchronized(lifecycleLock) {
            attempt.bindingCallInProgress = false
            attempt.bindingAccepted = accepted
            if (attempt.releaseRequested) attempt.removeWhenReleased else null
        }
        if (releaseRequested != null) {
            releaseAttempt(attempt, remove = releaseRequested)
        }
    }

    private fun handleServiceConnected(attempt: BindingAttempt, binder: IBinder?) {
        if (binder == null) {
            failBindingAttempt(attempt, "Shizuku UserService returned a null binder")
            return
        }
        synchronized(lifecycleLock) {
            if (activeBinding !== attempt || cleanedUp) return
            if (attempt.binder === binder && (attempt.verificationJob?.isActive == true ||
                    _connectionState.value == ConnectionState.READY)
            ) return
        }

        val deathRecipient = IBinder.DeathRecipient {
            handleUserServiceDeath(attempt, binder)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (e: Exception) {
            failBindingAttempt(attempt, "Could not monitor Shizuku UserService binder: ${e.message}")
            return
        }

        val stub = IShizukuService.Stub.asInterface(binder)
        synchronized(lifecycleLock) {
            if (activeBinding !== attempt || cleanedUp) {
                unlinkBinder(binder, deathRecipient)
                return
            }
            if (attempt.binder === binder && (attempt.verificationJob?.isActive == true ||
                    _connectionState.value == ConnectionState.READY)
            ) {
                unlinkBinder(binder, deathRecipient)
                return
            }
            unlinkAttemptDeathRecipientLocked(attempt)
            attempt.binder = binder
            attempt.deathRecipient = deathRecipient
            service = stub
            _connectionDetails.update {
                it.copy(userServiceConnected = true, commandVerified = false)
            }
            _connectionState.value = ConnectionState.CONNECTING
            _errorMessage.value = null
            attempt.verificationJob = connectionScope.launch { verifyService(attempt, stub) }
        }
    }

    private suspend fun verifyService(attempt: BindingAttempt, stub: IShizukuService) {
        val executionId = UUID.randomUUID().toString()
        val verificationProbe = try {
            val result = withTimeoutOrNull(PROBE_TIMEOUT_MS + REMOTE_TIMEOUT_GRACE_MS) {
                executeRemote(stub, executionId, "echo $VERIFY_PROBE_TOKEN", PROBE_TIMEOUT_MS)
            }
            result != null &&
                result.getString(ShizukuUserService.KEY_EXECUTION_ID) == executionId &&
                result.getString(ShizukuUserService.KEY_STATUS) == ShellExecutionStatus.SUCCESS.name &&
                result.getInt(ShizukuUserService.KEY_EXIT_CODE, -1) == 0 &&
                result.getString(ShizukuUserService.KEY_STDOUT, "").orEmpty().contains(VERIFY_PROBE_TOKEN)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failBindingAttempt(
                attempt,
                "Shizuku UserService command verification failed: ${e.message}"
            )
            return
        }

        var failed = false
        synchronized(lifecycleLock) {
            if (activeBinding !== attempt || service !== stub || cleanedUp) return
            if (verificationProbe) {
                _connectionDetails.update { it.copy(userServiceConnected = true, commandVerified = true) }
                _connectionState.value = ConnectionState.READY
                _errorMessage.value = null
                retryAttempts = 0
                retryJob?.cancel()
                retryJob = null
            } else {
                failed = true
            }
        }
        if (failed) {
            failBindingAttempt(attempt, "Shizuku connected, but the shell command verification failed.")
        }
    }

    private fun handleUserServiceDeath(attempt: BindingAttempt, deadBinder: IBinder) {
        synchronized(lifecycleLock) {
            if (activeBinding !== attempt || attempt.binder !== deadBinder) return
        }
        failBindingAttempt(attempt, "Shizuku UserService stopped. Reconnecting...")
    }

    internal fun handleServiceDisconnection(message: String = "Shizuku UserService disconnected") {
        val attempt = synchronized(lifecycleLock) { activeBinding }
        if (attempt != null) {
            handleBindingDisconnect(attempt, message)
        } else {
            synchronized(lifecycleLock) {
                service = null
                _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
                _connectionState.value = if (_connectionDetails.value.binderAvailable) {
                    ConnectionState.CONNECTING
                } else {
                    ConnectionState.NOT_RUNNING
                }
                _errorMessage.value = "$message. Reconnecting..."
            }
            if (mockService == null) scheduleReconnect()
        }
    }

    private fun handleBindingDisconnect(attempt: BindingAttempt, message: String) {
        failBindingAttempt(attempt, "$message. Reconnecting...")
    }

    private fun failBindingAttempt(attempt: BindingAttempt, message: String) {
        val shouldRetry = synchronized(lifecycleLock) {
            if (activeBinding !== attempt) return
            detachAttemptLocked(attempt)
            service = null
            _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
            val recoverable = _connectionDetails.value.binderAvailable &&
                _connectionDetails.value.permissionGranted
            _connectionState.value = if (recoverable) ConnectionState.CONNECTING else ConnectionState.ERROR
            _errorMessage.value = message
            recoverable
        }
        releaseAttempt(attempt, remove = false)
        if (shouldRetry) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        synchronized(lifecycleLock) {
            if (cleanedUp || mockService != null || retryJob?.isActive == true) return
            if (!_connectionDetails.value.binderAvailable || !_connectionDetails.value.permissionGranted) return
            if (retryAttempts >= ShizukuRetryPolicy.MAX_ATTEMPTS) {
                _connectionState.value = ConnectionState.ERROR
                _errorMessage.value =
                    "Shizuku UserService connection failed after ${ShizukuRetryPolicy.MAX_ATTEMPTS} retries."
                return
            }
            retryAttempts += 1
            val delayMs = retryDelayMillis(retryAttempts)
            retryJob = connectionScope.launch {
                delay(delayMs)
                synchronized(lifecycleLock) {
                    retryJob = null
                    if (cleanedUp || activeBinding != null) return@launch
                }
                checkAndBind(allowExhaustedRetry = true, forceAttempt = true)
            }
        }
    }

    private fun markConnectionError(message: String) {
        _connectionDetails.update { it.copy(userServiceConnected = false, commandVerified = false) }
        _connectionState.value = ConnectionState.ERROR
        _errorMessage.value = message
    }

    private fun detachAttemptLocked(attempt: BindingAttempt): Boolean {
        if (activeBinding !== attempt) return false
        activeBinding = null
        bindingReleaseInProgress = true
        attempt.verificationJob?.cancel()
        unlinkAttemptDeathRecipientLocked(attempt)
        return true
    }

    private fun unlinkAttemptDeathRecipientLocked(attempt: BindingAttempt) {
        val binder = attempt.binder
        val recipient = attempt.deathRecipient
        if (binder != null && recipient != null) {
            unlinkBinder(binder, recipient)
        }
        attempt.binder = null
        attempt.deathRecipient = null
    }

    private fun unlinkBinder(binder: IBinder, recipient: IBinder.DeathRecipient) {
        try {
            binder.unlinkToDeath(recipient, 0)
        } catch (_: NoSuchElementException) {
            // Binder already removed the recipient after death.
        }
    }

    private fun releaseAttempt(attempt: BindingAttempt, remove: Boolean) {
        val shouldUnbind = synchronized(lifecycleLock) {
            if (attempt.released) return
            attempt.releaseRequested = true
            attempt.removeWhenReleased = attempt.removeWhenReleased || remove
            if (attempt.bindingCallInProgress) return
            attempt.released = true
            attempt.bindingAccepted || attempt.callbackObserved
        }
        try {
            if (shouldUnbind) {
                shizukuPlatform.unbindUserService(
                    attempt.args,
                    attempt.connection,
                    attempt.removeWhenReleased
                )
            }
        } catch (e: Exception) {
            if (!cleanedUp) {
                _errorMessage.value = "Could not release the previous Shizuku UserService binding: ${e.message}"
            }
        } finally {
            val reconnect = synchronized(lifecycleLock) {
                bindingReleaseInProgress = false
                val shouldReconnect = reconnectAfterRelease && !cleanedUp
                reconnectAfterRelease = false
                shouldReconnect
            }
            if (reconnect) {
                connectionScope.launch {
                    checkAndBind(allowExhaustedRetry = true, forceAttempt = true)
                }
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
        if (!runCatching { shizukuPlatform.pingBinder() }.getOrDefault(false)) {
            checkAndBind()
            return@withContext false
        }

        val alreadyGranted = runCatching {
            shizukuPlatform.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (alreadyGranted) {
            _connectionDetails.update { it.copy(permissionGranted = true, binderAvailable = true) }
            checkAndBind(allowExhaustedRetry = true, forceAttempt = true)
            return@withContext true
        }

        try {
            shizukuPlatform.requestPermission(REQUEST_CODE)
            true
        } catch (e: Exception) {
            markConnectionError("Could not request Shizuku permission: ${e.message}")
            false
        }
    }

    suspend fun refresh(forceReconnect: Boolean = false): ConnectionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkAndBind(allowExhaustedRetry = forceReconnect, forceAttempt = forceReconnect)
            _connectionState.value
        }
    }

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (mockService == null) checkAndBind()
        val (activeServiceOrNull, activeGeneration) = synchronized(lifecycleLock) {
            service to activeBinding?.generation
        }
        val activeService = activeServiceOrNull
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
            handleCommandConnectionFailure(
                activeService,
                activeGeneration,
                "Shizuku command connection failed: ${e.message}"
            )
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

    private fun handleCommandConnectionFailure(
        activeService: IShizukuService,
        generation: Long?,
        message: String
    ) {
        val attempt = synchronized(lifecycleLock) {
            activeBinding?.takeIf {
                it.generation == generation && service === activeService
            }
        }
        if (attempt != null) {
            handleBindingDisconnect(attempt, message)
        } else if (mockService != null && service === activeService) {
            handleServiceDisconnection(message)
        }
    }

    fun cleanup() {
        val attempt = synchronized(lifecycleLock) {
            if (cleanedUp) return
            cleanedUp = true
            retryJob?.cancel()
            retryJob = null
            val current = activeBinding
            if (current != null) detachAttemptLocked(current)
            service = null
            _connectionDetails.value = ConnectionDetails()
            _connectionState.value = ConnectionState.NOT_RUNNING
            current
        }
        try {
            shizukuPlatform.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {
            // Shizuku may already be stopped while application resources are being released.
        }
        try {
            shizukuPlatform.removeBinderReceivedListener(binderReceivedListener)
        } catch (_: Exception) {
            // Shizuku may already be stopped while application resources are being released.
        }
        try {
            shizukuPlatform.removeBinderDeadListener(binderDeadListener)
        } catch (_: Exception) {
            // Shizuku may already be stopped while application resources are being released.
        }
        attempt?.let { releaseAttempt(it, remove = true) }
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
    }
}
