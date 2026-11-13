package com.framescope.monitor.metrics

import android.content.Context
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Drives the sampling loop, keeps live telemetry, and coordinates session logging. */
class MetricsEngine(
    private val hub: ShellHub,
    private val settings: AppSettings,
    private val store: SessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fpsSampler = FpsSampler(hub)
    private val systemSampler = SystemSampler(hub)
    private val sessionLogger = SessionLogger(store)

    var androidContext: Context? = null
        set(value) {
            field = value
            fpsSampler.context = value
            systemSampler.context = value
        }

    val fps: StateFlow<FpsState> = fpsSampler.state

    private val _system = MutableStateFlow(SystemMetrics())
    val system: StateFlow<SystemMetrics> = _system.asStateFlow()

    private val _foregroundApp = MutableStateFlow<String?>(null)
    val foregroundApp: StateFlow<String?> = _foregroundApp.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _live = MutableStateFlow(LiveStats())
    val live: StateFlow<LiveStats> = _live.asStateFlow()

    private val _protectedForeground = MutableStateFlow(false)
    val protectedForeground: StateFlow<Boolean> = _protectedForeground.asStateFlow()

    private val _diagnostic = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    private val _liveSeries = MutableStateFlow<List<Float>>(emptyList())
    val liveSeries: StateFlow<List<Float>> = _liveSeries.asStateFlow()

    private val _measuredLayer = MutableStateFlow<String?>(null)
    val measuredLayer: StateFlow<String?> = _measuredLayer.asStateFlow()

    val probeReport: StateFlow<String> = MutableStateFlow("")

    private var loop: Job? = null
    private var lastAutoApp: String? = null
    private var autoAppSince = 0L

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var slowCounter = 0
            while (isActive) {
                val started = System.currentTimeMillis()
                val enabled = settings.measurementEnabled.value

                val privilegedReady = hub.status.value.state == AccessState.READY
                val fpsState = if (enabled && privilegedReady) {
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(3000L) {
                            fpsSampler.sample(settings.fpsThreshold.value)
                        } ?: run {
                            _diagnostic.value = "Frame sampler timed out"
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                } else if (!enabled) {
                    fpsSampler.reset()
                    null
                } else {
                    null
                }

                val currentApp = fpsSampler.foregroundApp
                _foregroundApp.value = currentApp
                _measuredLayer.value = fpsSampler.measuredLayer

                _diagnostic.value = when {
                    !enabled -> null
                    !privilegedReady -> hub.status.value.detail.ifBlank {
                        "Shizuku is unavailable. Cached metrics and sessions are preserved."
                    }
                    else -> hub.lastError.value
                        ?: fpsState?.reason
                        ?: if (fpsState?.valid == false) "Waiting for frame presentation" else null
                }

                if (hub.status.value.state == AccessState.READY && !settings.setupCompleted.value) {
                    settings.setupCompleted.value = true
                }

                updateProtectedFlag()
                handleAutoRecord(currentApp)

                slowCounter++
                if (privilegedReady && slowCounter % SLOW_EVERY == 0) {
                    val metrics = try {
                        kotlinx.coroutines.withTimeoutOrNull(2500L) {
                            systemSampler.sample()
                        }
                    } catch (e: Exception) {
                        null
                    }
                    if (metrics != null) _system.value = metrics
                }

                if (fpsState != null && fpsState.valid) {
                    if (sessionLogger.isRecording) {
                        sessionLogger.onSample(fpsState, _system.value, settings.fpsThreshold.value)
                        _live.value = sessionLogger.currentLiveStats()
                    }

                    // Update live series for chart
                    val currentList = _liveSeries.value.toMutableList()
                    currentList.add(fpsState.fps)
                    if (currentList.size > 60) currentList.removeAt(0)
                    _liveSeries.value = currentList
                }

                val elapsed = System.currentTimeMillis() - started
                val wait = (settings.sampleIntervalMs.value - elapsed).coerceAtLeast(60L)
                delay(wait)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun startRecording() {
        if (_recording.value) return
        val current = _foregroundApp.value.orEmpty()
        val label = appLabel(current)
        sessionLogger.start(current, label, _system.value.batteryLevel)
        _recording.value = true
        _live.value = LiveStats()
    }

    fun stopRecording(): SessionRecord? {
        if (!_recording.value) return null
        _recording.value = false
        val record = sessionLogger.stop(settings.fpsThreshold.value)
        _live.value = LiveStats()
        return record
    }

    private fun handleAutoRecord(currentApp: String?) {
        if (!settings.autoRecord.value) {
            if (_recording.value) stopRecording()
            lastAutoApp = null
            return
        }
        if (currentApp.isNullOrBlank()) return
        if (currentApp != lastAutoApp) {
            if (_recording.value) stopRecording()
            lastAutoApp = currentApp
            autoAppSince = System.currentTimeMillis()
            return
        }
        if (_recording.value) return
        if (System.currentTimeMillis() - autoAppSince < MIN_AUTO_RECORD_MS) return
        startRecording()
    }

    private fun updateProtectedFlag() {
        val current = _foregroundApp.value
        val enabled = settings.protectBankApps.value
        if (!enabled || current.isNullOrBlank()) {
            _protectedForeground.value = false
            return
        }
        val custom = settings.protectedPackages()
        _protectedForeground.value = ProtectedApps.isProtected(current, custom)
    }

    private fun appLabel(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "Unknown app"
        val ctx = androidContext ?: return pkg.substringAfterLast('.')
        return runCatching {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    companion object {
        private const val SLOW_EVERY = 3
        private const val MIN_AUTO_RECORD_MS = 3_000L
    }
}
