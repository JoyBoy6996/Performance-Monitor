package com.framescope.monitor.ui.screens

import com.framescope.monitor.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison of two recorded runs.
 *
 * The comparison may only use the numbers that both runs really measured, and
 * it has to say which run was the better one.
 */
class CompareTest {

    private fun run(
        id: String = "a",
        avgFps: Float = 55f,
        low1: Float = 45f,
        cpu: Float? = 40f,
        temp: Float? = 42f,
        frames: Int = 1000
    ) = SessionRecord(
        id = id,
        appPackage = "com.example.game",
        appLabel = "Pixel Rush",
        startedAt = 0L,
        durationMs = 30_000L,
        avgFps = avgFps,
        maxFps = 60f,
        minFps = 30f,
        low1Percent = low1,
        low5Percent = 48f,
        smoothness = 90f,
        totalFrames = frames,
        jankFrames = 10,
        avgFrameTimeMs = 18f,
        maxFrameTimeMs = 40f,
        maxTempC = temp,
        avgCpu = cpu,
        fpsSeries = emptyList(),
        cpuSeries = emptyList(),
        tempSeries = emptyList()
    )

    @Test
    fun `only the numbers that both runs have are compared`() {
        val a = run(cpu = 40f, temp = null)
        val b = run(avgFps = 58f, cpu = null, temp = 44f)

        val rows = compareRuns(a, b)

        // The load of the processor is missing in the second run and the
        // temperature is missing in the first one: neither is a difference.
        assertTrue(rows.none { it.label == "Average CPU" })
        assertTrue(rows.none { it.label == "Highest temperature" })
        assertTrue(rows.any { it.label == "Average frame rate" })
    }

    @Test
    fun `the difference is the second run minus the first`() {
        val rows = compareRuns(run(avgFps = 55f), run(avgFps = 58f))

        val fps = rows.first { it.label == "Average frame rate" }
        assertEquals(3f, fps.delta!!, 0.001f)
    }

    @Test
    fun `the verdict names the run that was faster`() {
        val verdict = compareVerdict(run(avgFps = 55f), run(avgFps = 58f), threshold = 45)

        assertTrue(verdict, verdict.contains("Run B was 3 fps faster"))
        assertTrue(verdict, verdict.contains("Both runs held the target"))
    }

    @Test
    fun `the verdict says when only one run held the target`() {
        val verdict = compareVerdict(run(avgFps = 50f), run(avgFps = 40f), threshold = 45)

        assertTrue(verdict, verdict.contains("Run A was 10 fps faster"))
        assertTrue(verdict, verdict.contains("Only run A held the target"))
    }

    @Test
    fun `the verdict says when neither run held the target`() {
        val verdict = compareVerdict(run(avgFps = 40f), run(avgFps = 38f), threshold = 45)

        assertTrue(verdict, verdict.contains("Neither run held the target"))
    }

    @Test
    fun `two runs that are close are called close`() {
        val verdict = compareVerdict(run(avgFps = 55f), run(avgFps = 55.4f), threshold = 45)

        assertTrue(verdict, verdict.contains("about the same frame rate"))
    }

    @Test
    fun `a number that is missing has no difference`() {
        val row = ComparisonRow("Average CPU", "%", 40f, null, false)

        assertNull(row.delta)
    }

    @Test
    fun `the jank is the share of the frames`() {
        assertEquals(1f, jankPercent(run(frames = 1000))!!, 0.01f)
    }
}
