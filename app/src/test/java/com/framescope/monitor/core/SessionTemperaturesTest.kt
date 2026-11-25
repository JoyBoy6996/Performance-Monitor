package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The temperature of a run, second by second.
 *
 * The list has one value for every second, so the line of the temperature
 * sits above the line of the frame rate at the same second. A second without
 * a reading is a gap and not a drop to zero.
 */
class SessionTemperaturesTest {

    private fun session(
        cpuTemp: List<Float> = emptyList(),
        socTemp: List<Float> = emptyList(),
        legacyTemp: List<Float> = emptyList(),
        batteryTemp: List<Float> = emptyList()
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
        cpuSeries = listOf(20f, 22f, 21f),
        tempSeries = legacyTemp,
        cpuTempSeries = cpuTemp,
        socTempSeries = socTemp,
        batteryTempSeries = batteryTemp
    )

    @Test
    fun `the sensor of the chip comes first`() {
        val run = session(cpuTemp = listOf(40f, 41f, 42f), socTemp = listOf(41f, 42f, 43f))

        // The sensor of the SoC is the reading of the chip; the one of the
        // processor is the second answer.
        assertEquals(listOf(41f, 42f, 43f), run.chartTemperatures())
    }

    @Test
    fun `the second series is used when the first one has nothing`() {
        val run = session(cpuTemp = listOf(0f, 0f, 0f), socTemp = listOf(41f, 42f, 43f))

        assertEquals(listOf(41f, 42f, 43f), run.chartTemperatures())
    }

    @Test
    fun `the battery is the last way to a temperature`() {
        val run = session(cpuTemp = listOf(0f, 0f, 0f), batteryTemp = listOf(31f, 32f, 33f))

        assertEquals(listOf(31f, 32f, 33f), run.chartTemperatures())
    }

    @Test
    fun `a second without a reading is a gap and not a zero`() {
        val run = session(cpuTemp = listOf(40f, 0f, 42f))

        val values = run.chartTemperatures()
        assertEquals(3, values.size)
        assertEquals(40f, values[0], 0.01f)
        assertTrue("the gap is not a reading", values[1].isNaN())
        assertEquals(42f, values[2], 0.01f)
    }

    @Test
    fun `the card of the chip never shows the battery`() {
        val run = session(cpuTemp = listOf(0f, 0f, 0f), batteryTemp = listOf(31f, 32f, 33f))

        val values = run.chipTemperatureSeries()
        assertEquals(3, values.size)
        assertTrue("the battery became the temperature of the chip", values.all { it.isNaN() })
    }

    @Test
    fun `the warning about a hot chip never uses the battery`() {
        val run = session(cpuTemp = listOf(0f, 0f, 0f), batteryTemp = listOf(31f, 32f, 33f))

        assertTrue(run.chipTemperatures().isEmpty())
    }
}
