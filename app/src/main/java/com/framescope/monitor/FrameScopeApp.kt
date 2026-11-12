package com.framescope.monitor

import android.app.Application
import android.content.Context
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.CrashLog
import com.framescope.monitor.metrics.MetricsEngine
import com.framescope.monitor.metrics.SessionStore
import com.framescope.monitor.overlay.OverlayController
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Application entry point and dependency container. */
class FrameScopeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Installed first: a crash before this point would be lost.
        CrashLog.install(this)
        // A failure here must not leave a black screen without a trace.
        runCatching {
            container = AppContainer(this)
            container.engine.androidContext = applicationContext
            container.engine.start()
        }.onFailure { CrashLog.write(this, it) }
        // The Shizuku connection check runs in the background and a
        // failure there must never take the interface down with it.
        val app = runCatching { container }.getOrNull() ?: return
        app.scope.launch(Dispatchers.IO) {
            runCatching {
                com.framescope.monitor.core.ProtectedApps.scanInstalled(applicationContext)
                app.hub.refresh()
            }
        }
    }

    override fun onTerminate() {
        if (::container.isInitialized) container.cleanup()
        super.onTerminate()
    }
}

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = AppSettings(context)
    val hub = ShellHub(context.applicationContext)
    val store = SessionStore(context)
    val engine = MetricsEngine(hub, settings, store)
    val overlay = OverlayController(context.applicationContext)

    init {
        // The overlay follows the master switch: with the measurement off
        // nothing is drawn, and it comes back when the measurement is resumed.
        var resumeOverlay = false
        scope.launch {
            settings.measurementEnabled.flow.collect { enabled ->
                if (!enabled) {
                    if (overlay.running.value) {
                        resumeOverlay = true
                        overlay.stop()
                    }
                } else if (resumeOverlay) {
                    resumeOverlay = false
                    overlay.start()
                }
            }
        }
    }

    fun cleanup() {
        engine.stop()
        hub.cleanup()
        scope.cancel()
    }
}
