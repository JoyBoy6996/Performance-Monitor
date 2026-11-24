package com.framescope.monitor.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eleven cards of a run.
 *
 * The screen and the PDF show the same run in the same order. The old details
 * screen showed a second and different summary above these cards - "Average
 * frame rate", a charts/table switch, a "what happened" timeline - which said
 * the same thing twice and pushed the cards below the fold. This test guards
 * the order of the cards and the absence of that block.
 */
class SceneCardLayoutTest {

    private fun source(name: String): String {
        val file = File("src/main/java/com/framescope/monitor/ui/screens/$name")
        assertTrue("${file.absolutePath} was not found", file.exists())
        return file.readText()
    }

    /** The cards that carry a title, in the order they are drawn. */
    private val cardTitles = listOf(
        "FPS & Temperature(°C)",
        "Frame Time(ms)",
        "CPU Usage(%)",
        "CPU Frequency(MHz)",
        "Power(W) & Capacity(%)",
        "CPU Temperature(°C)",
        "Device Detail"
    )

    @Test
    fun `the scene cards carry every chart in the order of the report`() {
        val text = source("SceneSessionCards.kt")
        var at = 0
        cardTitles.forEach { title ->
            val next = text.indexOf(title, at)
            assertTrue("$title is missing from the scene cards", next >= 0)
            at = next + title.length
        }
    }

    @Test
    fun `the details screen shows only the scene cards`() {
        val text = source("SessionDetailScreen.kt")
        listOf(
            "Average frame rate",
            "Lowest seconds",
            "Charts | Table",
            "What happened",
            "SessionSummaryCard",
            "SessionTimelinePanel"
        ).forEach { legacy ->
            assertFalse(
                "the legacy block '$legacy' is still on the details screen",
                text.contains(legacy)
            )
        }
        assertTrue("the scene cards are not on the details screen",
            text.contains("SceneSessionCards("))
    }

    @Test
    fun `the report carries the same cards in the same order`() {
        val text = source("SessionShare.kt")
        var at = 0
        cardTitles.forEach { title ->
            val next = text.indexOf("\"$title\"", at)
            assertTrue("$title is missing from the report", next >= 0)
            at = next + title.length
        }
    }

    @Test
    fun `the report prints the name of the app with its version`() {
        val text = source("SessionShare.kt")
        assertTrue("the report does not name the version of the app",
            text.contains("appVersion(context, session.appPackage)"))
    }
}
