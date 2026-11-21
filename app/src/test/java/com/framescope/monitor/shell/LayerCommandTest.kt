package com.framescope.monitor.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys SurfaceFlinger --latency` answers to the exact layer name and to
 * nothing else.
 *
 * SurfaceFlinger compares the argument with `layer->getName()` character for
 * character:
 *
 * ```cpp
 * StringAppendF(&result, "%" PRId64 "\n", mScheduler->getPacesetterVsyncPeriod().ns());
 * traverseLegacyLayers([&](Layer* layer) {
 *     if (layer->getName() == name.c_str()) layer->dumpFrameStats(result);
 * });
 * ```
 *
 * A name that does not match produces exactly one line - the slower of the
 * refresh periods in nanoseconds - and no frames at all. On a 144 Hz phone that
 * line reads `6944444`, which is what every probe of every layer answered with.
 */
class LayerCommandTest {

    /**
     * A name is a name, not a quoted shell word.
     *
     * The argument replaced `'` and nothing else, so a name that contains a
     * space survived - but the whole descriptor around the name did not, and
     * the descriptor is never the name.
     */
    @Test
    fun `the layer name is quoted exactly and the rest is not added`() {
        val name = "bae4750 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) " +
            "11-19 19:36:02.082#750494"
        val command = ShellCommands.surfaceLatencyOfNames(listOf(name))
        assertTrue(command.contains("--latency '$name'"))
        assertTrue(command.contains("echo '@@@$name'"))
    }

    /**
     * The rows are oldest first, so the newest frames are the LAST ones.
     *
     * The latency dump is a rolling window of about 128 rows, and a game owns a
     * dozen layers. Ten of those windows do not fit into a shell call: the dump
     * arrives cut in half and the layers at the end - the ones that are drawing
     * - are the ones that are missing.
     */
    @Test
    fun `every layer keeps only the newest rows of its dump`() {
        val command = ShellCommands.surfaceLatencyOfNames(listOf("SurfaceView[pkg](BLAST)#0"))
        assertTrue(command.contains("tail -n 60"))
        assertFalse(command.contains("head -40"))
    }

    @Test
    fun `no layer is asked twice`() {
        val command = ShellCommands.surfaceLatencyOfNames(
            listOf("SurfaceView[pkg](BLAST)#0", "SurfaceView[pkg](BLAST)#0")
        )
        assertEquals(1, command.split("--latency").size - 1)
    }

    @Test
    fun `an empty list costs no round trip`() {
        assertEquals("true", ShellCommands.surfaceLatencyOfNames(emptyList()))
        assertEquals("true", ShellCommands.surfaceLatencyOfNames(listOf("", "  ")))
    }

    /**
     * A phone has no awk.
     *
     * Toybox, which is what a phone runs, has no awk, and a command that is
     * not there takes the whole answer with it: the dump comes back empty and
     * every app looks like it renders nothing.
     */
    @Test
    fun `no tool is used that a phone does not have`() {
        val commands = listOf(
            ShellCommands.surfaceLatencyOfNames(listOf("SurfaceView[com.pubg.krmobile/Main]#0")),
            ShellCommands.surfaceLatencyOfLayer("SurfaceView[com.pubg.krmobile/Main]#0"),
            ShellCommands.surfaceLatencyDefault(),
            ShellCommands.surfaceTimeStatsWindow(),
            ShellCommands.surfaceList()
        )

        for (command in commands) {
            assertFalse(command.contains("awk"))
            assertFalse(command.contains("perl"))
            assertFalse(command.contains("python"))
            assertFalse(command.contains("bc "))
        }
    }

    @Test
    fun `a single layer keeps its whole dump`() {
        // One layer is a single rolling window that fits in a shell call, and
        // the parser reads it from the oldest row to the newest. Trimming it -
        // to the first rows in particular - would keep the oldest frames.
        val command = ShellCommands.surfaceLatencyOfLayer("SurfaceView[com.pubg.krmobile/x]#0")
        assertFalse(command.contains("head"))
        assertFalse(command.contains("tail"))
        assertTrue(command.contains("--latency 'SurfaceView[com.pubg.krmobile/x]#0'"))
    }


    @Test
    fun `the counters of one app are asked for without the counters of the whole device`() {
        val command = ShellCommands.surfaceTimeStatsDumpOf("com.pubg.krmobile")

        // The dump of every layer of the device does not fit in the time a
        // measurement may take, and a cut dump has lost the layers that draw.
        assertTrue(command.contains("grep -B 6 -A 14 -F 'com.pubg.krmobile'"))
        assertTrue(command.contains(ShellCommands.TIMESTATS_END_MARKER))
    }

    @Test
    fun `the counters of the whole device say that they arrived`() {
        assertTrue(
            ShellCommands.surfaceTimeStatsDumpMarked()
                .contains(ShellCommands.TIMESTATS_END_MARKER)
        )
    }
}
