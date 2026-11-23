package com.framescope.monitor.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which shell answers a command.
 *
 * Android 16 hides the procfs and sysfs nodes of the CPU, the GPU and the
 * thermal zones from an ordinary app, so the hardware rows can only be filled
 * by a shell that runs as the `shell` user.
 *
 * The Shizuku UserService is such a shell - and so is the local ADB bridge,
 * because `adbd` runs its commands as the same `shell` user. The app's own
 * process is not, and must never answer a hardware read: a zero it cannot
 * read is worse than no answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShellHubTransportTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the hardware rows come from the local adb bridge when shizuku is absent`() = runBlocking {
        val daemon = MockAdbd(stdout = "cpu  1 2 3\n")
        daemon.start()
        try {
            // No Shizuku at all: the runner only knows that the service is
            // not running.
            val hub = ShellHub(ShizukuRunner(context = null), context(), daemon.port)

            val refreshed = hub.refresh(forceReconnect = true)
            assertTrue(
                "the bridge has to come up before it may answer",
                refreshed.adbReady
            )

            val result = hub.execTelemetry("cat /proc/stat", 5_000)
            assertTrue(
                "the bridge has to answer the hardware read",
                result.out.contains("cpu")
            )
            assertEquals(
                "the answer has to come from the bridge",
                "Local ADB bridge",
                hub.telemetryShell.value
            )
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `the hardware read is refused when no privileged shell is connected`() = runBlocking {
        val hub = ShellHub(ShizukuRunner(context = null), context(), 1)

        val failure = runCatching { hub.execTelemetry("cat /proc/stat", 2_000) }.exceptionOrNull()

        assertTrue(
            "there is no shell that may read the hardware",
            failure is ShellUnavailable
        )
        assertEquals("None", hub.telemetryShell.value)
    }

    @Test
    fun `a refused one time permission is reported instead of swallowed`() = runBlocking {
        val daemon = MockAdbd(stdout = "")
        daemon.start()
        try {
            val hub = ShellHub(ShizukuRunner(context = null), context(), daemon.port)
            hub.refresh(forceReconnect = true)

            assertNull("nothing was tried yet", hub.dumpGrantFailureReason())

            val granted = hub.tryGrantDumpPermission()
            assertTrue("the permission is not held by the test app", !granted)
            assertNotNull(
                "the refusal has to be reported, not swallowed",
                hub.dumpGrantFailureReason()
            )
        } finally {
            daemon.stop()
        }
    }

    @Test
    fun `the one time permission is asked for again after it was refused`() = runBlocking {
        val daemon = MockAdbd(stdout = "")
        daemon.start()
        try {
            val hub = ShellHub(ShizukuRunner(context = null), context(), daemon.port)
            hub.refresh(forceReconnect = true)

            // The first attempt is refused.
            hub.tryGrantDumpPermission()
            val afterFirst = hub.dumpGrantFailureReason()
            assertNotNull("the refusal has to be remembered", afterFirst)

            // The app may ask again later - a failed attempt must not be
            // final for the whole life of the app.
            hub.dumpGrantRetryDelayMs = 0L
            hub.tryGrantDumpPermission()
            assertTrue(
                "the status has to carry the reason the permission was refused",
                hub.status.value.detail.contains("refused", ignoreCase = true)
            )
        } finally {
            daemon.stop()
        }
    }
}
