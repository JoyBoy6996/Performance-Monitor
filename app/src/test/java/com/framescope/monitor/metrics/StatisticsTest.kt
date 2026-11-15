package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers a run of frames produces.
 *
 * These are the cases that were wrong before: a 120 Hz panel that was reported as 209
 * fps, a frame rate that hid a drop behind its average, and a jank count that grew every
 * time the same slow frame was read again.
 */
class StatisticsTest {

    private fun budget(fps: Int) = 1_000_000_000L / fps

    /** A steady stream of [count] frames at [fps]. */
    private fun stream(count: Int, fps: Int, base: Long = 1_000_000_000L): List<Long> {
        val step = 1_000_000_000L / fps
        return (0 until count).map { base + it * step }
    }

    /**
     * Feeds a whole run through the counter the way the sampler would.
     *
     * The first poll establishes the baseline and the second one sees the window grow,
     * which is what happens in practice: the source hands out a rolling history and the
     * frames that fall out of it are replaced by new ones.
     */
    private fun run(counter: FrameCounter, history: List<Long>): FrameCounter.Result {
        counter.onSample("layer", history.take(history.size / 2), 1_000_000_000L)
        return counter.onSample("layer", history, 1_100_000_000L)
    }

    @Test
    fun aSixtyHertzStreamIsSixty() {
        val c = FrameCounter(budget(60))
        val result = run(c, stream(120, 60))
        assertNotNull(result.recentFps)
        assertEquals(60f, result.recentFps!!, 0.5f)
    }

    @Test
    fun aNinetyHertzStreamIsNinety() {
        val c = FrameCounter(budget(90))
        val result = run(c, stream(120, 90))
        assertNotNull(result.recentFps)
        assertEquals(90f, result.recentFps!!, 0.5f)
    }

    @Test
    fun aOneHundredTwentyHertzStreamIsOneHundredTwenty() {
        val c = FrameCounter(budget(120))
        val result = run(c, stream(120, 120))
        assertNotNull(result.recentFps)
        assertEquals(120f, result.recentFps!!, 0.5f)
    }

    @Test
    fun aOneHundredFortyFourHertzStreamIsOneHundredFortyFour() {
        val c = FrameCounter(budget(144))
        val result = run(c, stream(144, 144))
        assertNotNull(result.recentFps)
        assertEquals(144f, result.recentFps!!, 0.5f)
    }

    @Test
    fun aSuddenDropIsVisibleInTheWindow() {
        val c = FrameCounter(budget(60))
        // Sixty frames at 60 Hz, then the app stops drawing for a moment.
        val history = stream(60, 60) + stream(20, 20, base = 10_000_000_000L)
        val result = run(c, history)

        assertNotNull(result.recentFps)
        assertTrue("the drop was not visible", result.recentFps!! < 58f)
    }

    @Test
    fun aLongFrameIsCountedAsJankOnce() {
        val c = FrameCounter(budget(60))
        val base = stream(60, 60)
        val slow = base.last() + 100_000_000L
        c.onSample("layer", base, 1_000_000_000L)
        val result = c.onSample("layer", base + slow, 1_100_000_000L)

        assertEquals(1, result.newJank)
        assertEquals(1L, c.totalJankFrames)
    }

    @Test
    fun readingTheSameHistoryAgainDoesNotAddMoreJank() {
        val c = FrameCounter(budget(60))
        val base = stream(60, 60)
        val slow = base.last() + 100_000_000L
        val grown = base + slow

        c.onSample("layer", base, 1_000_000_000L)
        c.onSample("layer", grown, 1_100_000_000L)
        // The same frames come back with the next poll.
        val third = c.onSample("layer", grown, 1_200_000_000L)

        assertEquals(0, third.newJank)
        assertEquals(1L, c.totalJankFrames)
    }

    @Test
    fun aVariableRefreshRateShowsUpAsAChange() {
        // The panel drops from 120 Hz to 60 Hz halfway through.
        val c = FrameCounter(budget(120))
        val first = stream(60, 120)
        val second = stream(60, 60, base = first.last() + 8_333_333L)
        val history = first + second

        c.onSample("layer", first, 1_000_000_000L)
        val result = c.onSample("layer", history, 1_100_000_000L)

        assertNotNull(result.recentFps)
        // The reading follows the panel down instead of staying at the rate it had.
        assertTrue("fps was ${result.recentFps}", result.recentFps!! < 100f)
        assertTrue("fps was ${result.recentFps}", result.recentFps!! > 45f)
    }

    @Test
    fun theFrameBudgetFollowsThePanel() {
        // A frame that takes 12 ms is smooth on a 60 Hz panel and janky on a 120 Hz one.
        val slow60 = FrameCounter(budget(60))
        val slow120 = FrameCounter(budget(120))
        val base = stream(60, 120)

        slow60.onSample("layer", base, 1_000_000_000L)
        slow120.onSample("layer", base, 1_000_000_000L)

        val frame = base.last() + 12_000_000L
        val result60 = slow60.onSample("layer", base + frame, 1_100_000_000L)
        val result120 = slow120.onSample("layer", base + frame, 1_100_000_000L)

        assertEquals(0, result60.newJank)
        assertEquals(1, result120.newJank)
    }

    @Test
    fun thePercentilesAreTakenFromTheFrameTimes() {
        val c = FrameCounter(budget(60))
        val base = stream(200, 60)
        c.onSample("layer", base, 1_000_000_000L)
        // Five frames that took five times as long.
        var history = base
        var t = base.last()
        repeat(5) {
            t += 83_333_333L
            history = history + listOf(t, t + 1, t + 2)
        }
        c.onSample("layer", history, 1_200_000_000L)

        val low1 = c.low1PercentFps()
        val low5 = c.low5PercentFps()
        assertNotNull(low1)
        assertNotNull(low5)
        // The slow frames are 83 ms, so the 1% low cannot be near 60.
        assertTrue("1% low was $low1", low1!! < 20f)
        assertTrue("5% low was $low5", low5!! >= low1)
    }

    @Test
    fun aSteadyRunHasNoLowsBelowItsRate() {
        val c = FrameCounter(budget(60))
        val base = stream(300, 60)
        c.onSample("layer", base.take(150), 1_000_000_000L)
        c.onSample("layer", base, 1_200_000_000L)

        val low1 = c.low1PercentFps()
        assertNotNull(low1)
        assertTrue("1% low was $low1", low1!! > 55f)
    }

    @Test
    fun aRunWithNoFramesHasNoFrameRate() {
        val c = FrameCounter(budget(60))
        val result = c.onSample("layer", emptyList(), 1_000_000_000L)

        assertNull(result.recentFps)
        assertNull(result.averageFps)
        assertNull(c.low1PercentFps())
    }

    @Test
    fun theFrameTimeAndTheFrameRateAgree() {
        val c = FrameCounter(budget(60))
        val result = run(c, stream(120, 60))

        assertNotNull(result.recentFps)
        assertNotNull(result.frameTimeMs)
        assertEquals(result.recentFps!!, 1000f / result.frameTimeMs!!, 0.5f)
    }
}
