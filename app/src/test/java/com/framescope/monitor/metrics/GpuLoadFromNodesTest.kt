package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The load of the graphics unit, from what the kernel publishes.
 *
 * Some devices publish the busy share directly, and others publish the busy
 * time against the total time. A number that is not one of those is not a
 * reading.
 */
class GpuLoadFromNodesTest {

    @Test
    fun `the busy share of the kernel is the load`() {
        assertEquals(37f, gpuLoadFromNodes(directPercent = "37", busyTicks = null)!!, 0.1f)
    }

    @Test
    fun `a busy share with a fraction is the load`() {
        assertEquals(37.5f, gpuLoadFromNodes(directPercent = "37.5", busyTicks = null)!!, 0.1f)
    }

    @Test
    fun `the busy time against the total time is the load`() {
        val load = gpuLoadFromNodes(directPercent = null, busyTicks = "250 1000")

        assertEquals(25f, load!!, 0.1f)
    }

    @Test
    fun `the busy time may be written with an at sign`() {
        val load = gpuLoadFromNodes(directPercent = null, busyTicks = "250@1000")

        assertEquals(25f, load!!, 0.1f)
    }

    @Test
    fun `a device that publishes neither says nothing`() {
        assertNull(gpuLoadFromNodes(directPercent = null, busyTicks = null))
        assertNull(gpuLoadFromNodes(directPercent = "", busyTicks = ""))
    }

    @Test
    fun `a number that is not a reading is not a reading`() {
        assertNull(gpuLoadFromNodes(directPercent = "abc", busyTicks = null))
        assertNull(gpuLoadFromNodes(directPercent = null, busyTicks = "not a number"))
    }

    @Test
    fun `a total of zero is not a reading`() {
        assertNull(gpuLoadFromNodes(directPercent = null, busyTicks = "100 0"))
    }

    @Test
    fun `the load never leaves the scale of a percent`() {
        val load = gpuLoadFromNodes(directPercent = "180", busyTicks = null)

        assertEquals(100f, load!!, 0.1f)
    }
}
