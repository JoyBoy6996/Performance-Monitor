package com.framescope.monitor.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name SurfaceFlinger answers to, taken from the lines it prints.
 *
 * `--list` does not print a name. It prints
 * `RequestedLayerState{<name> parentId=52 !handle z=6}`, which is
 * `RequestedLayerState::getDebugString()`:
 *
 * ```cpp
 * debug << "RequestedLayerState{" << name;
 * if (parentId != UNASSIGNED_LAYER_ID) debug << " parentId=" << parentId;
 * if (!handleAlive) debug << " !handle";
 * if (z != 0) debug << " z=" << z;
 * if (layerStack.id != 0) debug << " layerStack=" << layerStack.id;
 * debug << "}";
 * ```
 *
 * The lines below are the ones the phone printed, character for character.
 */
class SurfaceLayerNameTest {

    @Test
    fun `the descriptor around the name is removed and nothing else`() {
        assertEquals(
            "bae4750 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) " +
                "11-19 19:36:02.082#750494",
            surfaceLayerName(
                "RequestedLayerState{bae4750 SurfaceView[com.pubg.imobile/" +
                    "com.epicgames.ue4.GameActivity](BLAST) 11-19 19:36:02.082#750494}"
            )
        )
    }

    @Test
    fun `an app layer keeps its creation stamp`() {
        assertEquals(
            "com.termux/com.example.gemini.MainActivity 11-19 19:42:47.068#750811",
            surfaceLayerName(
                "RequestedLayerState{com.termux/com.example.gemini.MainActivity " +
                    "11-19 19:42:47.068#750811}"
            )
        )
    }

    @Test
    fun `the attributes after the name are dropped`() {
        assertEquals(
            "Window:6547de0 type=1 com.google.android.googlequicksearchbox/" +
                "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity " +
                "11-19 23:38:59.176#766476",
            surfaceLayerName(
                "RequestedLayerState{Window:6547de0 type=1 com.google.android.googlequicksearchbox/" +
                    "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity " +
                    "11-19 23:38:59.176#766476 parentId=766475}"
            )
        )
    }

    @Test
    fun `every attribute of the descriptor is dropped`() {
        assertEquals(
            "Task=2601 11-08 07:58:58.908#3608",
            surfaceLayerName(
                "RequestedLayerState{Task=2601 11-08 07:58:58.908#3608 parentId=11 !handle z=6}"
            )
        )
    }

    @Test
    fun `a layer without a creation stamp keeps its instance number`() {
        assertEquals(
            "__@@DcDiming__@@__#1",
            surfaceLayerName("RequestedLayerState{__@@DcDiming__@@__#1 z=1342177280}")
        )
    }

    @Test
    fun `a stack and a mirror id are dropped as well`() {
        assertEquals(
            "WindowToken{54ca60a type=2005 android.os.Binder@95a5675} 11-19 23:44:59.757#767066",
            surfaceLayerName(
                "RequestedLayerState{WindowToken{54ca60a type=2005 android.os.Binder@95a5675} " +
                    "11-19 23:44:59.757#767066 parentId=12 z=2 layerStack=0 mirrorId=1,2}"
            )
        )
    }

    @Test
    fun `the name of the timestats dump is already a name`() {
        val name = "bae4750 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) " +
            "11-19 19:36:02.082#750494"
        assertEquals(name, surfaceLayerName(name))
    }

    /**
     * A name that ends in something that looks like an attribute is a name.
     *
     * `Task=2601` starts with `Task=`, which is not one of the fields
     * `getDebugString()` appends, so the layer keeps it.
     */
    @Test
    fun `a value that is part of the name is not an attribute`() {
        assertEquals("Task=2601", surfaceLayerName("RequestedLayerState{Task=2601}"))
    }

    @Test
    fun `the app's own layers come first and the newest are the fallback`() {
        val list = """
            RequestedLayerState{StatusBar 11-13 15:18:20.225#367810}
            RequestedLayerState{com.pubg.imobile/com.epicgames.ue4.GameActivity 11-19 19:00:00.000#1}
            RequestedLayerState{bae4750 SurfaceView[com.pubg.imobile/com.epicgames.ue4.GameActivity](BLAST) 11-19 19:36:02.082#750494}
            RequestedLayerState{NotificationShade 11-19 23:47:39.765#768015 parentId=367787}
        """.trimIndent()

        val names = layerNamesFor(list, "com.pubg.imobile")
        assertEquals(2, names.count { it.contains("com.pubg.imobile") })
        // The two newest layers of the list are kept behind them, so a game
        // that draws into an unnamed surface is still found.
        assertTrue(names.last().startsWith("NotificationShade"))
        assertFalse(names.any { it.startsWith("RequestedLayerState") })
    }

    @Test
    fun `window manager entries are not layers`() {
        val list = """
            RequestedLayerState{ActivityRecord{76553019 u0 com.pubg.imobile/.GameActivity t1 d0} 11-12 21:18:25.453#304286}
            RequestedLayerState{com.pubg.imobile/com.epicgames.ue4.GameActivity 11-19 19:00:00.000#1}
        """.trimIndent()

        val names = layerNamesFor(list, "com.pubg.imobile")
        assertFalse(names.any { it.startsWith("ActivityRecord") })
        assertEquals("com.pubg.imobile/com.epicgames.ue4.GameActivity 11-19 19:00:00.000#1", names.first())
    }
}
