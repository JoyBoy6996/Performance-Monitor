package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
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
    fun theRateIsTakenOverTheLastSecondAndNotOverTheWholeDump() {
        // A layer dump holds more frames than a second: at 60 fps it is over two
        // seconds of them. The rate has to describe the moment, so only the
        // intervals of the last second belong to it.
        val deltas = List(120) { 16_666_666L }
        val recent = recentIntervals(deltas)

        assertEquals(48, recent.size)
        assertTrue(recent.sum() <= RECENT_WINDOW_NS)
        assertTrue(recent.sum() > RECENT_WINDOW_NS - 50_000_000L)
        assertEquals(60f, computeFrameStats(recent)!!.fps, 0.5f)
    }

    @Test
    fun aDropAtTheEndIsStillVisibleInTheRate() {
        // The first 120 frames are smooth, then the app drops to 30 fps. Over
        // the whole dump that hardly changes the number; over the last second
        // it is the rate of now.
        val deltas = List(120) { 16_666_666L } + List(30) { 33_333_333L }
        val recent = recentIntervals(deltas)

        assertEquals(30f, computeFrameStats(recent)!!.fps, 1f)
        assertTrue(computeFrameStats(deltas)!!.fps > 45f)
    }

    @Test
    fun aSeriesSlowerThanTheWindowKeepsTheFramesItHas() {
        // Ten frames a second: the last second of the dump holds eight of them,
        // and those eight are the rate of now.
        val deltas = List(10) { 100_000_000L }
        val recent = recentIntervals(deltas)

        assertEquals(8, recent.size)
        assertEquals(10f, computeFrameStats(recent)!!.fps, 0.5f)
    }

    @Test
    fun aSingleFrameIsNeverThrownAway() {
        assertEquals(listOf(100_000_000L), recentIntervals(listOf(100_000_000L)))
    }

    @Test
    fun theFrameTimeAlwaysMatchesTheFrameRate() {
        val deltas = List(40) { 20_000_000L }
        val stats = computeFrameStats(deltas)!!
        assertEquals(stats.fps, 1000f / stats.frameTimeMs, 0.01f)
        assertTrue(stats.fps in 1f..500f)
    }
}
