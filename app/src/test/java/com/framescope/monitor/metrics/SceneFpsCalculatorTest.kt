package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scene's rule is simple: count real present timestamps over a short window,
 * deduplicate them, clamp to the panel, and report zero as soon as they go stale.
 * The overlay must follow that rule instead of holding an old number forever.
 */
class SceneFpsCalculatorTest {

    private fun presents(count: Int, periodNs: Long): List<Long> =
        (0 until count).map { (it + 1) * periodNs }

    @Test
    fun countsFramesFromTheirPresentSpan() {
        val calc = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)
        val result = calc.calculate(presents(31, 8_333_333L), 144f, 1_000L)

        assertTrue(result.isSceneActive)
        assertEquals(120, result.fps)
    }

    @Test
    fun aDuplicateTimestampIsCountedOnce() {
        val calc = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)
        val frames = presents(31, 8_333_333L)
        calc.calculate(frames, 144f, 1_000L)
        val result = calc.calculate(frames, 144f, 1_100L)

        assertEquals(120, result.fps)
    }

    @Test
    fun noFreshFramesDropsTheReading() {
        val calc = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)
        calc.calculate(presents(31, 8_333_333L), 144f, 1_000L)

        val stale = calc.calculate(emptyList(), 144f, 2_000L)

        assertFalse(stale.isSceneActive)
        assertEquals(0, stale.fps)
    }

    @Test
    fun aReadingIsNeverClampedAboveThePanel() {
        val calc = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)
        // A jittery 60 Hz dump must not turn into a 144 fps claim.
        val frames = presents(61, 16_666_666L) + listOf(1_020_000_000L)
        val result = calc.calculate(frames, 60f, 1_000L)

        assertTrue(result.fps <= 61)
    }
}
