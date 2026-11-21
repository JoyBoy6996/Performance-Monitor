package com.framescope.monitor.metrics

import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.FpsSourceKind
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.SystemMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a recorded run is made of.
 *
 * The old session divided a count of samples by a count of frames, which is why the
 * smoothness of a run was always around ninety seven percent no matter what happened.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLoggerTest {

    private fun store() = SessionStore(ApplicationProvider.getApplicationContext())

    private fun sample(
        fps: Float,
        totalFrames: Int,
        jankFrames: Int = 0,
        frameTimeMs: Float = 1000f / fps,
        source: FpsSourceKind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
        appPackage: String = "com.example.game",
        low1: Float? = null,
        low5: Float? = null
    ) = FpsState(
        fps = fps,
        frameTimeMs = frameTimeMs,
        totalFrames = totalFrames,
        jankFrames = jankFrames,
        source = "SurfaceFlinger latency",
        surface = "SurfaceView[$appPackage/Main](BLAST)#0",
        valid = true,
        availability = MetricAvailability.AVAILABLE,
        sourceKind = source,
        low1PercentFps = low1,
        low5PercentFps = low5,
        appPackage = appPackage
    )

    @Test
    fun theFrameTotalCountsTheFramesPresentedDuringTheRun() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        // The counter of the sampler only grows while it looks at the same target.
        logger.onSample(sample(fps = 60f, totalFrames = 120), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 360), SystemMetrics(), 60)

        val stats = logger.currentLiveStats()
        // The first reading is the baseline, the run counts what came after it:
        // 120 + 120 unique frames and not the last counter value.
        assertEquals(240, stats.totalFrames)
    }

    @Test
    fun theJankTotalCountsTheJankOfTheRun() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        logger.onSample(sample(fps = 60f, totalFrames = 120, jankFrames = 4), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240, jankFrames = 7), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 360, jankFrames = 7), SystemMetrics(), 60)

        // 7 - 4 = 3 frames went by while the run was going.
        assertEquals(3, logger.currentLiveStats().jankFrames)
    }

    @Test
    fun aCounterThatWentBackwardsStartsANewSegmentInsteadOfSubtracting() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        logger.onSample(sample(fps = 60f, totalFrames = 120), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240), SystemMetrics(), 60)
        // The app in front changed, so the counter of the sampler starts again at zero.
        logger.onSample(
            sample(fps = 60f, totalFrames = 0, appPackage = "com.example.other"),
            SystemMetrics(),
            60
        )
        logger.onSample(
            sample(fps = 60f, totalFrames = 60, appPackage = "com.example.other"),
            SystemMetrics(),
            60
        )

        val stats = logger.currentLiveStats()
        // 120 from the first app and 60 from the second, and nothing was subtracted.
        assertEquals(180, stats.totalFrames)
    }

    @Test
    fun aChangeOfSourceEndsTheSegment() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        logger.onSample(sample(fps = 60f, totalFrames = 120), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240), SystemMetrics(), 60)
        logger.onSample(
            sample(fps = 60f, totalFrames = 360, source = FpsSourceKind.TIMESTATS),
            SystemMetrics(),
            60
        )
        logger.onSample(
            sample(fps = 60f, totalFrames = 480, source = FpsSourceKind.TIMESTATS),
            SystemMetrics(),
            60
        )

        val record = logger.stop(60)
        assertTrue(record != null)
        assertEquals(2, record!!.segments.size)
        assertEquals(
            FpsSourceKind.SURFACE_FLAINGER_LATENCY.name,
            record.segments[0].source
        )
        assertEquals(FpsSourceKind.TIMESTATS.name, record.segments[1].source)
        assertTrue(record.segments[0].frames > 0)
        assertTrue(record.segments[1].frames > 0)
    }

    @Test
    fun aChangeOfAppEndsTheSegment() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        logger.onSample(sample(fps = 60f, totalFrames = 120), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240), SystemMetrics(), 60)
        logger.onSample(
            sample(fps = 30f, totalFrames = 0, appPackage = "com.example.other"),
            SystemMetrics(),
            60
        )
        logger.onSample(
            sample(fps = 30f, totalFrames = 60, appPackage = "com.example.other"),
            SystemMetrics(),
            60
        )

        val record = logger.stop(60)
        assertTrue(record != null)
        assertEquals(2, record!!.segments.size)
        assertEquals("com.example.game", record.segments[0].appPackage)
        assertEquals("com.example.other", record.segments[1].appPackage)
    }

    @Test
    fun theLowsAreTheWorstWindowOfTheRun() {
        val logger = SessionLogger(store())
        logger.start("com.example.game", "Game", 80)

        logger.onSample(sample(fps = 60f, totalFrames = 120, low1 = 55f, low5 = 57f), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 240, low1 = 42f, low5 = 50f), SystemMetrics(), 60)
        logger.onSample(sample(fps = 60f, totalFrames = 360, low1 = 50f, low5 = 55f), SystemMetrics(), 60)

        val stats = logger.currentLiveStats()
        assertEquals(42f, stats.low1Percent, 0.01f)
        assertEquals(50f, stats.low5Percent, 0.01f)
    }
}
