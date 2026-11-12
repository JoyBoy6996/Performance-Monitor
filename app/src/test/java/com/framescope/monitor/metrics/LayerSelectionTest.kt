package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app owns several layers and only one of them is actually drawing.
 *
 * The home screen and the settings app both keep a background layer around,
 * so watching the wrong one produces a frame rate that belongs to nothing the
 * user is looking at.
 */
class LayerSelectionTest {

    private fun sample(name: String, last: Long) =
        LayerSample(name, 16_666_666L, listOf(last - 1_000L, last))

    @Test
    fun watchesTheLayerThatDrewMostRecently() {
        val stale = sample("SurfaceView[pkg](BLAST)#0", 2_000L)
        val live = sample("SurfaceView[pkg](BLAST)#1", 90_000L)
        val picked = pickMostRecent(listOf(stale, live))
        assertEquals("SurfaceView[pkg](BLAST)#1", picked.first().name)
    }

    @Test
    fun keepsTheOtherLayersAsFallbacks() {
        val stale = sample("a", 2_000L)
        val live = sample("b", 90_000L)
        val picked = pickMostRecent(listOf(stale, live))
        assertEquals(2, picked.size)
        assertTrue(picked[0].name == "b")
        assertTrue(picked[1].name == "a")
    }

    @Test
    fun doesNotCareAboutTheOrderTheLayersCameIn() {
        val stale = sample("stale", 1_000L)
        val live = sample("live", 500_000L)
        assertEquals("live", pickMostRecent(listOf(live, stale)).first().name)
        assertEquals("live", pickMostRecent(listOf(stale, live)).first().name)
    }

    @Test
    fun splitsTheMultiLayerDumpIntoOneEntryPerLayer() {
        val dump = buildString {
            appendLine("@@@SurfaceView[pkg/act](BLAST)#0")
            appendLine("16666666")
            appendLine("0 0 0")
            appendFrames(1_000_000_000L)
            appendLine("@@@SurfaceView[pkg/act]")
            appendLine("16666666")
            appendLine("0 0 0")
            appendFrames(2_000_000_000L)
        }
        val samples = parseLayerLatencies(dump)
        assertEquals(2, samples.size)
        assertEquals("SurfaceView[pkg/act](BLAST)#0", samples[0].name)
        assertEquals("SurfaceView[pkg/act]", samples[1].name)
        assertTrue(samples[0].presents.isNotEmpty())
        assertTrue(samples[1].presents.isNotEmpty())
    }

    /** Six frames at 60 Hz, in the three column SurfaceFlinger prints. */
    private fun StringBuilder.appendFrames(base: Long) {
        var t = base
        for (i in 0 until 6) {
            if (i > 0) t += 16_666_666
            appendLine("$t ${t + 1} ${t + 2}")
        }
    }

    @Test
    fun ignoresBlocksWithoutFrames() {
        val dump = """
            @@@Broken[pkg/act]
            16666666
            0 0 0
        """.trimIndent()
        assertTrue(parseLayerLatencies(dump).isEmpty())
    }

    @Test
    fun handlesAnEmptyList() {
        assertTrue(pickMostRecent(emptyList()).isEmpty())
    }
}
