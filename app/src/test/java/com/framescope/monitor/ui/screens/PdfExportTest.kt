package com.framescope.monitor.ui.screens

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report of a run.
 *
 * The page is one dark sheet of paper, and the cards on it have to be whole:
 * the top row of the device card was cut off by the edge of the page, and the
 * chart of the frame rate lost the curve of the temperature.
 */
class PdfExportTest {

    private fun report(): String {
        val file = File("src/main/java/com/framescope/monitor/ui/screens/SessionShare.kt")
        assertTrue("${file.absolutePath} was not found", file.exists())
        return file.readText()
    }

    @Test
    fun `the page keeps room above the first card and below the last one`() {
        val text = report()

        assertTrue("the page has no room above the first card", text.contains("TOP_MARGIN = 48f"))
        assertTrue("the page has no room below the last card", text.contains("BOTTOM_MARGIN = 48f"))
        assertTrue(
            "the first card does not start at the top margin",
            text.contains("private var cursor = TOP_MARGIN")
        )
        assertTrue(
            "the height of the page does not carry both margins",
            text.contains("TOP_MARGIN + BOTTOM_MARGIN")
        )
    }

    @Test
    fun `the chart of the frame rate carries the temperature`() {
        val text = report()

        assertTrue(
            "the chart does not draw the temperature",
            text.contains("ChartLine(\"TEMP(°C)\"")
        )
        assertTrue(
            "the temperature of the chart is not the one of the chip",
            text.contains("chartTemperatureSeries(session)")
        )
        assertTrue(
            "the footer does not tell the temperature",
            text.contains("TEMP MAX:")
        )
    }

    @Test
    fun `the report leaves out the charts that have no readings`() {
        val text = report()

        assertTrue(
            "the report does not use the shared answer of the charts",
            text.contains("session.hardwareCharts()")
        )
        assertTrue(
            "the report draws every chart of the hardware",
            text.contains("if (charts.cpuUsage) report.cpuUsage(session)")
        )
    }

    @Test
    fun `the report leaves out the reminder of an empty note`() {
        val text = report()

        assertTrue(
            "the note card is drawn even when there is no note",
            text.contains("if (session.note.isNotBlank()) report.note(session)")
        )
    }
}
