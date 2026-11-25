package com.framescope.monitor.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The temperature of the chip.
 *
 * The chart of the frame rate may fall back to the battery of an old run that
 * stored nothing else. The warning about a hot chip and the mean of the
 * processor may not: a battery is not a chip, and a warning that was
 * triggered by the battery is a false warning.
 */
class ChipTemperatureTest {

    private fun source(name: String): String {
        val file = File("src/main/java/com/framescope/monitor/ui/screens/$name")
        assertTrue("${file.absolutePath} was not found", file.exists())
        return file.readText()
    }

    @Test
    fun `the warning about a hot chip uses the chip alone`() {
        val text = source("SceneSessionCards.kt")

        assertTrue(
            "the peak of the chip is taken from the chart of the frame rate",
            text.contains("chipTempSeries(session).mapNotNull { it.finiteOrNull() }.maxOrNull()")
        )
    }

    @Test
    fun `the mean of the processor uses the chip alone`() {
        val text = source("SceneSessionCards.kt")

        assertTrue(
            "the mean of the chip is taken from the chart of the frame rate",
            text.contains("chipTempSeries(session).mapNotNull { it.finiteOrNull() }.avgOrNull()")
        )
    }

    @Test
    fun `the report does the same`() {
        val text = source("SessionShare.kt")

        assertTrue(
            "the report takes the peak of the chip from another way",
            text.contains("temperatureSeries(session).maxOrNull()")
        )
        assertTrue(
            "the report takes the mean of the chip from another way",
            text.contains("temperatureSeries(session).avgOrNull()")
        )
        assertTrue(
            "the series of the report is not the chip alone",
            text.contains("session.chipTemperatures().hotspot()")
        )
    }

    @Test
    fun `the card of the processor shows the chip and not the battery`() {
        val text = source("SessionShare.kt")

        assertTrue(
            "the card of the processor shows another series",
            text.contains("session.chipTemperatureSeries().map { it.coerceIn(0f, TEMP_MAX_C) }")
        )
    }
}
