package com.framescope.monitor.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app the user is looking at.
 *
 * A video in picture-in-picture can hold the focus while a game is drawn
 * behind it. The run of a game then carried the name of the video app, so a
 * floating window is never the answer and the fullscreen task is.
 */
class ForegroundPackageTest {

    @Test
    fun `the fullscreen game wins over a video in picture in picture`() {
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities):
              * Task{2f1a #88 type=standard A=com.pubg.imobile:1000 U=0 visible=true visible=true mode=fullscreen translucent=false sz=fullscreen}
                mResumedActivity: ActivityRecord{9a3f u0 com.pubg.imobile/.MainActivity t42}
                topResumedActivity=ActivityRecord{9a3f u0 com.pubg.imobile/.MainActivity t42}
              * Task{7c9b #91 type=standard A=app.morphe.android.youtube:1001 U=0 visible=true visible=true mode=pinned translucent=true sz=fullscreen}
                mPinnedTask: Task{7c9b #91 type=standard A=app.morphe.android.youtube:1001}
            WINDOW MANAGER DISPLAY CONTENTS (dumpsys window):
              mCurrentFocus=Window{4c1e u0 app.morphe.android.youtube/app.morphe.android.youtube.MainActivity}
        """.trimIndent()

        assertEquals("com.pubg.imobile", Parsers.foregroundPackage(dump))
    }

    @Test
    fun `a video in picture in picture is never the app in front`() {
        val dump = """
              * Task{7c9b #91 type=standard A=app.morphe.android.youtube:1001 U=0 visible=true visible=true mode=pinned translucent=true sz=fullscreen}
                mPinnedTask: Task{7c9b #91 type=standard A=app.morphe.android.youtube:1001}
              mCurrentFocus=Window{4c1e u0 app.morphe.android.youtube/app.morphe.android.youtube.MainActivity}
        """.trimIndent()

        assertNull(Parsers.foregroundPackage(dump))
    }

    @Test
    fun `a floating window is never the app in front`() {
        val dump = """
              * Task{5b2d #74 type=standard A=com.example.chat:900 U=0 visible=true visible=true mode=freeform translucent=false sz=fullscreen}
              mCurrentFocus=Window{1a2b u0 com.example.chat/com.example.chat.MainActivity}
        """.trimIndent()

        assertNull(Parsers.foregroundPackage(dump))
    }

    @Test
    fun `the fullscreen task is the answer when nothing else named the app`() {
        val dump = """
              * Task{2f1a #88 type=standard A=com.pubg.imobile:1000 U=0 visible=true visible=true mode=fullscreen translucent=false sz=fullscreen}
        """.trimIndent()

        assertEquals("com.pubg.imobile", Parsers.foregroundPackage(dump))
    }

    @Test
    fun `the system bars, the launcher and the keyboard are never the app in front`() {
        listOf(
            "com.android.systemui",
            "com.android.launcher3",
            "com.miui.home",
            "com.google.android.inputmethod.latin",
            "com.qstats.fpsmonitor"
        ).forEach { pkg ->
            val dump = "mCurrentFocus=Window{4c1e u0 $pkg/$pkg.MainActivity}"
            assertNull("$pkg must never be the app in front", Parsers.foregroundPackage(dump))
        }
    }

    @Test
    fun `a game is still the app in front`() {
        val dump = """
                topResumedActivity=ActivityRecord{9a3f u0 com.pubg.imobile/.MainActivity t42}
        """.trimIndent()

        assertEquals("com.pubg.imobile", Parsers.foregroundPackage(dump))
    }

    @Test
    fun `the query asks for the task that is really resumed`() {
        val command = ShellCommands.foregroundPackage()

        assertTrue("the query does not ask for the resumed activity",
            command.contains("topResumedActivity"))
        assertTrue("the query does not ask for the pinned task",
            command.contains("mPinnedTask"))
        assertTrue("the query does not ask for the windowing mode",
            command.contains("windowingMode="))
    }
}
