package com.framescope.monitor.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen needs the device-wide counter on every poll. */
class ShellCommandsTest {

    @Test
    fun deviceWideCounterUsesTheFirstTotalFramesLine() {
        val command = ShellCommands.surfaceTotalFrames()

        // The first totalFrames of the dump is the screen-wide counter. A filter
        // that stops at layerName used to make the home screen show "--" forever.
        assertTrue(command.contains("grep -m1 'totalFrames'"))
        assertFalse(command.contains("awk"))
    }
}
