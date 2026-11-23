package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The short memory of the live readings.
 *
 * It feeds the graphs of the dashboard, so it must keep the moments of the
 * readings and must not invent any.
 */
class LiveHistoryBufferTest {

    @Test
    fun `the buffer keeps the last readings only`() {
        val buffer = LiveHistoryBuffer(capacity = 3)
        repeat(5) { index ->
            buffer.addFrames(timeMs = index * 400L, fpsValue = 50f + index, frameTimeValue = 16f)
        }

        val history = buffer.snapshot()
        assertEquals(3, history.fps.size)
        assertEquals(52f, history.fps.first().value, 0.01f)
        assertEquals(54f, history.fps.last().value, 0.01f)
    }

    @Test
    fun `every reading keeps the moment it was taken`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(timeMs = 1_000L, fpsValue = 60f, frameTimeValue = 16.6f)
        buffer.addFrames(timeMs = 1_400L, fpsValue = 58f, frameTimeValue = 17.2f)

        val history = buffer.snapshot()
        assertEquals(1_000L, history.fps.first().timeMs)
        assertEquals(1_400L, history.fps.last().timeMs)
        assertEquals(16.6f, history.frameTimeMs.first().value, 0.01f)
    }

    @Test
    fun `the same number twice in a second is one reading`() {
        val buffer = LiveHistoryBuffer()
        buffer.addCpu(timeMs = 0L, percent = 30f)
        buffer.addCpu(timeMs = 400L, percent = 30f)
        buffer.addCpu(timeMs = 900L, percent = 31f)

        val history = buffer.snapshot()
        assertEquals(2, history.cpuPercent.size)
        assertEquals(30f, history.cpuPercent.first().value, 0.01f)
        assertEquals(31f, history.cpuPercent.last().value, 0.01f)
    }

    @Test
    fun `a reading that is not a number is left out`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(timeMs = 0L, fpsValue = Float.NaN, frameTimeValue = 0f)
        buffer.addCpu(timeMs = 0L, percent = Float.NaN)

        val history = buffer.snapshot()
        assertTrue(history.fps.isEmpty())
        assertTrue(history.frameTimeMs.isEmpty())
        assertTrue(history.cpuPercent.isEmpty())
    }

    @Test
    fun `the device readings keep their own moments`() {
        val buffer = LiveHistoryBuffer()
        buffer.addDevice(timeMs = 500L, temperatureC = 41f, ramPercent = 55, gpuPercent = null)
        buffer.addDevice(timeMs = 3_500L, temperatureC = 42.5f, ramPercent = 56, gpuPercent = 12f)

        val history = buffer.snapshot()
        assertEquals(2, history.temperatureC.size)
        assertEquals(500L, history.temperatureC.first().timeMs)
        assertEquals(3_500L, history.temperatureC.last().timeMs)
        assertEquals(2, history.ramPercent.size)
        // The device that does not report the load of the graphics chip says so
        // by saying nothing at all.
        assertEquals(1, history.gpuPercent.size)
        assertEquals(12f, history.gpuPercent.first().value, 0.01f)
    }

    @Test
    fun `the memory can be emptied`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(timeMs = 0L, fpsValue = 60f, frameTimeValue = 16f)
        buffer.addCpu(timeMs = 0L, percent = 20f)
        buffer.clear()

        val history = buffer.snapshot()
        assertTrue(history.fps.isEmpty())
        assertTrue(history.cpuPercent.isEmpty())
    }
}
