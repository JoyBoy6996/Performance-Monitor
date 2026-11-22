package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A game's FPS must come from the game's own frames.
 *
 * The overlay is a separate window. Moving it makes SurfaceFlinger present that
 * window at the panel rate, but those presents are not frames the game produced.
 */
class GameFpsIsolationTest {

    private val own = "com.qstats.fpsmonitor"
    private val game = "com.pubg.imobile"

    @Test
    fun draggingTheOverlayKeepsTheGameAsTheMeasurementTarget() {
        assertEquals(
            game,
            FpsSampler.targetForMeasurement(game, own, game, true)
        )
        assertEquals(
            game,
            FpsSampler.targetForMeasurement(own, own, game, true)
        )
        assertEquals(
            game,
            FpsSampler.targetForMeasurement(null, own, game, true)
        )
    }

    @Test
    fun theOverlayIsNeverMeasuredWhenNoGameIsKnown() {
        assertNull(FpsSampler.targetForMeasurement(own, own, null, true))
        assertNull(FpsSampler.targetForMeasurement(null, own, null, true))
    }

    @Test
    fun anOverlayCounterCannotWinEvenWhenItGrewFaster() {
        val overlay = TimeStats.Reading(
            layer = "SurfaceView[$own/QstatsOverlay]#0",
            frames = 1_000L,
            jankyFrames = 0L,
            packageName = own
        )
        val gameLayer = TimeStats.Reading(
            layer = "SurfaceView[$game/com.epicgames.ue4.GameActivity]#0",
            frames = 120L,
            jankyFrames = 2L,
            packageName = game
        )
        val earlier = mapOf(
            overlay.key to overlay.copy(frames = 800L),
            gameLayer.key to gameLayer.copy(frames = 60L)
        )

        val selected = selectCountedLayer(
            listOf(overlay, gameLayer),
            game,
            own,
            earlier
        )

        assertEquals(gameLayer.layer, selected?.layer)
    }

    @Test
    fun anOrdinaryAppIsNotMistakenForAGame() {
        assertFalse(
            FpsSampler.isGameLayerName("com.android.chrome/ChromeChildSurface#749670")
        )
        assertFalse(FpsSampler.isGameLayerName("home_task_overlay_container"))
        assertTrue(
            FpsSampler.isGameLayerName(
                "bae4750 SurfaceView[$game/com.epicgames.ue4.GameActivity](BLAST)#0"
            )
        )
    }
}
