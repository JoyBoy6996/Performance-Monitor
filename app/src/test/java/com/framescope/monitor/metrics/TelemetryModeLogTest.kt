package com.framescope.monitor.metrics

import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.FpsSourceKind
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.SessionDeviceInfo
import com.framescope.monitor.core.SystemMetrics
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The line of a run that says how the run was measured.
 *
 * It is the first line of the Shell Logs of every new run, and it stays there
 * when the shell answers later: the run was started in one way and measured
 * in another, and the log says both.
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryModeLogTest {

    private fun logger() = SessionLogger(
        SessionStore(ApplicationProvider.getApplicationContext())
    )

    private fun sample(fps: Float) = FpsState(
        fps = fps,
        frameTimeMs = 1000f / fps,
        totalFrames = (fps * 3).toInt(),
        jankFrames = 0,
        source = "SurfaceFlinger latency",
        surface = "SurfaceView[com.pubg.imobile/Main](BLAST)#0",
        valid = true,
        sourceKind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
        appPackage = "com.pubg.imobile"
    )

    private fun start(logger: SessionLogger, mode: String) {
        logger.start(
            packageName = "com.pubg.imobile",
            label = "BGMI",
            initialBattery = 80,
            device = SessionDeviceInfo(),
            initialShellLog = mode
        )
    }

    @Test
    fun `the mode line is the first line of the run`() {
        val logger = logger()
        start(logger, "Mode: Basic | Platform: SUN | Clusters: CPU 0~1")

        logger.adoptTelemetry(
            detail = "shell_uid=2000 | platform=SUN | thermal_zone=cpu0 | clusters=CPU 0~1",
            platform = "SUN",
            profile = "Balanced",
            mode = "Mode: Shizuku (uid=2000)"
        )
        logger.onSample(sample(58f), SystemMetrics(), 45)
        logger.onSample(sample(59f), SystemMetrics(), 45)
        logger.onSample(sample(57f), SystemMetrics(), 45)

        val log = logger.stop(45)?.shellLogs.orEmpty()
        assertTrue("the log is empty", log.isNotBlank())
        assertTrue("the log does not start with the mode line: $log", log.startsWith("Mode: "))
        assertTrue("the log does not carry the answer of the shell", log.contains("shell_uid=2000"))
    }

    @Test
    fun `a run without a shell keeps the line of the basic mode`() {
        val logger = logger()
        start(logger, "Mode: Basic | Platform: SUN | Clusters: CPU 0~1")

        logger.onSample(sample(58f), SystemMetrics(), 45)
        logger.onSample(sample(59f), SystemMetrics(), 45)
        logger.onSample(sample(57f), SystemMetrics(), 45)

        val log = logger.stop(45)?.shellLogs.orEmpty()
        assertTrue("the log is empty", log.isNotBlank())
        assertTrue("the log does not say how the run was measured", log.startsWith("Mode: Basic"))
        assertTrue("the log does not say what the device is", log.contains("Platform: SUN"))
    }
}
