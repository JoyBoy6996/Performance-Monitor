package com.framescope.monitor.metrics

import com.framescope.monitor.core.LivePoint
import com.framescope.monitor.ui.components.asChartPoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The live readings while the screen draws them.
 *
 * The readings arrive on a background thread and the dashboard reads them
 * while it draws. The buffer was not safe against that: the copy could walk
 * an array that was being resized, and it saw a slot that was empty - a
 * reading that was never there, and a crash on the way to the screen.
 */
class LiveHistoryBufferConcurrencyTest {

    @Test
    fun `a snapshot taken while the buffer is written to never holds a missing reading`() {
        val buffer = LiveHistoryBuffer(capacity = 64)
        val running = AtomicBoolean(true)
        val failure = AtomicBoolean(false)

        // The writer fills the buffer as fast as it can.
        val writer = Thread {
            var time = 0L
            while (running.get()) {
                buffer.addFrames(time, 58f, 17f)
                buffer.addCpu(time, 21f)
                buffer.addDevice(time, 42f, 50, 30f)
                time += 16L
            }
        }
        writer.isDaemon = true
        writer.start()

        // The reader takes the snapshots the dashboard takes.
        repeat(2_000) {
            try {
                val history = buffer.snapshot()
                history.fps.forEach { point ->
                    if (point == null) failure.set(true)
                    point?.value?.let { value ->
                        if (!value.isFinite()) failure.set(true)
                    }
                }
            } catch (e: Exception) {
                failure.set(true)
            }
        }

        running.set(false)
        writer.join(2_000)

        assertTrue("a snapshot held a reading that was not there", !failure.get())
    }

    @Test
    fun `the readings of a snapshot are a copy that cannot change`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(1_000L, 58f, 17f)

        val first = buffer.snapshot()
        buffer.addFrames(2_000L, 61f, 16f)

        assertEquals("the copy changed under the reader", 1, first.fps.size)
        assertEquals(2, buffer.snapshot().fps.size)
    }

    @Test
    fun `the same reading in the same second is not kept twice`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(1_000L, 58f, 17f)
        buffer.addFrames(1_200L, 58f, 17f)

        assertEquals(1, buffer.snapshot().fps.size)
    }

    @Test
    fun `a reading that is not a number is not kept`() {
        val buffer = LiveHistoryBuffer()
        buffer.addFrames(1_000L, Float.NaN, Float.POSITIVE_INFINITY)

        assertTrue(buffer.snapshot().fps.isEmpty())
        assertTrue(buffer.snapshot().frameTimeMs.isEmpty())
    }

    @Test
    fun `a point without a value is left out of the graph`() {
        val points: List<LivePoint?> = listOf(
            LivePoint(0L, 10f),
            null,
            LivePoint(1_000L, 20f)
        )

        val chart = points.asChartPoints()

        assertEquals("the missing reading was drawn", 2, chart.size)
        assertEquals(0L, chart.first().timeMs)
    }
}
