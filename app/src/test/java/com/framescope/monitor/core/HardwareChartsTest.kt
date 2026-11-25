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
    fun `a load of zero is not a reading`() {
        // A second without a reading is stored as a zero, and the processor
        // is never at a zero percent.
        val charts = session(cpuSeries = listOf(0f, 0f, 0f)).hardwareCharts()

        assertFalse("a chart of zeros is not a chart", charts.cpuUsage)
    }

    @Test
    fun `a clock of zero is not a reading`() {
        val charts = session(cpuFreqSeries = listOf(0f, 0f, 0f)).hardwareCharts()

        assertFalse("a chart of zeros is not a chart", charts.cpuFrequency)
    }

    @Test
    fun `a second without a reading is a gap in the line`() {
        val values = listOf(20f, 0f, 24f).loadWithGaps()

        assertEquals(3, values.size)
        assertEquals(20f, values[0], 0.01f)
        assertTrue("the gap is not a gap", values[1].isNaN())
        assertEquals(24f, values[2], 0.01f)
    }

    @Test
    fun `the line of a cluster keeps the seconds of the run`() {
        // The rows are the clusters and the values of one of them are the
        // seconds of the run.
        val clusters = listOf(
            listOf(12f, null, 14f),
            listOf(18f, 19f, null)
        )

        val first = clusters.columnWithGaps(0)
        assertEquals("the cluster does not keep its seconds", 3, first.size)
        assertEquals(12f, first[0], 0.01f)
        assertTrue("the gap is not a gap", first[1].isNaN())
        assertEquals(14f, first[2], 0.01f)

        val second = clusters.columnWithGaps(1)
        assertEquals("the cluster does not keep its seconds", 3, second.size)
        assertEquals(18f, second[0], 0.01f)
        assertEquals(19f, second[1], 0.01f)
        assertTrue("the gap is not a gap", second[2].isNaN())
    }

    @Test
    fun `a cluster is a row of the run and not a column of it`() {
        // Two clusters, three seconds. The first cluster has the values of
        // the three seconds, and the second one has its own.
        val clusters = listOf(
            listOf(10f, 11f, 12f),
            listOf(20f, 21f, 22f)
        )

        val first = clusters.columnWithGaps(0)
        assertEquals(3, first.size)
        assertEquals(10f, first[0], 0.01f)
        assertEquals(11f, first[1], 0.01f)
        assertEquals(12f, first[2], 0.01f)

        val second = clusters.columnWithGaps(1)
        assertEquals(3, second.size)
        assertEquals(20f, second[0], 0.01f)
        assertEquals(22f, second[2], 0.01f)
    }

    @Test
    fun `the temperature of the chart falls back to the battery of an old run`() {
        val old = session().copy(batteryTempSeries = listOf(31f, 32f, 33f))

        // The chart of the frame rate may use the battery of an old run...
        assertEquals(listOf(31f, 32f, 33f), old.chartTemperatures())
        // ...but the card of the processor shows the chip, and the battery is
        // not the chip.
        assertFalse(old.hardwareCharts().cpuTemperature)
    }

    @Test
    fun `the warning about a hot chip never uses the battery`() {
        val old = session().copy(batteryTempSeries = listOf(31f, 32f, 33f))

        assertTrue("the battery is not the chip", old.chipTemperatures().isEmpty())
    }
}
