package com.framescope.monitor.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The report, drawn and looked at.
 *
 * The page is one dark sheet of paper. The first card used to start at the
 * edge of it, and the labels and the icons of that card were cut off. The
 * chart of the frame rate had lost the curve of the temperature. A test that
 * reads the pixels of the drawn page proves both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PdfRenderTest {

    /** The colour of the page. */
    private val pageBackground = 0xFF0D0D0F.toInt()

    /** The colour of a card on the page. */
    private val cardColour = 0xFF18191C.toInt()

    /** The colour of the curve of the temperature. */
    private val tempColour = 0xFFD97736.toInt()

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun session(
        fps: List<Float> = listOf(58f, 59f, 57f, 60f, 58f, 59f, 58f, 57f),
        temp: List<Float> = listOf(38f, 39f, 40f, 41f, 42f, 41f, 40f, 39f),
        cpu: List<Float> = listOf(20f, 22f, 24f, 21f, 19f, 23f, 25f, 22f),
        note: String = ""
    ) = SessionRecord(
        id = "run-1",
        appPackage = "com.pubg.imobile",
        appLabel = "BGMI",
        startedAt = 1_700_000_000_000L,
        durationMs = 8_000L,
        avgFps = 58f,
        maxFps = 60f,
        minFps = 57f,
        low1Percent = 57f,
        low5Percent = 58f,
        smoothness = 96f,
        totalFrames = 464,
        jankFrames = 4,
        avgFrameTimeMs = 17.2f,
        maxFrameTimeMs = 33f,
        maxTempC = 42f,
        avgCpu = 22f,
        fpsSeries = fps,
        cpuSeries = cpu,
        tempSeries = emptyList(),
        cpuTempSeries = temp,
        socTempSeries = temp,
        note = note
    )

    @Test
    fun thePageIsOneSheetOfDarkPaper() {
        val bitmap = buildImage(context(), session())

        assertEquals(1080, bitmap.width)
        assertTrue("the page is too short", bitmap.height > 1000)
        assertEquals("the page is not the colour of the report", pageBackground, bitmap.getPixel(5, 5))
    }

    @Test
    fun theFirstCardKeepsRoomAboveIt() {
        val bitmap = buildImage(context(), session())

        // The margin above the first card is empty paper, and the card starts
        // below it: the labels and the icons of the card are whole.
        assertEquals("the paper above the first card is not empty", pageBackground, bitmap.getPixel(540, 20))
        assertEquals("the first card does not start at the top margin", pageBackground, bitmap.getPixel(540, 40))
        assertNotEquals("the first card is not there", pageBackground, bitmap.getPixel(540, 60))
        assertEquals("the first card is not the colour of a card", cardColour, bitmap.getPixel(540, 60))
    }

    @Test
    fun theChartOfTheFrameRateCarriesTheTemperature() {
        val bitmap = buildImage(context(), session())

        var found = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) == tempColour) found++
            }
        }
        assertTrue("the curve of the temperature is not on the page", found > 50)
    }

    @Test
    fun aRunWithoutCountersHasNoEmptyCharts() {
        val withCpu = buildImage(context(), session())
        val withoutCpu = buildImage(context(), session(cpu = emptyList()))

        // The card of the load of the processor is one sheet of paper tall.
        assertTrue(
            "the page is the same with and without the load of the processor",
            withCpu.height - withoutCpu.height > 400
        )
    }

    @Test
    fun aNoteAddsItsCard() {
        val without = buildImage(context(), session())
        val with = buildImage(context(), session(note = "the run was smooth"))

        assertTrue("the note did not add a card", with.height > without.height)
    }
}
