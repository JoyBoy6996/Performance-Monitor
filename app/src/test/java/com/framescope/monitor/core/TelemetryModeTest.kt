package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way a run was measured.
 *
 * Every new run carries this line, so the Shell Logs never says that nothing
 * was captured: either the privileged shell answered, or the app read what it
 * could by itself.
 */
class TelemetryModeTest {

    @Test
    fun `the shell says that it answered`() {
        assertEquals(
            "Mode: Shizuku (uid=2000)",
            telemetryModeLine(shizukuActive = true)
        )
    }

    @Test
    fun `the app says what it read by itself`() {
        val line = telemetryModeLine(
            shizukuActive = false,
            platform = "SUN",
            clusters = listOf("CPU 0~1", "CPU 2~4", "CPU 7")
        )

        assertEquals(
            "Mode: Basic | Platform: SUN | Clusters: CPU 0~1, CPU 2~4, CPU 7",
            line
        )
    }

    @Test
    fun `a run without a platform still says how it was measured`() {
        val line = telemetryModeLine(shizukuActive = false, platform = null)

        assertTrue("the line does not say that it was the basic mode", line.startsWith("Mode: Basic"))
        assertTrue("the platform is not marked as missing", line.contains("Platform: --"))
        assertTrue("the clusters are not marked as missing", line.contains("Clusters: --"))
    }

    @Test
    fun `the clusters of a run are named by their cores`() {
        val line = telemetryModeLine(
            shizukuActive = false,
            platform = "SUN",
            clusters = listOf("CPU 0~1", "CPU 2~4")
        )

        assertTrue(line.contains("CPU 0~1, CPU 2~4"))
    }

    /**
     * The first line of the log is the one that is printed on its own.
     */
    @Test
    fun `the first line of the log is the way the run was measured`() {
        val logs = "Mode: Basic | Platform: SUN | Clusters: CPU 0~1\n" +
            "captured_from=com.pubg.imobile at=42"

        assertEquals(
            "Mode: Basic | Platform: SUN | Clusters: CPU 0~1",
            ShellLogs.head(logs)
        )
        assertEquals(listOf("captured_from=com.pubg.imobile at=42"), ShellLogs.body(logs))
    }

    @Test
    fun `an old run without any line has no head`() {
        assertNull(ShellLogs.head(""))
        assertNull(ShellLogs.head("shell_uid=-- | platform=-- | thermal_zone=-- | clusters=--"))
    }
}
