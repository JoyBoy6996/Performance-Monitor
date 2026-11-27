package com.framescope.monitor.metrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.shell.ShellHub
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine has to survive its own launch.
 *
 * The frame rate loop is woken up by a coroutine that watches the measurement
 * switch. That coroutine was started while the engine was still being built,
 * and it runs on another thread at once: a field of the engine that has not
 * been given its value yet is still null there. The exception left the
 * coroutine, nothing caught it, and the app died on the first frame after the
 * launch - before a single frame rate was read.
 *
 * The watcher is therefore started with the engine and not while it is built,
 * and the engine is built here over and over to keep that true.
 */
@RunWith(RobolectricTestRunner::class)
class MetricsEngineLaunchTest {

    @Test
    fun `the engine survives its launch and the measurement can be started`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val escaped = AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error ->
            escaped.compareAndSet(null, error)
        }
        try {
            repeat(25) {
                val engine = MetricsEngine(
                    hub = ShellHub(context),
                    settings = AppSettings(context),
                    store = SessionStore(context)
                )
                engine.startMeasurement()
            }
            // The watcher of the switch needs a moment to run.
            Thread.sleep(300L)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertTrue(
            "the engine threw while it was still being built: ${escaped.get()}",
            escaped.get() == null
        )
    }

    @Test
    fun `the switch of the measurement wakes the frame rate loop`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        val engine = MetricsEngine(
            hub = ShellHub(context),
            settings = settings,
            store = SessionStore(context)
        )
        engine.start()

        val before = engine.wakeUpCount()
        settings.measurementEnabled.value = false
        settings.measurementEnabled.value = true
        Thread.sleep(500L)

        assertTrue(
            "the loop was not woken up when the measurement was turned on",
            engine.wakeUpCount() > before
        )
        engine.stop()
    }
}
