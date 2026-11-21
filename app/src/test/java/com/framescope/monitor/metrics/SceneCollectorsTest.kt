package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extra real collectors must never invent a load or a clock. */
class SceneCollectorsTest {

    private val firstCoreDump = """
        cpu0 100 0 50 10 0 0 0 0 0 0
        cpu1 100 0 50 10 0 0 0 0 0 0
        CPUMAX=0:2400000
        CPUMAX=1:1800000
    """.trimIndent()

    private val coreFreqDump = "CPUFREQ=0:1200000\nCPUFREQ=1:900000"

    @Test
    fun `first poll has no load but keeps the real clocks`() {
        val collectors = SceneCollectors()
        val reading = collectors.parse(
            coreDump = firstCoreDump,
            coreFreqDump = coreFreqDump,
            ddrDump = "DDRFREQ=3200000",
            threadDump = "",
            clockDump = "HZ=100",
            nowWallMs = 1_000L
        )
        assertTrue(reading.cpuCoreLoadPct.all { it == null })
        assertEquals(listOf(1200, 900), reading.cpuCoreFreqMHz)
        assertEquals(3200, reading.ddrFreqMHz)
    }

    @Test
    fun `per core load is the delta of real counters`() {
        val collectors = SceneCollectors()
        collectors.parse(firstCoreDump, coreFreqDump, "", "", "HZ=100", 1_000L)
        val second = """
            cpu0 150 0 50 10 0 0 0 0 0 0
            cpu1 150 0 50 35 0 0 0 0 0 0
        """.trimIndent()
        val reading = collectors.parse(second, coreFreqDump, "", "", "HZ=100", 3_000L)
        assertEquals(100f, reading.cpuCoreLoadPct[0]!!, 0.01f)
        assertEquals(66.67f, reading.cpuCoreLoadPct[1]!!, 0.01f)
    }

    @Test
    fun `thread load uses the kernel jiffy clock`() {
        val collectors = SceneCollectors()
        collectors.parse(
            "", "", "",
            "THREAD=101|RenderThread|1000\nTHREAD=102|MainThread|100",
            "HZ=100", 0L
        )
        val reading = collectors.parse(
            "", "", "",
            "THREAD=101|RenderThread|1300\nTHREAD=102|MainThread|110",
            "HZ=100", 2_000L
        )
        assertEquals("RenderThread", reading.threadLoads.first().name)
        assertEquals(100f, reading.threadLoads.first().percent!!, 0.01f)
        assertNull(reading.ddrFreqMHz)
    }
}
