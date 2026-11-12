package com.framescope.monitor.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import rikka.shizuku.Shizuku

/**
 * Unit tests for ShizukuRunner and Shizuku UserService execution layer.
 *
 * Verifies:
 * - Permission and connection lifecycle states
 * - Actual execution verification (never marking ready on permission alone)
 * - Command execution layer (stdout, stderr, exit code, timeout)
 * - Error handling, binder death, and disconnection
 * - ShellHub integration
 */
@RunWith(RobolectricTestRunner::class)
class ShizukuRunnerTest {

    private class FakeShizukuPlatform : ShizukuPlatform {
        var binderAvailable = true
        var permissionGranted = true
        var onBind: ((ServiceConnection, Int) -> Unit)? = null
        var bindFailure: ((Int) -> Exception?)? = null
        val connections = CopyOnWriteArrayList<ServiceConnection>()
        val unbinds = CopyOnWriteArrayList<Pair<ServiceConnection, Boolean>>()
        var binderReceivedListener: Shizuku.OnBinderReceivedListener? = null
        var binderDeadListener: Shizuku.OnBinderDeadListener? = null
        var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

        override fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            permissionListener = listener
        }

        override fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
            if (permissionListener === listener) permissionListener = null
        }

        override fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
            binderReceivedListener = listener
        }

        override fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
            if (binderReceivedListener === listener) binderReceivedListener = null
        }

        override fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            binderDeadListener = listener
        }

        override fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
            if (binderDeadListener === listener) binderDeadListener = null
        }

        override fun pingBinder(): Boolean = binderAvailable

        override fun checkSelfPermission(): Int =
            if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED

        override fun requestPermission(requestCode: Int) = Unit

        override fun bindUserService(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
            val index = connections.size
            connections.add(connection)
            bindFailure?.invoke(index)?.let { throw it }
            onBind?.invoke(connection, index)
        }

        override fun unbindUserService(
            args: Shizuku.UserServiceArgs,
            connection: ServiceConnection,
            remove: Boolean
        ) {
            unbinds.add(connection to remove)
        }
    }

    private class MockShizukuService(
        private val mockUid: Int = 2000,
        var defaultExitCode: Int = 0,
        var defaultStdout: String = "",
        var defaultStderr: String = "",
        var delayMs: Long = 0L,
        var probeDelayMs: Long = 0L,
        var shouldThrow: Boolean = false,
        var probeSucceeds: Boolean = true,
        var commandHandler: ((String) -> Bundle)? = null
    ) : IShizukuService.Stub() {

        val executedCommands = CopyOnWriteArrayList<String>()
        val cancelledExecutions = CopyOnWriteArrayList<String>()

        override fun destroy() {}
        override fun exit() {}

        override fun getUid(): Int = mockUid

        override fun execute(executionId: String, command: String, timeoutMs: Long): Bundle {
            executedCommands.add(command)

            if (command == "echo __shizuku_ready__") {
                if (probeDelayMs > 0) Thread.sleep(probeDelayMs)
                return Bundle().apply {
                    putString(ShizukuUserService.KEY_EXECUTION_ID, executionId)
                    putString(
                        ShizukuUserService.KEY_STATUS,
                        if (probeSucceeds) ShellExecutionStatus.SUCCESS.name else ShellExecutionStatus.FAILURE.name
                    )
                    putInt(ShizukuUserService.KEY_EXIT_CODE, if (probeSucceeds) 0 else 1)
                    putString(
                        ShizukuUserService.KEY_STDOUT,
                        if (probeSucceeds) "__shizuku_ready__\n" else ""
                    )
                    putString(ShizukuUserService.KEY_STDERR, "")
                }
            }

            if (shouldThrow) {
                throw RuntimeException("Remote IPC error")
            }

            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }

            val result = commandHandler?.invoke(command) ?: Bundle().apply {
                putInt(ShizukuUserService.KEY_EXIT_CODE, defaultExitCode)
                putString(ShizukuUserService.KEY_STDOUT, defaultStdout)
                putString(ShizukuUserService.KEY_STDERR, defaultStderr)
            }
            return result.apply {
                putString(ShizukuUserService.KEY_EXECUTION_ID, executionId)
                putString(
                    ShizukuUserService.KEY_STATUS,
                    result.getString(ShizukuUserService.KEY_STATUS)
                        ?: if (result.getInt(ShizukuUserService.KEY_EXIT_CODE, 0) == 0) {
                            ShellExecutionStatus.SUCCESS.name
                        } else {
                            ShellExecutionStatus.FAILURE.name
                        }
                )
            }
        }

        override fun cancel(executionId: String): Boolean {
            cancelledExecutions.add(executionId)
            return true
        }
    }

    private fun createLifecycleRunner(
        platform: FakeShizukuPlatform,
        onBind: ((ServiceConnection, Int) -> Unit)? = null
    ): ShizukuRunner {
        platform.onBind = onBind
        return ShizukuRunner(
            context = ApplicationProvider.getApplicationContext<Context>(),
            shizukuPlatform = platform,
            retryDelayMillis = { 0L }
        )
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun connect(connection: ServiceConnection, service: IShizukuService) {
        connection.onServiceConnected(
            ComponentName("com.framescope.monitor", ShizukuUserService::class.java.name),
            service.asBinder()
        )
    }

    @Test
    fun initialStateWithoutServiceIsNotRunning() = runBlocking {
        val runner = ShizukuRunner(context = null)
        assertEquals(ShizukuRunner.ConnectionState.NOT_RUNNING, runner.connectionState.value)
        assertFalse(runner.unavailableReason().isBlank())
    }

    @Test
    fun readyWhenMockServiceConnected() = runBlocking {
        val mock = MockShizukuService(defaultStdout = "uid=2000(shell)")
        val runner = ShizukuRunner(mockService = mock)

        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        assertTrue(runner.isAvailable())
        assertEquals("", runner.unavailableReason())
    }

    @Test
    fun doesNotReportReadyWhenUserServiceProbeFails() = runBlocking {
        val runner = ShizukuRunner(mockService = MockShizukuService(probeSucceeds = false))

        assertEquals(ShizukuRunner.ConnectionState.ERROR, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.userServiceConnected)
        assertFalse(runner.connectionDetails.value.commandVerified)
        assertFalse(runner.isAvailable())
    }

    @Test
    fun executesCommandSuccessfullyThroughUserService() = runBlocking {
        val mock = MockShizukuService(
            defaultExitCode = 0,
            defaultStdout = "16666666\n1000 2000\n",
            defaultStderr = ""
        )
        val runner = ShizukuRunner(mockService = mock)

        val result = runner.exec("dumpsys SurfaceFlinger --latency", 5_000)
        assertEquals(0, result.code)
        assertTrue(result.ok)
        assertEquals(ShellExecutionStatus.SUCCESS, result.status)
        assertEquals("16666666\n1000 2000\n", result.out)
        assertEquals("", result.err)
        assertEquals("echo __shizuku_ready__", mock.executedCommands.first())
        assertEquals("dumpsys SurfaceFlinger --latency", mock.executedCommands.last())

        val nextResult = runner.exec("echo second", 5_000)
        assertNotEquals(result.executionId, nextResult.executionId)
    }

    @Test
    fun capturesNonZeroExitCodeAndStderr() = runBlocking {
        val mock = MockShizukuService(
            defaultExitCode = 127,
            defaultStdout = "",
            defaultStderr = "sh: unknown_cmd: not found\n"
        )
        val runner = ShizukuRunner(mockService = mock)

        val result = runner.exec("unknown_cmd", 5_000)
        assertEquals(127, result.code)
        assertFalse(result.ok)
        assertEquals("", result.out)
        assertTrue(result.err.contains("not found"))
    }

    @Test
    fun throwsShellUnavailableWhenServiceNotRunning() = runBlocking {
        val runner = ShizukuRunner(context = null)
        try {
            runner.exec("echo test", 2_000)
            fail("Expected ShellUnavailable")
        } catch (e: ShellUnavailable) {
            assertTrue(e.message?.contains("Shizuku") == true)
        }
    }

    @Test
    fun throwsShellUnavailableOnRemoteException() = runBlocking {
        val mock = MockShizukuService(shouldThrow = true)
        val runner = ShizukuRunner(mockService = mock)

        try {
            runner.exec("echo test", 2_000)
            fail("Expected ShellUnavailable on remote failure")
        } catch (e: ShellUnavailable) {
            assertTrue(e.message?.contains("Remote IPC error") == true)
        }
    }

    @Test
    fun handlesCommandTimeoutProperly() = runBlocking {
        val mock = MockShizukuService(delayMs = 4_000)
        val runner = ShizukuRunner(mockService = mock)

        val result = runner.exec("sleep 10", 200)
        assertEquals(ShellExecutionStatus.TIMEOUT, result.status)
        assertTrue(result.err.contains("timed out"))
        assertNotNull(result.executionId)
    }

    @Test
    fun shellHubDelegatesExclusivelyToShizuku() = runBlocking {
        val mock = MockShizukuService(
            commandHandler = { cmd ->
                when (cmd) {
                    "id -u" -> Bundle().apply {
                        putInt(ShizukuUserService.KEY_EXIT_CODE, 0)
                        putString(ShizukuUserService.KEY_STDOUT, "2000\n")
                    }
                    else -> Bundle().apply {
                        putInt(ShizukuUserService.KEY_EXIT_CODE, 0)
                        putString(ShizukuUserService.KEY_STDOUT, "ok\n")
                    }
                }
            }
        )
        val runner = ShizukuRunner(mockService = mock)
        val hub = ShellHub(runner)

        val status = hub.refresh()
        assertEquals(AccessState.READY, status.state)
        assertTrue(status.permissionGranted)
        assertTrue(status.binderAvailable)
        assertTrue(status.userServiceConnected)
        assertTrue(status.commandVerified)

        val result = hub.exec("id -u", 5_000)
        assertEquals("2000\n", result.out)
        assertNull(hub.lastError.value)
    }

    @Test
    fun shellHubRecordsLastErrorOnFailure() = runBlocking {
        val mock = MockShizukuService(shouldThrow = true)
        val runner = ShizukuRunner(mockService = mock)
        val hub = ShellHub(runner)

        val res = hub.execOrNull("broken_cmd")
        assertNull(res)
        assertNotNull(hub.lastError.value)
    }

    @Test
    fun verifyStateTransitionsOnDisconnect() = runBlocking {
        val mock = MockShizukuService(defaultStdout = "ready")
        val runner = ShizukuRunner(mockService = mock)
        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)

        // Clear mock service simulating disconnect
        runner.setMockServiceForTest(null)
        assertEquals(ShizukuRunner.ConnectionState.NOT_RUNNING, runner.connectionState.value)
        assertFalse(runner.isAvailable())
    }

    @Test
    fun permissionDenialAndRevocationNeverImplyReadiness() = runBlocking {
        val runner = ShizukuRunner(context = null)

        runner.handlePermissionResult(ShizukuRunner.REQUEST_CODE, android.content.pm.PackageManager.PERMISSION_DENIED)
        assertEquals(ShizukuRunner.ConnectionState.PERMISSION_DENIED, runner.connectionState.value)
        assertFalse(runner.connectionDetails.value.permissionGranted)

        runner.handlePermissionResult(ShizukuRunner.REQUEST_CODE, android.content.pm.PackageManager.PERMISSION_GRANTED)
        assertEquals(ShizukuRunner.ConnectionState.CONNECTING, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.permissionGranted)
        assertFalse(runner.connectionDetails.value.userServiceConnected)
        assertFalse(runner.connectionDetails.value.commandVerified)

        runner.handlePermissionResult(ShizukuRunner.REQUEST_CODE, android.content.pm.PackageManager.PERMISSION_DENIED)
        assertEquals(ShizukuRunner.ConnectionState.PERMISSION_DENIED, runner.connectionState.value)
        assertFalse(runner.connectionDetails.value.commandVerified)
    }

    @Test
    fun binderDeathAndServiceDisconnectionRecoverOnlyAfterNewVerification() = runBlocking {
        val runner = ShizukuRunner(mockService = MockShizukuService())

        runner.handleServiceDisconnection()
        assertEquals(ShizukuRunner.ConnectionState.CONNECTING, runner.connectionState.value)
        assertFalse(runner.connectionDetails.value.userServiceConnected)
        assertFalse(runner.connectionDetails.value.commandVerified)

        runner.setMockServiceForTest(MockShizukuService())
        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.commandVerified)

        runner.handleShizukuBinderDeath()
        assertEquals(ShizukuRunner.ConnectionState.NOT_RUNNING, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.permissionGranted)
        assertFalse(runner.connectionDetails.value.binderAvailable)
        assertFalse(runner.connectionDetails.value.commandVerified)

        runner.setMockServiceForTest(MockShizukuService())
        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.commandVerified)
    }

    @Test
    fun repeatedRefreshWhileConnectingKeepsOneBindingAttempt() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform)
        val generation = runner.activeBindingGenerationForTest()

        repeat(10) { runner.refresh() }

        assertEquals(1, platform.connections.size)
        assertEquals(generation, runner.activeBindingGenerationForTest())
        assertEquals(ShizukuRunner.ConnectionState.CONNECTING, runner.connectionState.value)
        runner.cleanup()
    }

    @Test
    fun duplicateConnectionCallbackAndRefreshDoNotRepeatVerificationOrDowngradeReady() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform)
        val mock = MockShizukuService(probeDelayMs = 150)
        val connection = platform.connections.single()

        connect(connection, mock)
        connect(connection, mock)
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        repeat(10) { runner.refresh() }

        assertEquals(1, mock.executedCommands.count { it == "echo __shizuku_ready__" })
        assertEquals(1, platform.connections.size)
        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        runner.cleanup()
    }

    @Test
    fun staleDisconnectCannotClearNewConnection() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform)
        val oldConnection = platform.connections.single()
        connect(oldConnection, MockShizukuService())
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        val oldGeneration = runner.activeBindingGenerationForTest()

        oldConnection.onServiceDisconnected(null)
        awaitCondition { platform.connections.size == 2 }
        val newConnection = platform.connections[1]
        connect(newConnection, MockShizukuService())
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        assertNotEquals(oldGeneration, runner.activeBindingGenerationForTest())
        oldConnection.onServiceDisconnected(null)

        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.commandVerified)
        runner.cleanup()
    }

    @Test
    fun oldBinderDeathCannotClearReconnectedService() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform)
        val oldConnection = platform.connections.single()
        connect(oldConnection, MockShizukuService())
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        val oldDeathRecipient = runner.activeDeathRecipientForTest()
        assertNotNull(oldDeathRecipient)

        oldConnection.onServiceDisconnected(null)
        awaitCondition { platform.connections.size == 2 }
        connect(platform.connections[1], MockShizukuService())
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        oldDeathRecipient!!.binderDied()

        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        assertTrue(runner.connectionDetails.value.commandVerified)
        runner.cleanup()
    }

    @Test
    fun verificationFailureRecoversOnBoundedRetry() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform) { connection, index ->
            connect(connection, MockShizukuService(probeSucceeds = index != 0))
        }

        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }

        assertEquals(2, platform.connections.size)
        assertTrue(runner.connectionDetails.value.commandVerified)
        runner.cleanup()
    }

    @Test
    fun bindingFailureAndNullBindingRetryWithoutRemovingService() = runBlocking {
        val platform = FakeShizukuPlatform()
        platform.bindFailure = { index -> if (index == 0) IllegalStateException("temporary") else null }
        val runner = createLifecycleRunner(platform) { connection, index ->
            if (index == 1) connection.onNullBinding(null)
            if (index == 2) connect(connection, MockShizukuService())
        }

        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }

        assertEquals(3, platform.connections.size)
        assertTrue(platform.unbinds.all { !it.second })
        runner.cleanup()
        assertTrue(platform.unbinds.last().second)
    }

    @Test
    fun retryExhaustionReportsErrorAndBinderReturnAllowsRecovery() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform) { connection, _ ->
            connection.onNullBinding(null)
        }

        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.ERROR }
        assertEquals(ShizukuRetryPolicy.MAX_ATTEMPTS + 1, platform.connections.size)

        platform.onBind = { connection, _ -> connect(connection, MockShizukuService()) }
        platform.binderReceivedListener?.onBinderReceived()
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        assertTrue(runner.connectionDetails.value.commandVerified)
        runner.cleanup()
    }

    @Test
    fun retryPolicyIsExponentialAndCapped() {
        assertEquals(1_000L, ShizukuRetryPolicy.delayMillis(1))
        assertEquals(2_000L, ShizukuRetryPolicy.delayMillis(2))
        assertEquals(16_000L, ShizukuRetryPolicy.delayMillis(5))
        assertEquals(30_000L, ShizukuRetryPolicy.delayMillis(6))
    }

    @Test
    fun unavailableShizukuAndDeniedPermissionDoNotRetry() = runBlocking {
        val unavailable = FakeShizukuPlatform().apply { binderAvailable = false }
        val unavailableRunner = createLifecycleRunner(unavailable)
        delay(100)
        assertTrue(unavailable.connections.isEmpty())
        assertEquals(ShizukuRunner.ConnectionState.NOT_RUNNING, unavailableRunner.connectionState.value)
        unavailable.binderAvailable = true
        unavailable.binderReceivedListener?.onBinderReceived()
        awaitCondition { unavailable.connections.size == 1 }
        unavailableRunner.cleanup()

        val denied = FakeShizukuPlatform().apply { permissionGranted = false }
        val deniedRunner = createLifecycleRunner(denied)
        delay(100)
        assertTrue(denied.connections.isEmpty())
        assertEquals(ShizukuRunner.ConnectionState.PERMISSION_DENIED, deniedRunner.connectionState.value)
        deniedRunner.cleanup()
    }

    @Test
    fun shizukuStopAndRestartRebindsAndCleanupRemovesOnlyActiveBinding() = runBlocking {
        val platform = FakeShizukuPlatform()
        val runner = createLifecycleRunner(platform) { connection, _ ->
            connect(connection, MockShizukuService())
        }
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        val oldConnection = platform.connections.single()

        platform.binderAvailable = false
        platform.binderDeadListener?.onBinderDead()
        assertEquals(ShizukuRunner.ConnectionState.NOT_RUNNING, runner.connectionState.value)
        platform.binderAvailable = true
        platform.binderReceivedListener?.onBinderReceived()
        awaitCondition { runner.connectionState.value == ShizukuRunner.ConnectionState.READY }
        oldConnection.onServiceDisconnected(null)

        assertEquals(ShizukuRunner.ConnectionState.READY, runner.connectionState.value)
        runner.cleanup()
        assertEquals(2, platform.unbinds.size)
        assertFalse(platform.unbinds[0].second)
        assertTrue(platform.unbinds[1].second)
        assertSame(platform.connections[1], platform.unbinds[1].first)
        assertNull(platform.permissionListener)
        assertNull(platform.binderReceivedListener)
        assertNull(platform.binderDeadListener)
    }

    @Test
    fun cancellationRequestsRemoteExecutionCancellation() = runBlocking {
        val mock = MockShizukuService(delayMs = 5_000)
        val runner = ShizukuRunner(mockService = mock)
        val command = async { runner.exec("sleep 20", 20_000) }
        delay(100)
        command.cancelAndJoin()
        repeat(20) {
            if (mock.cancelledExecutions.isNotEmpty()) return@repeat
            delay(10)
        }
        assertEquals(1, mock.cancelledExecutions.size)
    }
}
