package com.framescope.monitor.shell

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
class ShizukuRunnerTest {

    private class MockShizukuService(
        var uid: Int = 2000,
        var defaultExitCode: Int = 0,
        var defaultStdout: String = "",
        var defaultStderr: String = "",
        var delayMs: Long = 0L,
        var shouldThrow: Boolean = false,
        var commandHandler: ((String) -> Bundle)? = null
    ) : IShizukuService.Stub() {

        val executedCommands = mutableListOf<String>()

        override fun destroy() {}
        override fun exit() {}

        override fun getUid(): Int = uid

        override fun execute(command: String, timeoutMs: Long): Bundle {
            executedCommands.add(command)

            if (shouldThrow) {
                throw RuntimeException("Remote IPC error")
            }

            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }

            commandHandler?.let { return it(command) }

            return Bundle().apply {
                putInt(ShizukuUserService.KEY_EXIT_CODE, defaultExitCode)
                putString(ShizukuUserService.KEY_STDOUT, defaultStdout)
                putString(ShizukuUserService.KEY_STDERR, defaultStderr)
            }
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
        assertEquals("16666666\n1000 2000\n", result.out)
        assertEquals("", result.err)
        assertEquals(listOf("dumpsys SurfaceFlinger --latency"), mock.executedCommands)
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

        try {
            // Short timeout should be triggered
            runner.exec("sleep 10", 200)
            fail("Expected timeout")
        } catch (e: ShellUnavailable) {
            assertTrue(e.message?.contains("timed out") == true)
        }
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
        assertEquals("shizuku", status.backendId)
        assertEquals("Shizuku (shell)", status.backendLabel)

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
}
