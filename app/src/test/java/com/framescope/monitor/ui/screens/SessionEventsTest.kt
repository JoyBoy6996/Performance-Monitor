package com.framescope.monitor.ui.screens

import com.framescope.monitor.core.CapChange
import com.framescope.monitor.core.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The moments of a run that are worth pointing at.
 *
 * The function is pure, so the moments can be checked without a screen.
 */
class SessionEventsTest {

    private fun session(
        fps: List<Float> = emptyList(),
        thermal: List<Float> = emptyList(),
        caps: List<CapChange> = emptyList(),
        times: List<Long> = emptyList()
    ) = SessionRecord(
        id = "run",
        appPackage = "com.example.game",
        appLabel = "Pixel Rush",
        startedAt = 0L,
        durationMs = 4_000L,
        avgFps = 55f,
        maxFps = 60f,
        minFps = 20f,
        low1Percent = 20f,
        low5Percent = 40f,
        smoothness = 80f,
        totalFrames = 240,
        jankFrames = 2,
        avgFrameTimeMs = 17f,
        maxFrameTimeMs = 50f,
        maxTempC = 44f,
        avgCpu = 50f,
        fpsSeries = fps,
        cpuSeries = emptyList(),
        tempSeries = emptyList(),
        thermalStatusSeries = thermal,
        capChanges = caps,
        seriesTimesMs = times
    )

    @Test
    fun `a second far below the target is worth pointing at`() {
        val events = sessionEvents(session(fps = listOf(60f, 58f, 20f, 55f)), threshold = 45)

        assertEquals(1, events.size)
        assertEquals(2_000L, events.first().timeMs)
        assertTrue(events.first().label.contains("20 fps"))
    }

    @Test
    fun `a second near the target is not worth pointing at`() {
        val events = sessionEvents(session(fps = listOf(60f, 58f, 44f, 55f)), threshold = 45)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `a change of the frame rate cap is worth pointing at`() {
        val events = sessionEvents(
            session(caps = listOf(CapChange(second = 3, fromFps = 60, toFps = 40))),
            threshold = 45
        )

        assertEquals(1, events.size)
        assertEquals(3_000L, events.first().timeMs)
        assertTrue(events.first().label.contains("60"))
        assertTrue(events.first().label.contains("40"))
    }

    @Test
    fun `a step of the thermal level is worth pointing at`() {
        val events = sessionEvents(
            session(thermal = listOf(0f, 0f, 2f, 2f)),
            threshold = 45
        )

        assertEquals(1, events.size)
        assertEquals(2_000L, events.first().timeMs)
        assertTrue(events.first().label.contains("Thermal"))
    }

    @Test
    fun `the real moments of the run are used`() {
        val events = sessionEvents(
            session(
                fps = listOf(60f, 58f, 20f, 55f),
                times = listOf(0L, 1_100L, 5_400L, 6_000L)
            ),
            threshold = 45
        )

        assertEquals(1, events.size)
        assertEquals(5_400L, events.first().timeMs)
    }

    @Test
    fun `the moments come in the order they happened`() {
        val events = sessionEvents(
            session(
                fps = listOf(60f, 58f, 20f, 55f),
                thermal = listOf(0f, 0f, 2f, 2f),
                caps = listOf(CapChange(second = 3, fromFps = 60, toFps = 40))
            ),
            threshold = 45
        )

        assertEquals(3, events.size)
        // The heat and the slow second happened in the same second, the cap
        // change a second later.
        assertEquals(listOf(2_000L, 2_000L, 3_000L), events.map { it.timeMs })
    }
}
