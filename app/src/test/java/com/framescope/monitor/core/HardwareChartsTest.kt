package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which charts of the hardware a run can show.
 *
 * The app and the report use one answer for this, so a run without counters
 * shows the same thing on the screen and on the paper.
 */
class HardwareChartsTest {

    private fun session(
        cpuSeries: List<Float> = emptyList(),
        cpuFreqSeries: List<Float> = emptyList(),
        tempSeries: List<Float> = emptyList()
    ) = SessionRecord(
        id = "run-1",
        appPackage = "com.pubg.imobile",
        appLabel = "BGMI",
        startedAt = 1_700_000_000_000L,
        durationMs = 60_000L,
        avgFps = 58f,
        maxFps = 62f,
        minFps = 44f,
        low1Percent = 44f,
        low5Percent = 50f,
        smoothness = 91f,
        totalFrames = 3_500,
        jankFrames = 40,
        avgFrameTimeMs = 17f,
        maxFrameTimeMs = 33f,
        maxTempC = 42f,
        avgCpu = 21f,
        fpsSeries = listOf(58f, 60f, 57f),
        cpuSeries = cpuSeries,
        cpuFreqSeries = cpuFreqSeries,
        tempSeries = tempSeries
    )

    @Test
    fun `a legacy run without counters has no chart`() {
        val charts = session().hardwareCharts()

        assertFalse(charts.cpuUsage)
        assertFalse(charts.cpuFrequency)
        assertFalse(charts.cpuTemperature)
        assertFalse(charts.any)
        assertEquals(
            listOf("CPU Usage", "CPU Frequency", "CPU Temperature"),
            charts.missing
        )
    }

    @Test
    fun `a run with the counters has all the charts`() {
        val charts = session(
            cpuSeries = listOf(20f, 24f, 18f),
            cpuFreqSeries = listOf(2400f, 1800f, 2400f),
            tempSeries = listOf(40f, 42f, 41f)
        ).hardwareCharts()

        assertTrue(charts.cpuUsage)
        assertTrue(charts.cpuFrequency)
        assertTrue(charts.cpuTemperature)
        assertTrue(charts.missing.isEmpty())
    }

    @Test
    fun `one reading is not a chart`() {
        val charts = session(cpuSeries = listOf(20f)).hardwareCharts()

        assertFalse("a single reading is not a chart", charts.cpuUsage)
    }

    @Test
    fun `the temperature of the chart falls back to the battery of an old run`() {
        val old = session().copy(batteryTempSeries = listOf(31f, 32f, 33f))

        assertTrue(old.hardwareCharts().cpuTemperature)
        assertEquals(listOf(31f, 32f, 33f), old.chartTemperatures())
    }

    @Test
    fun `the warning about a hot chip never uses the battery`() {
        val old = session().copy(batteryTempSeries = listOf(31f, 32f, 33f))

        assertTrue("the battery is not the chip", old.chipTemperatures().isEmpty())
    }
}
