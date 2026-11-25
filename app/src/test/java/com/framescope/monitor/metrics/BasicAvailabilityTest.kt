package com.framescope.monitor.metrics

import com.framescope.monitor.core.SystemMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The metrics the app's own reading fills.
 *
 * A number that the app read by itself is a real number. The screen must not
 * tell the user to install Shizuku for a reading that is already there.
 */
class BasicAvailabilityTest {

    private fun sample(
        cpuPercent: Float? = null,
        cpuFreqMhz: Int? = null,
        cpuTempC: Float? = null,
        gpuPercent: Float? = null,
        gpuClockMhz: Int? = null,
        perCoreFreqMhz: List<Int?> = emptyList()
    ) = BasicCollector.Sample(
        cpuPercent = cpuPercent,
        perCoreLoadPct = emptyList(),
        clusterLoadPct = emptyList(),
        cpuFreqMhz = cpuFreqMhz,
        cpuClockAvgMhz = cpuFreqMhz,
        perCoreFreqMhz = perCoreFreqMhz,
        clusterFreqMhz = emptyList(),
        cpuTempC = cpuTempC,
        gpuPercent = gpuPercent,
        gpuClockMhz = gpuClockMhz
    )

    @Test
    fun `without a reading nothing is filled`() {
        assertTrue(basicFilledMetrics(null).isEmpty())
        assertTrue(basicFilledMetrics(sample()).isEmpty())
    }

    @Test
    fun `the clock of the processor fills its metric`() {
        val filled = basicFilledMetrics(sample(cpuFreqMhz = 2400))

        assertTrue(SystemMetric.CPU_FREQ_MHZ in filled)
    }

    @Test
    fun `the temperature of the chip fills both of its metrics`() {
        val filled = basicFilledMetrics(sample(cpuTempC = 41f))

        assertTrue(SystemMetric.CPU_TEMP_C in filled)
        assertTrue(SystemMetric.THERMAL_MAX_C in filled)
    }

    @Test
    fun `the graphics unit fills its own metrics`() {
        val filled = basicFilledMetrics(sample(gpuPercent = 33f, gpuClockMhz = 850))

        assertTrue(SystemMetric.GPU_PERCENT in filled)
        assertTrue(SystemMetric.GPU_FREQ_MHZ in filled)
    }

    @Test
    fun `the cores of the processor fill their metric`() {
        val filled = basicFilledMetrics(sample(perCoreFreqMhz = listOf(1800, null, 2400)))

        assertTrue(SystemMetric.CPU_CORES in filled)
    }

    @Test
    fun `a reading that could not be made fills nothing`() {
        val filled = basicFilledMetrics(sample(perCoreFreqMhz = listOf(null, null)))

        assertEquals(emptySet<SystemMetric>(), filled)
    }
}
