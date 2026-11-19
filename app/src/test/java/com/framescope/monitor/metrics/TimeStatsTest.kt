package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counters SurfaceFlinger keeps per layer.
 *
 * The text below is the text SurfaceFlinger really prints
 * (`TimeStatsHelper::TimeStatsLayer::toString()` and
 * `TimeStatsHelper::TimeStatsGlobal::toString()`): a global section first, then
 * one block per layer, each block introduced by its `layerName = ` line and
 * headed by the refresh rate, the render rate and the uid printed *before* the
 * name.
 *
 * The global section carries a `totalFrames` of its own. That number belongs to
 * the display, not to the app, and reporting it as the app's frame rate is the
 * one mistake this parser must never make - so every test here would fail if
 * the two counters were confused.
 */
class TimeStatsTest {

    private val dump = """
        SurfaceFlinger TimeStats:
        Legacy stats are as follows:
        statsStart = 1763520000
        statsEnd = 1763520060
        totalFrames = 9999
        missedFrames = 7
        clientCompositionFrames = 0
        refreshRateSwitches = 2
        displayOnTime = 60000 ms
        displayConfigStats is as below:
        144fps = 60000ms

        Global aggregated jank payload (Timeline stats):
        displayRefreshRate = 144 fps
        renderRate = 120 fps
        totalTimelineFrames = 400
        jankyFrames = 9
        sfLongCpuJankyFrames = 2

        displayRefreshRate = 144 fps
        renderRate = 120 fps
        uid = 10234
        layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.MainActivity](BLAST)#0
        packageName = com.pubg.imobile
        gameMode = Unsupported
        totalFrames = 118
        droppedFrames = 1
        lateAcquireFrames = 0
        badDesiredPresentFrames = 0
        Jank payload for this layer:
        totalTimelineFrames = 118
        jankyFrames = 4
        sfLongCpuJankyFrames = 1
        SetFrameRate vote for this layer:
        frameRate = 120.00
        averageFPS = 118.532
        present2present histogram is as below:
        8ms=100 9ms=18

        displayRefreshRate = 144 fps
        renderRate = 60 fps
        uid = 1000
        layerName = SurfaceView[com.android.systemui/com.android.systemui]Splash Screen#0
        packageName = com.android.systemui
        gameMode = Unsupported
        totalFrames = 2
        droppedFrames = 0
        lateAcquireFrames = 0
        badDesiredPresentFrames = 0
        Jank payload for this layer:
        totalTimelineFrames = 2
        jankyFrames = 0
    """.trimIndent()

    @Test
    fun `reads the counters of the layer that belongs to the package`() {
        val reading = TimeStats.read(dump, "com.pubg.imobile")

        assertEquals(118L, reading?.frames)
        assertEquals("com.pubg.imobile", reading?.packageName)
        assertTrue(reading!!.layer.contains("com.pubg.imobile"))
    }

    @Test
    fun `the global display counter is never read as the app's counter`() {
        val readings = TimeStats.readAllUnfiltered(dump)

        assertEquals(2, readings.size)
        assertTrue(readings.none { it.frames == 9999L })
    }

    @Test
    fun `reads the janky frames and the dropped frames`() {
        val reading = TimeStats.read(dump, "com.pubg.imobile")

        assertEquals(4L, reading?.jankyFrames)
        assertEquals(1L, reading?.missedVsync)
    }

    @Test
    fun `reads the rates of the block, not of the block before it`() {
        val reading = TimeStats.read(dump, "com.pubg.imobile")

        assertEquals(120, reading?.renderRate)
        assertEquals(144, reading?.displayRefreshRate)
    }

    @Test
    fun `reads the average frame rate only when SurfaceFlinger printed one`() {
        val readings = TimeStats.readAllUnfiltered(dump)

        assertEquals(118.532f, readings[0].averageFps!!, 0.001f)
        // The second block has no present2present histogram, so it has no average.
        assertNull(readings[1].averageFps)
    }

