package com.framescope.monitor.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe has to ask for the layer the app is measuring, by that exact name.
 *
 * `dumpsys SurfaceFlinger --latency <name>` compares the argument with
 * `layer->getName()` character for character. About 127 rows means the name was
 * right; a single line means it was not - and that line is the only thing the
 * probe ever produced while the wrapper of a list entry was handed over as if it
 * were a name.
 */
class ProbeCommandTest {

    private val layer = "bae4750 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity]" +
        "(BLAST) 11-19 19:36:02.082#750494"

    @Test
    fun `the measured layer is asked for by its exact name`() {
        val command = Diagnostics.probeCommand("com.pubg.imobile", layer)
        assertTrue(command.contains("--latency '$layer'"))
        assertTrue(command.contains("rows="))
        assertTrue(command.contains("layer=[$layer]"))
    }

    @Test
    fun `a private field of a layer is never put into the shell bare`() {
        // The name goes into the shell in single quotes, so a name that arrives
        // with a quote of its own is stripped instead of closing the quote.
        val command = Diagnostics.probeCommand("com.pubg.imobile", "it's a layer")
        assertTrue(command.contains("--latency 'its a layer'"))
        assertFalse(command.contains("--latency 'it's"))
    }

    @Test
    fun `a probe without a layer still prints the counters`() {
        val command = Diagnostics.probeCommand("com.pubg.imobile", null)
        assertTrue(command.contains("counters of com.pubg.imobile"))
        assertTrue(command.contains("the layer that was measured: none yet"))
    }
}
