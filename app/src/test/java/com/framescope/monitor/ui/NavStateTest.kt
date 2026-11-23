package com.framescope.monitor.ui

import com.framescope.monitor.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule of the navigation.
 *
 * A tab is always stronger than the page that is open inside it: choosing a
 * tab closes the page, and the back key closes the page and keeps the tab.
 */
class NavStateTest {

    private fun session(id: String = "run") = SessionRecord(
        id = id,
        appPackage = "com.example.game",
        appLabel = "Pixel Rush",
        startedAt = 0L,
        durationMs = 30_000L,
        avgFps = 55f,
        maxFps = 60f,
        minFps = 30f,
        low1Percent = 45f,
        low5Percent = 48f,
        smoothness = 90f,
        totalFrames = 1000,
        jankFrames = 10,
        avgFrameTimeMs = 18f,
        maxFrameTimeMs = 40f,
        maxTempC = 42f,
        avgCpu = 40f,
        fpsSeries = emptyList(),
        cpuSeries = emptyList(),
        tempSeries = emptyList()
    )

    @Test
    fun `the app starts on the monitor`() {
        val nav = NavState()

        assertEquals(AppTab.MONITOR, nav.tab)
        assertNull(nav.openPage)
        assertFalse(nav.hasOpenPage)
    }

    @Test
    fun `choosing a tab closes the report of a run`() {
        val nav = NavState()
        nav.openSession(session())
        assertTrue(nav.hasOpenPage)

        nav.selectTab(AppTab.MONITOR)

        assertEquals(AppTab.MONITOR, nav.tab)
        assertNull(nav.openPage)
    }

    @Test
    fun `choosing a tab closes the report even when it is the same tab`() {
        val nav = NavState()
        nav.selectTab(AppTab.SESSIONS)
        nav.openSession(session())

        nav.selectTab(AppTab.SESSIONS)

        assertNull(nav.openPage)
        assertEquals(AppTab.SESSIONS, nav.tab)
    }

    @Test
    fun `every tab closes the page that was open`() {
        AppTab.entries.forEach { target ->
            val nav = NavState()
            nav.openSession(session())

            nav.selectTab(target)

            assertNull("the page was still open after ${target.name}", nav.openPage)
            assertEquals(target, nav.tab)
        }
    }

    @Test
    fun `the back key closes the page and keeps the tab`() {
        val nav = NavState()
        nav.selectTab(AppTab.SESSIONS)
        nav.openSession(session())

        nav.closePage()

        assertNull(nav.openPage)
        assertEquals(AppTab.SESSIONS, nav.tab)
    }

    @Test
    fun `the comparison and the tools are pages of the tab they were opened from`() {
        val nav = NavState()
        nav.selectTab(AppTab.SESSIONS)
        nav.openCompare()
        assertTrue(nav.openPage is OpenPage.Compare)

        nav.selectTab(AppTab.SETTINGS)
        nav.openTools()
        assertTrue(nav.openPage is OpenPage.Tools)

        nav.selectTab(AppTab.OVERLAY)
        assertNull(nav.openPage)
    }

    @Test
    fun `the report of a run keeps the run it belongs to`() {
        val nav = NavState()
        val run = session("second")
        nav.openSession(run)

        val page = nav.openPage as OpenPage.Session
        assertEquals("second", page.session.id)
    }

    @Test
    fun `the tab survives a rotation`() {
        val nav = NavState()
        nav.selectTab(AppTab.OVERLAY)

        val restored = NavState.Saver.restore(nav.tab.name)!!

        assertEquals(AppTab.OVERLAY, restored.tab)
        assertNull("a page is not kept across a rotation", restored.openPage)
    }
}