    @Test
    fun `the second block keeps its own header`() {
        val readings = TimeStats.readAllUnfiltered(dump)

        assertEquals("com.android.systemui", readings[1].packageName)
        assertEquals(60, readings[1].renderRate)
        assertEquals(144, readings[1].displayRefreshRate)
    }

    @Test
    fun `every layer of the package is read`() {
        val twoLayers = """
            displayRefreshRate = 144 fps
            renderRate = 120 fps
            uid = 10234
            layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.MainActivity](BLAST)#0
            packageName = com.pubg.imobile
            totalFrames = 1200
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 12

            displayRefreshRate = 144 fps
            renderRate = 120 fps
            uid = 10234
            layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.Hud](BLAST)#0
            packageName = com.pubg.imobile
            totalFrames = 900
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 4
        """.trimIndent()

        val readings = TimeStats.readAll(twoLayers, "com.pubg.imobile")

        assertEquals(2, readings.size)
        assertTrue(readings.any { it.frames == 1200L })
        assertTrue(readings.any { it.frames == 900L })
    }

    @Test
    fun `a layer without a package name is found by its layer name`() {
        val unnamed = """
            displayRefreshRate = 120 fps
            renderRate = 90 fps
            uid = 10234
            layerName = BLAST#123(com.pubg.imobile/com.pubg.imobile.MainActivity)#0
            packageName =
            totalFrames = 60
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 1
        """.trimIndent()

        val reading = TimeStats.read(unnamed, "com.pubg.imobile")

        assertEquals(60L, reading?.frames)
        assertNull(reading?.packageName)
    }

    @Test
    fun `a package without a layer has no reading`() {
        assertNull(TimeStats.read(dump, "com.example.other"))
    }

    @Test
    fun `a dump without layer names is refused`() {
        val global = """
            SurfaceFlinger TimeStats:
            Legacy stats are as follows:
            totalFrames = 5000
            missedFrames = 10
        """.trimIndent()

        assertTrue(TimeStats.readAllUnfiltered(global).isEmpty())
        assertNull(TimeStats.read(global, "com.pubg.imobile"))
    }

    @Test
    fun `an empty dump has no reading`() {
        assertNull(TimeStats.read("", "com.pubg.imobile"))
        assertTrue(TimeStats.readAllUnfiltered("").isEmpty())
    }

    @Test
    fun `two counters of the same layer are two different counters`() {
        // A game that changes its frame rate gets a new counter from
        // SurfaceFlinger, keyed by the new render rate, and the old one stays
        // in the dump. Reading them as one counter compares a fresh total of
        // 30 with an old total of 2000, and the growth of that comparison is
        // not a frame rate at all.
        val switched = """
            displayRefreshRate = 144 fps
            renderRate = 90 fps
            uid = 10234
            layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.MainActivity](BLAST)#0
            packageName = com.pubg.imobile
            totalFrames = 2000
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 20

            displayRefreshRate = 120 fps
            renderRate = 120 fps
            uid = 10234
            layerName = SurfaceView[com.pubg.imobile/com.pubg.imobile.MainActivity](BLAST)#0
            packageName = com.pubg.imobile
            totalFrames = 30
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 0
        """.trimIndent()

        val readings = TimeStats.readAllUnfiltered(switched)

        assertEquals(2, readings.size)
        assertEquals(2, readings.map { it.key }.distinct().size)
        assertEquals(90, readings[0].renderRate)
        assertEquals(120, readings[1].renderRate)
        assertEquals(144, readings[0].displayRefreshRate)
        assertEquals(120, readings[1].displayRefreshRate)
        assertEquals(10234, readings[0].uid)
    }

    @Test
    fun `the old text is still read`() {
        val legacy = """
            SurfaceView[com.pubg.krmobile/com.pubg.krmobile.MainActivity](BLAST)#0
               Total frames rendered: 1234
               Janky frames: 12 (1.0%)
               Number Missed Vsync: 3
        """.trimIndent()

        val reading = TimeStats.read(legacy, "com.pubg.krmobile")

        assertEquals(1234L, reading?.frames)
        assertEquals(12L, reading?.jankyFrames)
        assertEquals(3L, reading?.missedVsync)
    }
}
