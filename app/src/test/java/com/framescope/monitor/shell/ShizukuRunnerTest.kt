package com.framescope.monitor.shell

import android.os.Bundle
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.runner.RunWith
import org.junit.Test
import org.robolectric.RobolectricTestRunner

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

    private class MockShizukuService(
        private val mockUid: Int = 2000,
        var defaultExitCode: Int = 0,
        var defaultStdout: String = "",
        var defaultStderr: String = "",
        var delayMs: Long = 0L,
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
