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
}
