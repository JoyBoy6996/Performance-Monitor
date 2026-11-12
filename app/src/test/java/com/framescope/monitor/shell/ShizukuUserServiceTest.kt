package com.framescope.monitor.shell

import android.os.Parcel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShizukuUserServiceTest {

    @Test
    fun collectsStdoutStderrAndNonzeroExitCode() {
        val result = ShizukuUserService().execute(
            "streams",
            "printf 'stdout-data'; printf 'stderr-data' >&2; exit 7",
            2_000
        )

        assertEquals(ShellExecutionStatus.FAILURE.name, result.getString(ShizukuUserService.KEY_STATUS))
        assertEquals(7, result.getInt(ShizukuUserService.KEY_EXIT_CODE))
        assertEquals("stdout-data", result.getString(ShizukuUserService.KEY_STDOUT))
        assertEquals("stderr-data", result.getString(ShizukuUserService.KEY_STDERR))
    }

    @Test
    fun truncatesBothOutputStreamsBeforeReturningBinderResult() {
        val result = ShizukuUserService().execute(
            "large-output",
            "head -c 100000 /dev/zero | tr '\\0' x; head -c 100000 /dev/zero | tr '\\0' y >&2",
            5_000
        )
        val stdout = result.getString(ShizukuUserService.KEY_STDOUT).orEmpty()
        val stderr = result.getString(ShizukuUserService.KEY_STDERR).orEmpty()

        assertTrue(stdout.length < ShizukuUserService.MAX_OUTPUT_BYTES_PER_STREAM + 100)
        assertTrue(stderr.length < ShizukuUserService.MAX_OUTPUT_BYTES_PER_STREAM + 100)
        assertTrue(stdout.contains("[output truncated after"))
        assertTrue(stderr.contains("[output truncated after"))
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(result)
            assertTrue(parcel.dataSize() < 256 * 1024)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun rejectsOversizedCommandBeforeStartingProcess() {
        val result = ShizukuUserService().execute(
            "large-command",
            "x".repeat(ShizukuUserService.MAX_COMMAND_LENGTH + 1),
            1_000
        )

        assertEquals(ShellExecutionStatus.FAILURE.name, result.getString(ShizukuUserService.KEY_STATUS))
        assertTrue(result.getString(ShizukuUserService.KEY_STDERR).orEmpty().contains("maximum length"))
    }

    @Test
    fun timeoutHasDistinctStatusAndCleansExecutionRecord() {
        val service = ShizukuUserService()
        val result = service.execute(
            "timeout",
            "while :; do :; done",
            150
        )

        assertEquals(ShellExecutionStatus.TIMEOUT.name, result.getString(ShizukuUserService.KEY_STATUS))
        assertTrue(result.getString(ShizukuUserService.KEY_STDERR).orEmpty().contains("timed out"))
        assertEquals(0, service.activeExecutionCount)
    }

    @Test
    fun simultaneousCommandsKeepTheirOwnOutputAndExecutionRecords() {
        val service = ShizukuUserService()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<android.os.Bundle> {
                service.execute("first", "sleep 0.2; printf first-out; printf first-err >&2", 3_000)
            }
            val second = executor.submit<android.os.Bundle> {
                service.execute("second", "sleep 0.2; printf second-out; printf second-err >&2", 3_000)
            }

            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals("first-out", firstResult.getString(ShizukuUserService.KEY_STDOUT))
            assertEquals("first-err", firstResult.getString(ShizukuUserService.KEY_STDERR))
            assertEquals("second-out", secondResult.getString(ShizukuUserService.KEY_STDOUT))
            assertEquals("second-err", secondResult.getString(ShizukuUserService.KEY_STDERR))
            assertEquals("first", firstResult.getString(ShizukuUserService.KEY_EXECUTION_ID))
            assertEquals("second", secondResult.getString(ShizukuUserService.KEY_EXECUTION_ID))
            assertEquals(0, service.activeExecutionCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationTerminatesOnlyTheMatchingExecution() {
        val service = ShizukuUserService()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cancelledCommand = executor.submit<android.os.Bundle> {
                service.execute("cancel-this", "while :; do :; done", 10_000)
            }
            val unaffectedCommand = executor.submit<android.os.Bundle> {
                service.execute("leave-this-alone", "sleep 0.4; printf survives", 3_000)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (service.activeExecutionCount < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(2, service.activeExecutionCount)
            assertTrue(service.cancel("cancel-this"))

            val result = cancelledCommand.get(3, TimeUnit.SECONDS)
            assertEquals(
                ShellExecutionStatus.CANCELLED.name,
                result.getString(ShizukuUserService.KEY_STATUS)
            )
            assertFalse(service.cancel("cancel-this"))
            val unaffectedResult = unaffectedCommand.get(3, TimeUnit.SECONDS)
            assertEquals(
                ShellExecutionStatus.SUCCESS.name,
                unaffectedResult.getString(ShizukuUserService.KEY_STATUS)
            )
            assertEquals("survives", unaffectedResult.getString(ShizukuUserService.KEY_STDOUT))
            assertEquals(0, service.activeExecutionCount)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationBeforeRemoteStartIsAppliedAndServiceDestroyCleansProcesses() {
        val service = ShizukuUserService()
        assertTrue(service.cancel("cancel-before-start"))

        val cancelled = service.execute("cancel-before-start", "printf must-not-run", 2_000)
        assertEquals(
            ShellExecutionStatus.CANCELLED.name,
            cancelled.getString(ShizukuUserService.KEY_STATUS)
        )
        assertEquals("", cancelled.getString(ShizukuUserService.KEY_STDOUT))

        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit<android.os.Bundle> {
                service.execute("during-destroy", "while :; do :; done", 10_000)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (service.activeExecutionCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals(1, service.activeExecutionCount)
            service.destroy()
            val result = running.get(3, TimeUnit.SECONDS)
            assertEquals(
                ShellExecutionStatus.CANCELLED.name,
                result.getString(ShizukuUserService.KEY_STATUS)
            )
            assertEquals(0, service.activeExecutionCount)
        } finally {
            executor.shutdownNow()
        }
    }
}
