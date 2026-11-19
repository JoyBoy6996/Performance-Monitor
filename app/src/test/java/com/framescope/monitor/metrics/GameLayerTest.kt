package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A game does not always name its surface after its package.
 *
 * A game draws into a buffer queue that SurfaceFlinger names after the blast buffer,
 * for example "BLAST#123(com.pubg.krmobile/com.pubg.krmobile.MainActivity)". Nothing in
 * that name sits where a package is normally found, so a strict lookup finds nothing and
 * the frame rate of the game stays empty while every other app works.
 *
 * When no layer can be attributed, the surface that drew last is taken: the phone draws
 * one app at a time, so the surface that is presenting right now belongs to the app in
 * front. A system surface is never taken that way.
 */
class GameLayerTest {

    private val pkg = "com.pubg.krmobile"

    private fun surface(name: String, lastPresent: Long) =
        LayerSample(name, 16_666_666L, listOf(lastPresent - 1_000L, lastPresent))

    @Test
    fun takesTheSurfaceThatDrewLastWhenNoLayerNamesThePackage() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("BLAST#101(com.android.systemui)/StatusBar#0", 900_000L),
                surface("BLAST#123(com.example.engine)/UnityMain#0", 700_000L)
            ),
            pkg,
            allowUnattributed = true
        )

        assertNotNull(selection.target)
        assertEquals("BLAST#123(com.example.engine)/UnityMain#0", selection.target!!.name)
        assertTrue(selection.reason.contains("recency"))
    }

    @Test
    fun refusesToGuessWhenTheAttributionIsNotAllowed() {
        val selection = selectVerifiedTarget(
            listOf(surface("BLAST#123(com.example.engine)/UnityMain#0", 700_000L)),
            pkg
        )

        assertNull(selection.target)
    }

    @Test
    fun takesTheGameSurfaceEvenWhenItsNameOnlyMentionsThePackage() {
        val selection = selectVerifiedTarget(
            listOf(surface("BLAST#123($pkg/com.pubg.krmobile.MainActivity)#0", 700_000L)),
            pkg,
            allowUnattributed = true
        )

        assertNotNull(selection.target)
        assertEquals("BLAST#123($pkg/com.pubg.krmobile.MainActivity)#0", selection.target!!.name)
    }

    @Test
    fun neverTakesASystemSurfaceByRecency() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("SurfaceView[com.android.systemui]/StatusBar#0", 900_000L),
                surface("BLAST#123(com.example.engine)/UnityMain#0", 100_000L)
            ),
            pkg,
            allowUnattributed = true
        )

        assertNotNull(selection.target)
        assertTrue(!selection.target!!.name.contains("systemui"))
    }

    @Test
    fun readsTheCountersOfAGameSurfaceNamedAfterItsBlastBuffer() {
        val dump = """
            displayRefreshRate = 144 fps
            renderRate = 120 fps
            uid = 10234
            layerName = BLAST#123($pkg/com.pubg.krmobile.MainActivity)#0
            packageName = $pkg
            totalFrames = 120
            droppedFrames = 1
            Jank payload for this layer:
            jankyFrames = 3
        """.trimIndent()

        val readings = TimeStats.readAll(dump, pkg)

        assertEquals(1, readings.size)
        assertEquals(120L, readings.first().frames)
        assertEquals(3L, readings.first().jankyFrames)
    }

    @Test
    fun readsEveryLayerWhenNoLayerNamesThePackage() {
        val dump = """
            displayRefreshRate = 144 fps
            renderRate = 120 fps
            uid = 1000
            layerName = BLAST#101(com.android.systemui)/StatusBar#0
            packageName =
            totalFrames = 300
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 0

            displayRefreshRate = 144 fps
            renderRate = 120 fps
            uid = 10234
            layerName = BLAST#123(com.example.engine)/UnityMain#0
            packageName =
            totalFrames = 60
            droppedFrames = 0
            Jank payload for this layer:
            jankyFrames = 1
        """.trimIndent()

        val readings = TimeStats.readAllUnfiltered(dump)

        assertEquals(2, readings.size)
        assertEquals(300L, readings[0].frames)
        assertEquals(60L, readings[1].frames)
    }
}
