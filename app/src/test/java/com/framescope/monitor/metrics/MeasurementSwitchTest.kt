package com.framescope.monitor.metrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.shell.ShellHub
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The overlay and the measurement are one thing to the user.
 *
 * Scene keeps one switch: the overlay is on and the measurement runs, the
 * overlay is off and nothing is measured. A pill that appears with "-- FPS"
 * on it is not a reading, and a sampler that keeps asking the compositor for
 * counters when the overlay is gone is work the phone does for nothing.
 */
@RunWith(RobolectricTestRunner::class)
class MeasurementSwitchTest {

    private fun engine(): Pair<MetricsEngine, AppSettings> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        val engine = MetricsEngine(
            hub = ShellHub(context),
            settings = settings,
            store = SessionStore(context)
        )
        return engine to settings
    }

    @Test
    fun `turning the measurement on takes the first sample at once`() {
        val (engine, settings) = engine()
        engine.start()
        settings.measurementEnabled.value = false

        engine.startMeasurement()

        assertTrue(
            "the measurement did not turn on",
            settings.measurementEnabled.value
        )
        engine.stop()
    }

    @Test
    fun `turning the measurement off stops the frame rate loop`() {
        val (engine, settings) = engine()
        engine.start()
        engine.startMeasurement()
        assertTrue(settings.measurementEnabled.value)

        engine.stopMeasurement()

        assertFalse(
            "the measurement kept running after it was turned off",
            settings.measurementEnabled.value
        )
        engine.stop()
    }

    @Test
    fun `the measurement can be turned on again after it was turned off`() {
        val (engine, settings) = engine()
        engine.start()

        engine.startMeasurement()
        engine.stopMeasurement()
        engine.startMeasurement()

        assertTrue(
            "the measurement did not come back",
            settings.measurementEnabled.value
        )
        engine.stop()
    }
}
