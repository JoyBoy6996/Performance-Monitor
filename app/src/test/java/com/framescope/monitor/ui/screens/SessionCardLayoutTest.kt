package com.framescope.monitor.ui.screens

import com.framescope.monitor.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card of a recorded run.
 *
 * The card was six cells in two rows, four lines of text and a pill, and it
 * took half a screen to scan a list of runs. It is now one row of the four
 * numbers that matter and one quiet line under it.
 */
class SessionCardLayoutTest {

    private fun session(
        avgFps: Float = 58f,
        maxTempC: Float? = 42f,
        avgPowerW: Float? = 3.2f,
        fpsVariance: Float? = 12.5f,
        smoothness: Float? = 91f,
        durationMs: Long = 95_000L,
        totalFrames: Int = 5_420
    ) = SessionRecord(
        id = "run-1",
        appPackage = "com.pubg.imobile",
        appLabel = "BGMI",
        startedAt = 1_700_000_000_000L,
        durationMs = durationMs,
        avgFps = avgFps,
        maxFps = avgFps + 4f,
        minFps = avgFps - 12f,
        low1Percent = 44f,
        low5Percent = 50f,
        smoothness = smoothness ?: 0f,
        totalFrames = totalFrames,
        jankFrames = 120,
        avgFrameTimeMs = 17f,
        maxFrameTimeMs = 33f,
        maxTempC = maxTempC,
        avgCpu = 21f,
        fpsSeries = listOf(58f, 60f, 57f),
        cpuSeries = listOf(20f, 24f, 18f),
        tempSeries = listOf(40f, 42f, 41f),
        avgPowerW = avgPowerW,
        fpsVariance = fpsVariance,
        sceneSmoothnessPercent = smoothness
    )

    @Test
    fun `the footer carries the rest of the run on one line`() {
        val footer = sessionFooter(session(), threshold = 45)

        assertTrue("the duration is missing", footer.contains("Duration 1:35"))
        assertTrue("the frame count is missing", footer.contains("5420 frames"))
        assertTrue("the variance is missing", footer.contains("Variance 12.5"))
        assertTrue("the smoothness is missing", footer.contains("Smooth 91%"))
    }

    @Test
    fun `the footer says whether the run held the target`() {
        assertTrue(sessionFooter(session(avgFps = 58f), 45).contains("Held target"))
        assertTrue(sessionFooter(session(avgFps = 30f), 45).contains("Missed target"))
    }

    @Test
    fun `the temperature and the power are shown side by side`() {
        assertEquals("42° / 3.2W", sessionTempAndPower(session()))
    }

    @Test
    fun `a run without them says so`() {
        val empty = session(maxTempC = null, avgPowerW = null)

        assertEquals("--", sessionTempAndPower(empty))
    }
}
