package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic lines of a run.
 *
 * A line that only carries placeholders is not a reading: it says the shell
 * did not answer. Printing it next to the real numbers makes a run look
 * measured when it was not, so it is dropped.
 */
class ShellLogsTest {

    private val real = "shell_uid=2000 | platform=SUN | thermal_zone=cpu0 | clusters=CPU 0~1"
    private val placeholder = "shell_uid=-- | platform=-- | thermal_zone=-- | clusters=--"

    @Test
    fun `a line of placeholders is not a reading`() {
        assertTrue(ShellLogs.isPlaceholder(placeholder))
        assertTrue(ShellLogs.isPlaceholder("--"))
        assertTrue(ShellLogs.isPlaceholder(""))
        assertFalse(ShellLogs.isPlaceholder(real))
    }

    @Test
    fun `the identity is only the real one`() {
        assertEquals(real, ShellLogs.identity("$real\ncaptured_from=com.pubg.imobile"))
        assertNull(ShellLogs.identity(placeholder))
        assertNull(ShellLogs.identity(""))
    }

    @Test
    fun `the lines under the identity leave it out`() {
        val logs = "$real\ncaptured_from=com.pubg.imobile\n--- frame counters ---"
        assertEquals(
            listOf("captured_from=com.pubg.imobile", "--- frame counters ---"),
            ShellLogs.detailLines(logs)
        )
    }

    @Test
    fun `a placeholder is never part of the log`() {
        val logs = "$placeholder\ncaptured_from=com.pubg.imobile"
        assertEquals(listOf("captured_from=com.pubg.imobile"), ShellLogs.realLines(logs))
        assertNull(ShellLogs.identity(logs))
    }

    @Test
    fun `the app and the frame counters are kept when the shell answered`() {
        val logs = "captured_from=com.pubg.imobile at=1234\n" +
            "--- telemetry ---\nSHELLUID=2000\nPLAT=SUN"
        assertEquals(4, ShellLogs.realLines(logs).size)
    }
}
