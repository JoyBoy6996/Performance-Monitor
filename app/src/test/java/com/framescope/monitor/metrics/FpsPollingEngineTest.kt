package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The layer that is picked out of SurfaceFlinger's frame counters.
 *
 * A game names its surface after its engine and not after its package, so the
 * layer that drew the most is the app in front - but only when it is clearly
 * ahead of the others. The overlay of this app and the system bars are never
 * the app in front.
 */
class FpsPollingEngineTest {

    private fun reading(layer: String, frames: Long) =
        TimeStats.Reading(layer, frames, 0L, 0L)

    private fun select(readings: List<TimeStats.Reading>, pkg: String) =
        selectCountedLayer(readings, pkg, "com.framescope.monitor")

    @Test
    fun `the layer of the app in front wins`() {
        val picked = select(
            listOf(
                reading("SurfaceView[com.android.systemui]/StatusBar#0", 40L),
                reading("SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity]#0", 120L)
            ),
            "com.pubg.krmobile"
        )

        assertEquals("SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity]#0", picked?.layer)
    }

    @Test
    fun `a game surface that does not name the package is taken by its frames`() {
        val picked = select(
            listOf(
                reading("SurfaceView[com.android.systemui]/StatusBar#0", 2L),
                reading("BLAST#123(com.example.engine)/UnityMain#0", 120L)
            ),
            "com.pubg.krmobile"
        )

        assertEquals("BLAST#123(com.example.engine)/UnityMain#0", picked?.layer)
    }

    @Test
    fun `two layers that drew the same say nothing about the app in front`() {
        val picked = select(
            listOf(
                reading("BLAST#123(com.example.engine)/UnityMain#0", 120L),
                reading("BLAST#124(com.example.other)/OtherMain#0", 110L)
            ),
            "com.pubg.krmobile"
        )

        assertNull(picked)
    }

    @Test
    fun `the overlay of this app and the system bars are never the app in front`() {
        val picked = select(
            listOf(
                reading("SurfaceView[com.framescope.monitor]/FrameScopeOverlay#0", 500L),
                reading("SurfaceView[com.android.systemui]/NavigationBar#0", 300L),
                reading("BLAST#123(com.example.engine)/UnityMain#0", 120L)
            ),
            "com.pubg.krmobile"
        )

        assertEquals("BLAST#123(com.example.engine)/UnityMain#0", picked?.layer)
    }
}
