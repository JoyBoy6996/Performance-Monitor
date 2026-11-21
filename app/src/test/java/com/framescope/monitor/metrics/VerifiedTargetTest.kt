package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer has to be proven before it can be measured.
 *
 * The old code fell back to "the last three layers in the dump" when no layer matched
 * the foreground package, which meant the app could report the frame rate of the launcher
 * or of a system overlay while claiming it was the game's.
 */
class VerifiedTargetTest {

    private val pkg = "com.example.game"

    private fun surface(name: String, lastPresent: Long = 10_000L) =
        LayerSample(name, 16_666_666L, listOf(lastPresent - 1_000L, lastPresent))

    @Test
    fun picksTheBufferQueueOfTheForegroundPackage() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("SurfaceView[com.android.systemui]/StatusBar#0", 999_000L),
                surface("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", 10_000L)
            ),
            pkg
        )
        assertTrue(selection.verified)
        assertEquals("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", selection.target?.name)
    }

    @Test
    fun refusesToMeasureAnythingWhenNoLayerBelongsToTheApp() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("SurfaceView[com.android.systemui]/StatusBar#0", 999_000L),
                surface("SurfaceView[com.other.app]/Main#0", 999_000L)
            ),
            pkg
        )
        assertFalse(selection.verified)
        assertNull(selection.target)
        assertTrue(selection.reason.contains(pkg))
        // Every layer must be accounted for.
        assertEquals(2, selection.rejected.size)
    }

    @Test
    fun prefersTheRenderSurfaceOverTheAppWindow() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("ActivityRecord[$pkg/.MainActivity]", 999_000L),
                surface("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", 10_000L)
            ),
            pkg
        )
        assertTrue(selection.verified)
        assertEquals("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", selection.target?.name)
    }

    @Test
    fun prefersTheRenderSurfaceEvenWhenTheWindowDrewMoreRecently() {
        // The window is newer because a toast was drawn on it; that must not win.
        val selection = selectVerifiedTarget(
            listOf(
                surface("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", 10_000L),
                surface("ActivityRecord[$pkg/.MainActivity]", 999_000L)
            ),
            pkg
        )
        assertTrue(selection.verified)
        assertEquals("SurfaceView[$pkg/com.example.game.Main](BLAST)#0", selection.target?.name)
    }

    @Test
    fun breaksATieBetweenTwoRenderSurfacesByRecency() {
        val selection = selectVerifiedTarget(
            listOf(
                surface("SurfaceView[$pkg/Main](BLAST)#0", 10_000L),
                surface("SurfaceView[$pkg/Main](BLAST)#1", 90_000L)
            ),
            pkg
        )
        assertTrue(selection.verified)
        assertEquals("SurfaceView[$pkg/Main](BLAST)#1", selection.target?.name)
    }

    @Test
    fun doesNotAttributeASystemSurfaceEvenWhenItNamesThePackage() {
        val selection = selectVerifiedTarget(
            listOf(surface("StatusBar[$pkg]/x#0", 999_000L)),
            pkg
        )
        assertFalse(selection.verified)
        assertTrue(selection.reason.contains("system"))
    }

    @Test
    fun anEmptyDumpHasNoTarget() {
        val selection = selectVerifiedTarget(emptyList(), pkg)
        assertFalse(selection.verified)
        assertNull(selection.target)
    }

    @Test
    fun classifiesTheLayerKinds() {
        assertEquals(LayerKind.SURFACE_VIEW, classifyLayer("SurfaceView[a/b](BLAST)#0"))
        assertEquals(LayerKind.WINDOW, classifyLayer("Window{com.example.game/.Main}"))
        assertEquals(LayerKind.SYSTEM, classifyLayer("StatusBar[com.android.systemui]"))
        assertEquals(LayerKind.UNKNOWN, classifyLayer("something else"))
    }

    @Test
    fun readsThePackageEvidence() {
        assertEquals(
            PackageEvidence.EXACT,
            packageEvidenceOf("SurfaceView[com.example.game/com.example.game.Main](BLAST)#0", pkg)
        )
        assertEquals(
            PackageEvidence.PARTIAL,
            packageEvidenceOf("com.example.game/something", pkg)
        )
        assertEquals(
            PackageEvidence.NONE,
            packageEvidenceOf("SurfaceView[com.other.app]/Main#0", pkg)
        )
        assertEquals(PackageEvidence.NONE, packageEvidenceOf("SurfaceView[x/y]", ""))
    }
}
