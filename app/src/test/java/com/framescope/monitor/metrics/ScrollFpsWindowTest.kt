package com.framescope.monitor.metrics

import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen that is scrolled on a 144 Hz panel.
 *
 * The frames of the last 550 ms are counted over their own window. On a
 * 144 Hz panel that window holds about 79 frames, so a fast scroll reaches
 * the rate of the panel - and a game, whose frames come from its own layer,
 * is not touched by any of this.
 */
class ScrollFpsWindowTest {

    // The frames of the system are written from the oldest to the newest.
    private fun frames(count: Int, stepNs: Long, lastNs: Long): List<Long> =
        (0 until count).map { lastNs - (count - 1 - it) * stepNs }

    @Test
    fun `a scroll at 144 Hz reads 144 frames a second`() {
        val step = 1_000_000_000L / 144L
        val now = 5_000_000_000L
        val stamps = frames(79, step, now)

        val fps = gfxWindowFps(stamps, now, now)

        assertTrue("the scroll must reach the rate of the panel, was $fps", fps != null && fps > 143f)
    }

    @Test
    fun `the frames outside the window do not count`() {
        val step = 1_000_000_000L / 144L
        val now = 5_000_000_000L
        // Ten old frames and four recent ones.
        val stamps = frames(4, step, now) + frames(10, step, now - 2_000_000_000L)

        val fps = gfxWindowFps(stamps, now, now)

        assertTrue("only the recent frames may count, was $fps", fps != null && fps > 143f)
    }

    @Test
    fun `a single frame is not a rate`() {
        assertNull(gfxWindowFps(listOf(1_000L), 1_000L, 1_000L))
    }

    @Test
    fun `only the frames that completed without a flag are counted`() {
        val dump = """
            Flags,IntendedVsync,Vsync,FrameCompleted
            0,100,110,120
            1,200,210,220
            0,300,310,320
        """.trimIndent()

        val stamps = Parsers.parseGfxFrameStats(dump)

        assertEquals(listOf(120L, 320L), stamps)
    }
}
