package com.framescope.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scale of the frame-time chart.
 *
 * A fixed 100 ms scale hides every stutter of a fast run: a 10 ms frame sits
 * in the bottom tenth of the chart and the curve looks flat. The scale follows
 * the run instead.
 */
class FrameTimeScaleTest {

    @Test
    fun `a fast run is not squashed into a fixed scale`() {
        val series = listOf(8.3f, 9.1f, 10.4f, 8.0f)

        val ceiling = frameTimeCeiling(series)

        assertTrue("the ceiling must sit above the worst frame", ceiling > 10.4f)
        assertEquals("the ceiling must be a clean number", 20f, ceiling, 0.01f)
    }

    @Test
    fun `the scale is never below twenty milliseconds`() {
        assertEquals(20f, frameTimeCeiling(listOf(4f, 5f, 6f)), 0.01f)
    }

    @Test
    fun `a stuttering run gets a scale that holds it`() {
        val series = listOf(16.6f, 17.2f, 33.4f, 16.8f)

        val ceiling = frameTimeCeiling(series)

        assertTrue("the ceiling must sit above the worst frame", ceiling >= 33.4f)
        assertTrue("the ceiling must be a clean number", ceiling % 5f == 0f)
    }

    @Test
    fun `the recorded maximum is used when the series is empty`() {
        val ceiling = frameTimeCeiling(emptyList(), 48f)

        assertTrue("the ceiling must sit above the recorded maximum", ceiling >= 48f)
    }

    @Test
    fun `a run without frames keeps a usable scale`() {
        val ceiling = frameTimeCeiling(emptyList(), null)

        assertEquals(20f, ceiling, 0.01f)
    }

    @Test
    fun `a value that is not a number is ignored`() {
        val ceiling = frameTimeCeiling(listOf(Float.NaN, 12f, Float.POSITIVE_INFINITY))

        assertTrue("the ceiling must sit above the worst real frame", ceiling >= 12f)
    }
}
