package com.framescope.monitor.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SurfaceFlinger layer list has to be walked line by line.
 *
 * A layer name contains spaces - "RequestedLayerState{ActivityRecord{219735033 u0 t4356
 * d0} 11-13#0}" - and a `for` loop over `$(...)` splits it into pieces. Every piece is a
 * layer that does not exist, so the latency dump comes back empty and the frame rate of
 * that layer is never measured.
 */
class LayerCommandTest {

    @Test
    fun `the layer list is walked line by line`() {
        val command = ShellCommands.surfaceLatencyOfLayers("com.pubg.krmobile")
        assertTrue(command.contains("while IFS= read -r L"))
        assertFalse(command.contains("for L in \$("))
    }

    @Test
    fun `the fallback list is walked line by line as well`() {
        val command = ShellCommands.surfaceLatencyOfLayers("com.pubg.krmobile", fallback = true)
        assertTrue(command.contains("while IFS= read -r L"))
        assertFalse(command.contains("for L in \$("))
        assertTrue(command.contains("tail -12"))
    }

    @Test
    fun `the layer name is quoted so a space cannot split it`() {
        val command = ShellCommands.surfaceLatencyOfLayers("com.pubg.krmobile")
        assertTrue(command.contains("--latency \"\$L\""))
    }

    /**
     * The shell carries a limited amount of text.
     *
     * The latency dump is a rolling window of about 128 rows per layer, and a game
     * owns a dozen layers. Ten of those windows do not fit into a shell call: the
     * dump arrives cut in half, and the layers that are missing are the ones at the
     * end - the ones that are actually drawing. The game then shows no frame rate
     * while every other app works.
     */
    @Test
    fun `every layer keeps only the refresh period and its newest frames`() {
        val command = ShellCommands.surfaceLatencyOfLayers("com.pubg.krmobile")
        assertTrue(command.contains("awk"))
        assertTrue(command.contains("NR==1{print;next}"))
        assertTrue(command.contains("i=NR-29"))
    }

    @Test
    fun `every layer of the app is asked, not only the newest ones`() {
        val command = ShellCommands.surfaceLatencyOfLayers("com.pubg.krmobile")
        assertTrue(command.contains("grep -F 'com.pubg.krmobile'"))
        assertFalse(command.contains("tail -8"))
    }

    @Test
    fun `a single layer is trimmed as well`() {
        val command = ShellCommands.surfaceLatencyOfLayer("SurfaceView[com.pubg.krmobile/x]#0")
        assertTrue(command.contains("awk"))
        assertTrue(command.contains("i=NR-29"))
    }
}
