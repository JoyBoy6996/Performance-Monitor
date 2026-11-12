package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counters SurfaceFlinger keeps per layer.
 *
 * They are the fallback for a game that renders without a frame timeline, so
 * they have to be read from the right layer and refused when the dump does not
 * say which layer it belongs to.
 */
class TimeStatsTest {

    private val dump = """
        SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity](BLAST)#0
           Total frames rendered: 1234
           Janky frames: 12 (1.0%)
           50th percentile: 8ms
           Number Missed Vsync: 3
           Histogram of frame times:
           0-1: 0

        SurfaceView[com.android.launcher3/.Launcher](BLAST)#0
           Total frames rendered: 900
           Janky frames: 0 (0.0%)
    """.trimIndent()

    @Test
    fun `reads the counter of the layer that belongs to the package`() {
        val reading = TimeStats.read(dump, "com.pubg.krmobile")

        assertEquals(1234L, reading?.frames)
        assertTrue(reading!!.layer.contains("com.pubg.krmobile"))
    }

    @Test
    fun `reads the janky frames and the missed vsyncs`() {
        val reading = TimeStats.read(dump, "com.pubg.krmobile")

        assertEquals(12L, reading?.jankyFrames)
        assertEquals(3L, reading?.missedVsync)
    }

    @Test
    fun `every layer of the package is read`() {
        val dump = """
            SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity](BLAST)#0
               Total frames rendered: 1200
               Janky frames: 12

            SurfaceView[com.pubg.krmobile/com.pubg.krmobile.Hud](BLAST)#0
               Total frames rendered: 900
               Janky frames: 4
        """.trimIndent()

        val readings = TimeStats.readAll(dump, "com.pubg.krmobile")

        assertEquals(2, readings.size)
        assertTrue(readings.any { it.frames == 1200L })
        assertTrue(readings.any { it.frames == 900L })
    }

    @Test
    fun `a package without a layer has no reading`() {
        val reading = TimeStats.read(dump, "com.example.other")

        assertNull(reading)
    }

    @Test
    fun `a dump without layer names is refused`() {
        val global = """
            Total frames rendered: 5000
            Janky frames: 10
        """.trimIndent()

        assertNull(TimeStats.read(global, "com.pubg.krmobile"))
    }

    @Test
    fun `an empty dump has no reading`() {
        assertNull(TimeStats.read("", "com.pubg.krmobile"))
    }
}
