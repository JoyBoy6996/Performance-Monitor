package com.framescope.monitor.metrics

import android.content.Context
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SystemMetric
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

/**
 * Drives the sampling, keeps live telemetry and coordinates session logging.
 *
 * The three kinds of measurement run on three separate schedules:
 *
 * - The frame rate is sampled on its own clock, because it is the only reading that has
 *   to be fast, and a slow shell command must never delay it.
 * - The native device metrics are read on their own clock and do not need Shizuku.
 * - The privileged metrics are read on a slower clock and only while Shizuku is there.
 *
 * A privileged command that takes two seconds therefore cannot hold up the frame rate,
 * and a phone without Shizuku still shows its battery, its memory and its refresh rate.
 */
class MetricsEngine(
    private val hub: ShellHub,
    private val settings: AppSettings,
    private val store: SessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fpsSampler = FpsSampler(hub)

    /** The last app that was measured, for the diagnostics. */
    val lastMeasuredPackage: StateFlow<String?> get() = fpsSampler.lastMeasuredPackage

    /** The counters the device printed the last time it was asked. */
    fun lastCounterDump(): String = fpsSampler.lastCounterDump

    /** The text the foreground query answered with the last time it ran. */
    fun lastForegroundOutput(): String = fpsSampler.lastForegroundOutput

    /** The last five polled readings, newest last. */
    fun debugFrameLog(): List<Pair<Long, Float>> = fpsSampler.debugFrameLog()

    /** The last five polled readings as text, for the raw probe. */
    fun debugFrameLogText(): String = fpsSampler.debugFrameLogText()
    private val systemSampler = SystemSampler(hub)
    private val sessionLogger = SessionLogger(store)

    var androidContext: Context? = null
        set(value) {
            field = value
            fpsSampler.context = value
            nativeCollectors = value?.let { NativeCollectors(it) }
        }

    private var nativeCollectors: NativeCollectors? = null

    val fps: StateFlow<FpsState> = fpsSampler.state

    private val _system = MutableStateFlow(SystemMetrics())
    val system: StateFlow<SystemMetrics> = _system.asStateFlow()

    /** The metrics that do not need a privileged shell. */
    private val _native = MutableStateFlow(SystemMetrics())

    /** The metrics that do need one. */
    private val _privileged = MutableStateFlow(SystemMetrics())

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

    private var fpsJob: Job? = null
    private var nativeJob: Job? = null
    private var privilegedJob: Job? = null

    private var lastAutoApp: String? = null
    private var autoAppSince = 0L

    fun start() {
        if (fpsJob?.isActive == true) return
        fpsJob = scope.launch { fpsLoop() }
        nativeJob = scope.launch { nativeLoop() }
        privilegedJob = scope.launch { privilegedLoop() }
    }

    fun stop() {
        fpsJob?.cancel()
        nativeJob?.cancel()
        privilegedJob?.cancel()
        fpsJob = null
        nativeJob = null
        privilegedJob = null
    }

    /**
     * The frame rate loop.
     *
     * One sample at a time: the next sample only starts after the previous one has
     * finished, so a slow command cannot pile up.
     */
    private suspend fun fpsLoop() {
        while (scope.isActive) {
            val started = System.nanoTime()
            val enabled = settings.measurementEnabled.value

            // The sampler decides for itself what it can read. When Shizuku is gone it
            // still finds out which app is in front and says that the frame rate needs
            // a privileged shell, instead of the whole interface going quiet.
            val fpsState = if (!enabled) {
                fpsSampler.reset()
                null
            } else {
                try {
                    // The budget is the sum of what the sources inside need, not
                    // less: the counters get four seconds, the layer sweep three,
                    // and a sample cancelled halfway through never reaches the
                    // source that would have answered.
                    kotlinx.coroutines.withTimeoutOrNull(SAMPLE_TIMEOUT_MS) {
                        fpsSampler.sample(settings.fpsThreshold.value)
                    } ?: run {
                        _diagnostic.value = "Frame sampler timed out"
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }

            _foregroundApp.value = fpsSampler.foregroundApp
            _measuredLayer.value = fpsSampler.measuredLayer

            if (fpsState != null) {
                _diagnostic.value = when {
                    fpsState.valid -> null
                    else -> fpsState.reason ?: "Waiting for frame presentation"
                }
            } else if (!enabled) {
                _diagnostic.value = null
            }

            if (hub.status.value.state == AccessState.READY && !settings.setupCompleted.value) {
                settings.setupCompleted.value = true
            }

            updateProtectedFlag()
            handleAutoRecord(fpsSampler.foregroundApp)

            if (fpsState != null && fpsState.valid) {
                if (sessionLogger.isRecording) {
                    sessionLogger.onSample(fpsState, _system.value, settings.fpsThreshold.value)
                    _live.value = sessionLogger.currentLiveStats()
                }

                val currentList = _liveSeries.value.toMutableList()
                currentList.add(fpsState.fps)
                if (currentList.size > 60) currentList.removeAt(0)
                _liveSeries.value = currentList
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val wait = (settings.sampleIntervalMs.value - elapsedMs).coerceAtLeast(60L)
            delay(wait)
        }
    }

    /**
     * The native device metrics.
     *
     * These never need Shizuku, so they never stop.
     */
    private suspend fun nativeLoop() {
        while (scope.isActive) {
            val started = System.nanoTime()
            try {
                collectNative()
            } catch (e: Exception) {
                // A collector that throws must not take the others with it.
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            delay((NATIVE_INTERVAL_MS - elapsedMs).coerceAtLeast(100L))
        }
    }

    /**
     * The privileged device metrics.
     *
     * They are only read while Shizuku is connected. When it is not, the metrics that
     * need it are marked as needing it instead of keeping a stale value on screen.
     */
    private suspend fun privilegedLoop() {
        while (scope.isActive) {
            val started = System.nanoTime()
            if (hub.status.value.state == AccessState.READY) {
                try {
                    kotlinx.coroutines.withTimeoutOrNull(2_500L) {
                        systemSampler.sample()
                    }?.let { _privileged.value = it }
                } catch (e: Exception) {
                    // Keep the last reading; it is marked stale below.
                }
            } else {
                _privileged.value = SystemMetrics(
                    availability = PRIVILEGED_METRICS.associateWith {
                        MetricAvailability.SHIZUKU_REQUIRED
                    },
                    sources = PRIVILEGED_METRICS.associateWith { "privileged shell" }
                )
            }
            publishSystem()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            delay((PRIVILEGED_INTERVAL_MS - elapsedMs).coerceAtLeast(200L))
        }
    }

    /** Reads every native metric, each on its own. */
    private fun collectNative() {
        val collectors = nativeCollectors ?: return
        val availability = mutableMapOf<SystemMetric, MetricAvailability>()
        val sources = mutableMapOf<SystemMetric, String>()

        val battery = collectors.battery()
        availability[SystemMetric.BATTERY_LEVEL] = battery.availability
        availability[SystemMetric.BATTERY_TEMP_C] = battery.availability
        availability[SystemMetric.POWER_WATTS] = battery.availability
        sources[SystemMetric.BATTERY_LEVEL] = battery.source
        sources[SystemMetric.BATTERY_TEMP_C] = battery.source
        sources[SystemMetric.POWER_WATTS] = battery.source

        val memory = collectors.memory()
        availability[SystemMetric.RAM_USED_GB] = memory.availability
        availability[SystemMetric.RAM_TOTAL_GB] = memory.availability
        availability[SystemMetric.RAM_PERCENT] = memory.availability
        sources[SystemMetric.RAM_USED_GB] = memory.source
        sources[SystemMetric.RAM_TOTAL_GB] = memory.source
        sources[SystemMetric.RAM_PERCENT] = memory.source

        val refresh = collectors.refreshRate()
        availability[SystemMetric.REFRESH_HZ] = refresh.availability
        sources[SystemMetric.REFRESH_HZ] = refresh.source

        val batteryValue = battery.value
        val memoryValue = memory.value

        _native.value = SystemMetrics(
            batteryLevel = batteryValue?.levelPercent,
            batteryTempC = batteryValue?.temperatureC,
            powerWatts = batteryValue?.watts,
            ramUsedGb = memoryValue?.usedBytes?.let { it / 1_073_741_824f },
            ramTotalGb = memoryValue?.totalBytes?.let { it / 1_073_741_824f },
            ramPercent = memoryValue?.let {
                if (it.totalBytes > 0L) (it.usedBytes * 100 / it.totalBytes).toInt() else null
            },
            refreshHz = refresh.value,
            updatedAt = System.currentTimeMillis(),
            availability = availability,
            sources = sources,
            measuredAtElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
        )
        publishSystem()
    }

    /** Puts the native and the privileged readings into one snapshot. */
    private fun publishSystem() {
        val native = _native.value
        val privileged = _privileged.value
        _system.value = SystemMetrics(
            cpuPercent = privileged.cpuPercent,
            cpuFreqMHz = privileged.cpuFreqMHz,
            gpuName = privileged.gpuName,
            gpuFreqMHz = privileged.gpuFreqMHz,
            batteryLevel = native.batteryLevel,
            batteryTempC = native.batteryTempC,
            powerWatts = native.powerWatts,
            ramUsedGb = native.ramUsedGb,
            ramTotalGb = native.ramTotalGb,
            ramPercent = native.ramPercent,
            thermalMaxC = privileged.thermalMaxC,
            refreshHz = native.refreshHz,
            updatedAt = maxOf(native.updatedAt, privileged.updatedAt),
            availability = native.availability + privileged.availability,
            sources = native.sources + privileged.sources,
            measuredAtElapsedNanos = maxOf(
                native.measuredAtElapsedNanos,
                privileged.measuredAtElapsedNanos
            )
        )
    }

    /** Where the foreground answer came from, in words a user can read. */
    fun foregroundSourceName(): String = when (fpsSampler.foregroundSource) {
        ForegroundDetector.Source.PRIVILEGED -> "privileged window manager"
        ForegroundDetector.Source.USAGE_STATS -> "usage access (approximate)"
        ForegroundDetector.Source.NONE -> "unknown"
    }

    /** Whether the foreground answer is known to be exact. */
    fun foregroundIsExact(): Boolean = fpsSampler.foregroundConfident

    /** Why the current target was chosen, or why there is none. */
    fun measuredLayerReason(): String = fpsSampler.targetReason

    fun startRecording() {        if (_recording.value) return
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
        private const val MIN_AUTO_RECORD_MS = 3_000L

        /**
         * The longest one frame-rate sample may take.
         *
         * It has to cover the slowest source inside it - the counters wait up to
         * four seconds for SurfaceFlinger - or the sample is cancelled before the
         * source that works for a game gets its turn.
         */
        private const val SAMPLE_TIMEOUT_MS = 12_000L

        /** How often the native device metrics are read. */
        private const val NATIVE_INTERVAL_MS = 1_000L

        /** How often the privileged device metrics are read. */
        private const val PRIVILEGED_INTERVAL_MS = 3_000L

        /** The metrics that need a privileged shell. */
        private val PRIVILEGED_METRICS = listOf(
            SystemMetric.CPU_PERCENT,
            SystemMetric.CPU_FREQ_MHZ,
            SystemMetric.GPU_NAME,
            SystemMetric.GPU_FREQ_MHZ,
            SystemMetric.THERMAL_MAX_C
        )
    }
}
