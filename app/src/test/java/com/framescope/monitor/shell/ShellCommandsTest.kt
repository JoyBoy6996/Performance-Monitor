package com.framescope.monitor.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen needs the device-wide counter on every poll. */
class ShellCommandsTest {

    @Test
    fun deviceWideCounterUsesTheFirstTotalFramesLine() {
        val command = ShellCommands.surfaceTotalFrames()

        // Two counters live in the dump: the total of the display at the top and
        // the total of the first layer below it. Both are taken, so the screen
        // can be measured by the frames it presented and a game by the frames
        // its own layer drew. A filter that stops at layerName used to make the
        // home screen show "--" forever.
        assertTrue(command.contains("Total frames rendered"))
        assertTrue(command.contains("totalFrames"))
        assertTrue(command.contains("grep -m2"))
        assertFalse(command.contains("awk"))
    }

    @Test
    fun gpuLoadIsFoundAtRuntimeWithoutADeviceName() {
        val command = ShellCommands.gpuBusy()

        assertTrue(command.contains("kgsl-*/gpu_busy_percentage"))
        assertTrue(command.contains("kgsl-*/gpubusy"))
        assertTrue(command.contains("busy_time"))
        assertTrue(command.contains("utilisation"))
        assertFalse(command.contains("iQOO"))
        assertFalse(command.contains("Snapdragon"))
    }

    @Test
    fun gpuLoadParsersAcceptOnlyRealNumbers() {
        assertEquals(42f, Parsers.parseGpuPercentDirect("GPUPCT=42")!!, 0.001f)
        assertEquals(0f, Parsers.parseGpuPercentDirect("GPUPCT=0 %")!!, 0.001f)
        assertNull(Parsers.parseGpuPercentDirect("GPUPCT=unknown"))
        assertNull(Parsers.parseGpuPercentDirect("GPUBUSY=10:100"))
        assertEquals(Pair(10L, 100L), Parsers.parseGpuBusy("GPUBUSY=10:100"))
        assertNull(Parsers.parseGpuBusy("GPUPCT=42"))
    }
}
