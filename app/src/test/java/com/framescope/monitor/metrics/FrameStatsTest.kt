package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the frame rate, checked on its own.
 *
 * The dashboard used to show 120 fps next to a 398 ms frame time and 209 fps
 * on a 120 Hz panel, so these cases pin down what the numbers have to be.
 */
class FrameStatsTest {

    @Test
    fun reportsSixtyFramesPerSecondForASixtyHertzStream() {
        val deltas = List(120) { 16_666_667L }
        val stats = computeFrameStats(deltas)
        assertNotNull(stats)
        assertEquals(60f, stats!!.fps, 0.01f)
        assertEquals(16.67f, stats.frameTimeMs, 0.01f)
        assertEquals(0, stats.jank)
    }

    @Test
    fun reportsOneHundredTwentyFramesPerSecondForAOneTwentyHertzStream() {
        val deltas = List(120) { 8_333_333L }
        val stats = computeFrameStats(deltas)
        assertNotNull(stats)
        assertEquals(120f, stats!!.fps, 0.01f)
        assertEquals(8.33f, stats.frameTimeMs, 0.01f)
    }

    @Test
    fun countsStallsAsJank() {
        val deltas = List(58) { 16_666_667L } + listOf(50_000_000L, 33_500_000L)
        val stats = computeFrameStats(deltas)
        assertNotNull(stats)
        assertEquals(2, stats!!.jank)
    }

    @Test
    fun ignoresOrdinaryFrameTimeVariation() {
        val deltas = listOf(16_000_000L, 17_200_000L, 16_400_000L, 16_900_000L, 16_600_000L)
        val stats = computeFrameStats(deltas)
        assertNotNull(stats)
        assertEquals(0, stats!!.jank)
    }

    @Test
    fun refusesIdleGaps() {
        // Two seconds between frames is an idle app, not a frame rate.
        val deltas = List(8) { 2_000_000_000L }
        assertNull(computeFrameStats(deltas))
    }

    @Test
    fun refusesImpossiblyFastFrames() {
        val deltas = List(60) { 200_000L }
        assertNull(computeFrameStats(deltas))
    }

    @Test
    fun refusesAnEmptySeries() {
        assertNull(computeFrameStats(emptyList()))
        assertNull(computeFrameStats(listOf(16_666_667L)))
    }

    @Test
    fun anIdleGapDoesNotTurnAFastGameIntoASlowReading() {
        // A 120 Hz stream with three two-second stalls. 1/mean(delta) reports
        // about 17 fps here; the median is still 8.33 ms, so the reading has to
        // stay at 120. This is the difference between the working version and
        // the 7 / 12 / 24 fps readings seen during gameplay.
        val deltas = List(60) { 8_333_333L } +
            listOf(2_000_000_000L, 2_000_000_000L, 2_000_000_000L) +
            List(60) { 8_333_333L }
        val stats = computeFrameStats(deltas)
        assertNotNull(stats)
        assertEquals(120f, stats!!.fps, 1f)
        assertEquals(8.33f, stats.frameTimeMs, 0.2f)
    }

    @Test
    fun anIdleLayerIsRejectedBeforeItCanBecomeAReading() {
        val idle = (0..12).map { it * 2_000_000_000L }
        assertFalse(isPlausibleSeries(idle))
        val steady = (0..40).map { it * 8_333_333L }
        assertTrue(isPlausibleSeries(steady))
    }

    @Test
    fun theFrameTimeAlwaysMatchesTheFrameRate() {
        val deltas = List(40) { 20_000_000L }
        val stats = computeFrameStats(deltas)!!
        assertEquals(stats.fps, 1000f / stats.frameTimeMs, 0.01f)
        assertTrue(stats.fps in 1f..500f)
    }
}
