package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dumps that print the counters of the whole device.
 *
 * `dumpsys SurfaceFlinger --timestats -dump` asks the compositor for the
 * counters of every layer of the phone, and it holds its own lock while it
 * prints them. A game feels that: the frames of the match stutter while the
 * dump is made. One dump a second is enough for the counters to grow into a
 * window, so the gate lets one through and holds the rest back.
 */
class HeavyDumpGateTest {

    @Test
    fun `the first dump is allowed`() {
        HeavyDumpGate.reset()
        assertTrue(HeavyDumpGate.take(0L))
    }

    @Test
    fun `a second dump right after the first one is held back`() {
        HeavyDumpGate.reset()
        assertTrue(HeavyDumpGate.take(1_000L))
        assertFalse(HeavyDumpGate.take(1_100L))
    }

    @Test
    fun `the gate opens again after the gap has passed`() {
        HeavyDumpGate.reset()
        assertTrue(HeavyDumpGate.take(1_000L))
        assertTrue(HeavyDumpGate.take(1_000L + HeavyDumpGate.GAP_MS))
    }

    @Test
    fun `two callers cannot both print the device in the same moment`() {
        HeavyDumpGate.reset()
        val first = HeavyDumpGate.take(5_000L)
        val second = HeavyDumpGate.take(5_000L)
        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `the age of a kept reading grows with the monotonic clock`() {
        val measuredAt = 1_000_000_000L
        assertEquals(0L, keptReadingAgeMs(measuredAt, measuredAt))
        assertEquals(1_500L, keptReadingAgeMs(measuredAt + 1_500_000_000L, measuredAt))
    }

    @Test
    fun `a clock that went backwards never makes a reading look fresh`() {
        assertEquals(0L, keptReadingAgeMs(1_000L, 5_000L))
    }
}
