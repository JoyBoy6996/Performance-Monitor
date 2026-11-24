package com.framescope.monitor.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens the audit looked at.
 *
 * Every point of the audit that was true of the app is guarded here, so the
 * same screen does not grow the same problem a second time: the same numbers
 * twice on one screen, the raw terminal output in the middle of the scroll,
 * a hint that is repeated under every graph, two cells with the same name and
 * a way back that is not where the thumb expects it.
 */
class UiAuditTest {

    private fun source(screen: String): String {
        val file = File("src/main/java/com/framescope/monitor/ui/screens/$screen")
        assertTrue("${file.absolutePath} was not found", file.exists())
        return file.readText()
    }

    @Test
    fun `the raw terminal output stays behind a toggle`() {
        val text = source("DashboardScreen.kt")

        assertTrue(
            "the raw output is not behind a toggle any more",
            text.contains("Show raw SurfaceFlinger output")
        )
    }

    @Test
    fun `the hardware readings are not shown twice on the monitor screen`() {
        val text = source("DashboardScreen.kt")

        assertFalse(
            "the device load is a second grid of the same readings",
            text.contains("SectionHeader(\"Device load\")")
        )
        assertFalse(
            "the device load panel is still there",
            text.contains("SystemPanel(")
        )
    }

    @Test
    fun `the hint under the graphs is gone`() {
        val text = source("MonitorLive.kt")

        assertFalse(
            "the same hint is still under every graph",
            text.contains("Tap to read the graph closely")
        )
        assertTrue("the graphs have no lock button", text.contains("Icons.Rounded.Lock"))
    }

    @Test
    fun `the two frame-rate targets are told apart`() {
        val text = source("DashboardScreen.kt")

        assertTrue(
            "the history still calls the smoothness floor a target",
            text.contains("Smoothness floor:")
        )
        assertFalse(
            "the hero card still says only 'Target'",
            text.contains("label = \"Target\",")
        )
    }

    @Test
    fun `a session card has one cell for the one percent low`() {
        val text = source("SessionsScreen.kt")

        val lowCells = Regex("label\\s*=\\s*\"[^\"]*low[^\"]*\"", RegexOption.IGNORE_CASE)
            .findAll(text)
            .count()
        assertTrue("the card has $lowCells cells for the low frame rate", lowCells <= 1)
    }

    @Test
    fun `the actions of a session sit behind one menu`() {
        val text = source("SessionsScreen.kt")

        assertTrue("the card has no overflow menu", text.contains("Icons.Rounded.MoreVert"))
        assertTrue("the delete has no confirmation", text.contains("Delete this run?"))
    }

    @Test
    fun `the way back is on the left of the heading`() {
        val detail = source("SessionDetailScreen.kt")
        val scaffold = File(
            "src/main/java/com/framescope/monitor/ui/ScreenScaffold.kt"
        ).readText()

        assertTrue("the detail screen has no navigation icon", detail.contains("navigationIcon = {"))
        assertTrue(
            "the back arrow is not in the navigation icon",
            detail.contains("Icons.AutoMirrored.Rounded.ArrowBack")
        )
        assertTrue("the heading has no navigation slot", scaffold.contains("navigationIcon"))
    }

    @Test
    fun `the permissions live in one section`() {
        val text = source("SettingsScreen.kt")

        assertTrue(
            "the permissions are not one section",
            text.contains("SectionHeader(\"Permissions & Shell Access\")")
        )
        assertFalse(
            "the shell access is a section of its own",
            text.contains("SectionHeader(\"Permanent shell access\")")
        )
        assertTrue(
            "the other ways to get a shell are not folded away",
            text.contains("Alternative setup methods (Shizuku / ADB)")
        )
    }

    @Test
    fun `the crash is copied from the tools and not from the shell access`() {
        val text = source("SettingsScreen.kt")

        assertTrue("the crash is not in the tools", text.contains("\"Copy last crash\""))
        val tools = text.substringBefore("SectionHeader(\"Monitoring\")")
        assertTrue(
            "the crash button left the privileged access card",
            tools.substringAfter("Privileged access").contains("Copy last crash") ||
                !tools.contains("Copy last crash")
        )
    }

    @Test
    fun `the overlay has one card at the top and not three`() {
        val text = source("OverlayScreen.kt")

        assertFalse("the preview still has a card of its own", text.contains("\"Preview\""))
        assertTrue(
            "the overlay card does not say how it is moved",
            text.contains("Drag widget to move")
        )
    }

    @Test
    fun `the rows say when they do nothing`() {
        val text = source("OverlayScreen.kt")

        assertTrue(
            "the rows do not say that they only work in the wide styles",
            text.contains("Row toggles apply to Classic, Detailed & RTSS styles")
        )
    }
}
