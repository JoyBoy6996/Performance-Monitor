package com.framescope.monitor.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame time on the pill.
 *
 * The age of the reading is never written on the pill - it kept appearing in
 * the middle of a run.
 */
class PillTextTest {

    @Test
    fun `the frame time is shown`() {
        assertEquals("8.4 ms", pillFrameTimeText(8.4f, true))
    }

    @Test
    fun `an old reading shows its number and nothing else`() {
        assertEquals("22.4 ms", pillFrameTimeText(22.4f, true))
    }

    @Test
    fun `a reading without a number shows nothing`() {
        assertEquals("", pillFrameTimeText(0f, false))
    }
}
