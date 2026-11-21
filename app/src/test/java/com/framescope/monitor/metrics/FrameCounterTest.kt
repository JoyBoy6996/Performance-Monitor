package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame is a frame, however often the source repeats itself.
 *
 * SurfaceFlinger returns a rolling history. The same timestamps come back in every poll
 * until they fall out of the window, and reading them as new frames is how the old
 * counter ended up roughly 1.6 times too high.
 */
class FrameCounterTest {

    private val budget60 = 16_666_666L

    private fun counter(budget: Long = budget60) = FrameCounter(budget)

    /** A steady 60 Hz stream of [count] frames starting at [base]. */
    private fun stream(base: Long, count: Int, stepNs: Long = 16_666_666L): List<Long> =
        (0 until count).map { base + it * stepNs }

    @Test
    fun theFirstSampleOnlyEstablishesTheBaseline() {
        val c = counter()
        val result = c.onSample("layer", stream(0L, 10), 1_000_000_000L)
        assertEquals(0, result.newFrames)
        assertEquals(0L, c.totalUniqueFrames)
        assertNull(result.recentFps)
    }

    @Test
    fun theSameHistoryTwiceCountsNothingTwice() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        val second = c.onSample("layer", history, 1_400_000_000L)
        assertEquals(0, second.newFrames)
        assertEquals(0L, c.totalUniqueFrames)
    }

    @Test
    fun oneNewFrameIsCountedOnce() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        val grown = history + (history.last() + 16_666_666L)
        val second = c.onSample("layer", grown, 1_020_000_000L)
        assertEquals(1, second.newFrames)
        assertEquals(1L, c.totalUniqueFrames)
    }

    @Test
    fun severalNewFramesAreAllCounted() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        val grown = history + stream(history.last() + 16_666_666L, 5)
        val second = c.onSample("layer", grown, 1_100_000_000L)
        assertEquals(5, second.newFrames)
        assertEquals(5L, c.totalUniqueFrames)
    }

    @Test
    fun aSlidingWindowThatKeepsOldFramesStillOnlyCountsTheNewOnes() {
        val c = counter()
        var history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        // The window slides: the oldest frames fall out, new ones arrive.
        history = history.drop(2) + stream(history.last() + 16_666_666L, 2)
        val second = c.onSample("layer", history, 1_100_000_000L)

        assertEquals(2, second.newFrames)
        assertEquals(2L, c.totalUniqueFrames)
    }

    @Test
    fun aSourceThatRestartsItsTimelineIsReBaselined() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)
        // One more frame, half a second later.
        c.onSample("layer", history + (history.last() + 16_666_666L), 1_020_000_000L)

        // The source restarted: the timestamps are smaller than the cursor.
        val restarted = stream(0L, 20)
        val result = c.onSample("layer", restarted, 1_040_000_000L)

        assertTrue(result.sourceReset)
        assertEquals(0, result.newFrames)
        assertEquals(0L, c.totalUniqueFrames)
    }

    @Test
    fun aDifferentLayerStartsTheCountAgain() {
        val c = counter()
        c.onSample("layer-a", stream(0L, 20), 1_000_000_000L)
        c.onSample("layer-a", stream(333_333_333L, 20), 1_020_000_000L)
        assertTrue(c.totalUniqueFrames > 0L)

        val result = c.onSample("layer-b", stream(0L, 20), 1_040_000_000L)
        assertTrue(result.targetChanged)
        assertEquals("layer-b", c.layerName)
        assertEquals(0L, c.totalUniqueFrames)
    }

    @Test
    fun anIntervalThatMissesTheBudgetIsJank() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        // One frame that took three times as long as it should have.
        val slow = history.last() + 50_000_000L
        val result = c.onSample("layer", history + slow, 1_020_000_000L)

        assertEquals(1, result.newJank)
        assertEquals(1L, c.totalJankFrames)
    }

    @Test
    fun anIdleGapIsNotASlowFrame() {
        val c = counter()
        val history = stream(0L, 20)
        c.onSample("layer", history, 1_000_000_000L)

        // The app was paused for two seconds. That is not a jank frame.
        val afterIdle = history.last() + 2_000_000_000L
        val result = c.onSample("layer", history + afterIdle, 3_000_000_000L)

        assertEquals(0, result.newJank)
        assertEquals(0L, c.totalJankFrames)
    }

    @Test
    fun theFrameBudgetFollowsTheDisplayMode() {
        val at120 = counter(budget = 8_333_333L)
        val history = stream(0L, 20)
        at120.onSample("layer", history, 1_000_000_000L)
        val slow = history.last() + 12_000_000L
        val result = at120.onSample("layer", history + slow, 1_020_000_000L)

        // 12 ms is over budget on a 120 Hz panel and under budget on a 60 Hz one.
        assertEquals(1, result.newJank)
    }

    @Test
    fun theLiveFrameRateFollowsTheRealStream() {
        val c = counter()
        c.onSample("layer", stream(0L, 20), 1_000_000_000L)
        val grown = stream(0L, 44)
        val result = c.onSample("layer", grown, 1_400_000_000L)
        // 24 new frames over the window that they span.

        assertNotNull(result.recentFps)
        assertEquals(60f, result.recentFps!!, 0.5f)
    }

    @Test
    fun aOneHundredTwentyHertzStreamIsMeasuredAsSuch() {
        val c = counter(budget = 8_333_333L)
        c.onSample("layer", stream(0L, 20), 1_000_000_000L)
        val result = c.onSample("layer", stream(0L, 44, 8_333_333L), 1_400_000_000L)

        assertNotNull(result.recentFps)
        assertEquals(120f, result.recentFps!!, 0.5f)
    }

    @Test
    fun theAverageCountsEveryUniqueFrame() {
        val c = counter()
        c.onSample("layer", stream(0L, 20), 1_000_000_000L)
        c.onSample("layer", stream(0L, 44), 1_500_000_000L)

        // 24 new frames in half a second is about 48 frames per second.
        val average = c.averageFps(1_500_000_000L)
        assertNotNull(average)
        assertEquals(48f, average!!, 1.0f)
    }

    @Test
    fun theLowsComeFromFrameTimesAndNotFromAveragedFrameRates() {
        val c = counter()
        // 100 frames at 60 Hz, then one that took 100 ms.
        val history = stream(0L, 100)
        c.onSample("layer", history, 1_000_000_000L)
        val slow = history.last() + 100_000_000L
        c.onSample("layer", history + slow, 1_050_000_000L)

        val low1 = c.low1PercentFps()
        val low5 = c.low5PercentFps()
        assertNotNull(low1)
        assertNotNull(low5)
        // The worst frame was 100 ms, so the 1% low cannot be anywhere near 60.
        assertTrue("1% low was $low1", low1!! < 20f)
        // The 5% low sees more frames, so it cannot be worse than the 1% low.
        assertTrue(low5!! >= low1)
    }

    @Test
    fun aSteadyStreamHasNoLowsBelowItsRate() {
        val c = counter()
        val history = stream(0L, 100)
        c.onSample("layer", history, 1_000_000_000L)
        c.onSample("layer", stream(0L, 160), 1_600_000_000L)
        // The second poll added 60 frames of 16.67 ms, so no frame was slow.

        val low1 = c.low1PercentFps()
        assertNotNull(low1)
        assertTrue("1% low was $low1", low1!! > 55f)
    }

    @Test
    fun theSampleAgeCountsFromTheNewestAcceptedFrame() {
        val c = counter()
        c.onSample("layer", stream(0L, 20), 1_000_000_000L)
        c.onSample("layer", stream(0L, 24), 1_100_000_000L)

        val age = c.sampleAgeMs(1_150_000_000L)
        assertEquals(50L, age)
    }

    @Test
    fun anEmptyPollKeepsTheStateAndReportsNoNewFrames() {
        val c = counter()
        c.onSample("layer", stream(0L, 20), 1_000_000_000L)
        val result = c.onSample("layer", emptyList(), 1_020_000_000L)

        assertEquals(0, result.newFrames)
        assertEquals(0L, c.totalUniqueFrames)
        assertFalse(result.sourceReset)
    }
}
