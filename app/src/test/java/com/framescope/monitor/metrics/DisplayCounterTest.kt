package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two counters live in one dump of SurfaceFlinger.
 *
 * The total at the top is the display's own: the frames the screen presented.
 * The `totalFrames` of a layer is what that layer drew. A home screen that is
 * scrolled is the first one - the display presents, the layer draws now and
 * then - and a game is the second one.
 */
class DisplayCounterTest {

    private val dump = """
        Total frames rendered: 48210
        Janky frames: 12 (0.02%)
        ---PROFILEDATA---
        Flags,IntendedVsync
        ---LAYER: SurfaceView[com.pubg.imobile]---
        totalFrames = 8112
        droppedFrames = 3
    """.trimIndent()

    @Test
    fun `the counter of the display is read from the top of the dump`() {
        assertEquals(48210L, displayPresentedFrames(dump))
    }

    @Test
    fun `the counter of a layer is read from its own block`() {
        assertEquals(8112L, layerPresentedFrames(dump))
    }

    @Test
    fun `a dump without either counter answers nothing`() {
        assertNull(displayPresentedFrames("nothing here"))
        assertNull(layerPresentedFrames("nothing here"))
    }

    @Test
    fun `a dump with only the layer counter still answers the layer`() {
        val layerOnly = "totalFrames = 55"
        assertNull(displayPresentedFrames(layerOnly))
        assertEquals(55L, layerPresentedFrames(layerOnly))
    }
}
