package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun earlier(vararg pairs: Pair<String, Long>): Map<String, TimeStats.Reading> =
        pairs.associate { (layer, frames) ->
            val reading = TimeStats.Reading(layer, frames, 0L, 0L)
            reading.key to reading
        }

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

    @Test
    fun `the layer that drew in this window wins over one that only drew before`() {
        val stale = "SurfaceView[com.pubg.krmobile/com.pubg.krmobile.Splash]#0"
        val live = "SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity]#0"

        val picked = selectCountedLayer(
            listOf(reading(stale, 900L), reading(live, 120L)),
            "com.pubg.krmobile",
            "com.framescope.monitor",
            earlier(stale to 900L, live to 60L)
        )

        assertEquals(live, picked?.layer)
    }

    @Test
    fun `a screen that is standing still still names the app in front`() {
        val layer = "SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity]#0"

        val picked = selectCountedLayer(
            listOf(reading(layer, 900L), reading("BLAST#9(com.other)/Other#0", 100L)),
            "com.pubg.krmobile",
            "com.framescope.monitor",
            earlier(layer to 900L)
        )

        assertEquals(layer, picked?.layer)
    }

    @Test
    fun `a screen that is standing still keeps the layer that was measured last`() {
        val game = "BLAST#123(com.example.engine)/UnityMain#0"
        val other = "SurfaceView[com.other.other]/Other#0"
        val gameReading = reading(game, 120L)
        val otherReading = reading(other, 5000L)

        val picked = selectCountedLayer(
            listOf(otherReading, gameReading),
            "com.pubg.krmobile",
            "com.framescope.monitor",
            mapOf(gameReading.key to gameReading, otherReading.key to otherReading),
            preferredKey = gameReading.key
        )

        assertEquals(game, picked?.layer)
    }


    @Test
    fun `a dump that was cut short is refused`() {
        val cut = "layerName = SurfaceView[com.pubg.krmobile/Main]#0\ntotalFrames = 120\n"
        val whole = cut + "__FS_TIMESTATS_END__\n"

        assertFalse(isCompleteCounterDump(cut))
        assertTrue(isCompleteCounterDump(whole))
    }

    @Test
    fun `a layer of another app is not the app in front`() {
        val foreign = TimeStats.Reading(
            "SurfaceView[com.android.launcher3/.Launcher]#0", 0L, 0L, 0L
        )
        val own = TimeStats.Reading(
            "SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity]#0", 120L, 0L, 0L
        )

        assertFalse(TimeStats.belongsTo(foreign, "com.pubg.krmobile"))
        assertTrue(TimeStats.belongsTo(own, "com.pubg.krmobile"))
    }
}
