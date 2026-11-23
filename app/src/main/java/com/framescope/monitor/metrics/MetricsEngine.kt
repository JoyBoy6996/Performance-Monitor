package com.framescope.monitor.metrics

import android.content.Context
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.SessionDeviceInfo
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
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

    /**
     * CPU load from its own tiny shell call. The heavy privileged batch can run
     * late under a game; this keeps the CPU percentage flowing into the session
     * even when that batch is discarded.
     */
    private val _cpuPercent = MutableStateFlow<Float?>(null)

    private val _foregroundApp = MutableStateFlow<String?>(null)
    val foregroundApp: StateFlow<String?> = _foregroundApp.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    /** Raw privileged identity captured at Record time for this run's Shell Logs. */
    private var startTelemetryRaw: String = ""

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
    private var cpuJob: Job? = null

    fun start() {
        if (fpsJob?.isActive == true) return
        fpsJob = scope.launch { fpsLoop() }
        nativeJob = scope.launch { nativeLoop() }
        privilegedJob = scope.launch { privilegedLoop() }
        cpuJob = scope.launch { cpuLoop() }
    }

    fun stop() {
        fpsJob?.cancel()
        nativeJob?.cancel()
        privilegedJob?.cancel()
        cpuJob?.cancel()
        fpsJob = null
        nativeJob = null
        privilegedJob = null
        cpuJob = null
    }

    /**
     * The CPU load, on its own fast clock.
     *
     * It deliberately does not wait for the big privileged batch: one slow dumpsys
     * must never erase the CPU percentage from a recorded match.
     */
    private suspend fun cpuLoop() {
        while (scope.isActive) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(CPU_LOOP_TIMEOUT_MS) {
                    systemSampler.sampleCpu()
                }?.let {
                    _cpuPercent.value = it
                    publishSystem()
                }
            } catch (_: Exception) {
                // A failed read keeps the last real percentage.
            }
            delay(CPU_INTERVAL_MS)
        }
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
                        fpsSampler.sample(
                            targetFps = settings.fpsThreshold.value,
                            thermalStatus = _system.value.thermalStatus
                        )
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
                    val measured = _foregroundApp.value ?: lastMeasuredPackage.value.orEmpty()
                    // The batch asks SurfaceFlinger, the thermals and every game
                    // thread. Under load it can take several seconds; 2.5 s threw
                    // the whole answer away and left every privileged metric blank.
                    kotlinx.coroutines.withTimeoutOrNull(PRIVILEGED_SAMPLE_TIMEOUT_MS) {
                        systemSampler.sample(measured, PRIVILEGED_SHELL_TIMEOUT_MS)
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
        availability[SystemMetric.BATTERY_CURRENT_MA] = battery.availability
        availability[SystemMetric.BATTERY_VOLTAGE_MV] = battery.availability
        availability[SystemMetric.BATTERY_CYCLES] = battery.availability
        sources[SystemMetric.BATTERY_LEVEL] = battery.source
        sources[SystemMetric.BATTERY_TEMP_C] = battery.source
        sources[SystemMetric.POWER_WATTS] = battery.source
        sources[SystemMetric.BATTERY_CURRENT_MA] = battery.source
        sources[SystemMetric.BATTERY_VOLTAGE_MV] = battery.source
        sources[SystemMetric.BATTERY_CYCLES] = battery.source

        val memory = collectors.memory()
        availability[SystemMetric.RAM_USED_GB] = memory.availability
        availability[SystemMetric.RAM_TOTAL_GB] = memory.availability
        availability[SystemMetric.RAM_PERCENT] = memory.availability
        sources[SystemMetric.RAM_USED_GB] = memory.source
        sources[SystemMetric.RAM_TOTAL_GB] = memory.source
        sources[SystemMetric.RAM_PERCENT] = memory.source

        val refresh = collectors.refreshRate()
        // DisplayManager reports the rate of this app's window, which can be held
        // to 120 while the panel runs at 144. SurfaceFlinger measures the panel
        // itself; only fall back to DisplayManager when no shell is connected.
        val panelRefreshHz = fpsSampler.displayHz.value?.takeIf { it > 0f }
        availability[SystemMetric.REFRESH_HZ] = if (panelRefreshHz != null) {
            MetricAvailability.AVAILABLE
        } else refresh.availability
        sources[SystemMetric.REFRESH_HZ] = if (panelRefreshHz != null) {
            "SurfaceFlinger"
        } else refresh.source

        val batteryValue = battery.value
        val memoryValue = memory.value

        _native.value = SystemMetrics(
            batteryLevel = batteryValue?.levelPercent,
            batteryTempC = batteryValue?.temperatureC,
            powerWatts = batteryValue?.watts,
            batteryCurrentMa = batteryValue?.currentMicroAmps?.let { it / 1_000f },
            batteryVoltageMv = batteryValue?.voltageMilliVolts,
            batteryCycles = batteryValue?.cycleCount,
            ramUsedGb = memoryValue?.usedBytes?.let { it / 1_073_741_824f },
            ramTotalGb = memoryValue?.totalBytes?.let { it / 1_073_741_824f },
            ramPercent = memoryValue?.let {
                if (it.totalBytes > 0L) (it.usedBytes * 100 / it.totalBytes).toInt() else null
            },
            refreshHz = panelRefreshHz ?: refresh.value,
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
        val cpuPercent = _cpuPercent.value ?: privileged.cpuPercent
        val availability = (native.availability + privileged.availability).toMutableMap()
        val sources = (native.sources + privileged.sources).toMutableMap()
        if (cpuPercent != null && _cpuPercent.value != null) {
            availability[SystemMetric.CPU_PERCENT] = MetricAvailability.AVAILABLE
            sources[SystemMetric.CPU_PERCENT] = "CPU counter (app read or privileged shell)"
        }
        val merged = SystemMetrics(
            cpuPercent = cpuPercent,
            cpuFreqMHz = privileged.cpuFreqMHz,
            gpuName = privileged.gpuName,
            gpuFreqMHz = privileged.gpuFreqMHz,
            batteryLevel = native.batteryLevel,
            batteryTempC = native.batteryTempC,
            powerWatts = native.powerWatts,
            ramUsedGb = native.ramUsedGb,
            ramTotalGb = native.ramTotalGb,
            ramPercent = native.ramPercent,
            batteryCurrentMa = native.batteryCurrentMa,
            batteryVoltageMv = native.batteryVoltageMv,
            batteryCycles = native.batteryCycles,
            thermalMaxC = privileged.thermalMaxC,
            cpuTempC = privileged.cpuTempC,
            gpuPercent = privileged.gpuPercent,
            cpuGroups = privileged.cpuGroups,
            cpuCoreLoadPct = privileged.cpuCoreLoadPct,
            cpuCoreFreqMHz = privileged.cpuCoreFreqMHz,
            ddrFreqMHz = privileged.ddrFreqMHz,
            threadLoads = privileged.threadLoads,
            thermalStatus = privileged.thermalStatus,
            platform = privileged.platform,
            performanceProfile = privileged.performanceProfile,
            telemetryDetail = privileged.telemetryDetail,
            telemetryRaw = privileged.telemetryRaw,
            refreshHz = native.refreshHz,
            updatedAt = maxOf(native.updatedAt, privileged.updatedAt),
            availability = availability,
            sources = sources,
            measuredAtElapsedNanos = maxOf(
                native.measuredAtElapsedNanos,
                privileged.measuredAtElapsedNanos
            )
        )

        // Keep the hottest value until the phone actually drops below it. A dip
        // between two readings must not erase the peak, or "cooled down" lies.
        val previous = _system.value
        val now = merged.deviceTemperatureC()
        val peak = when {
            now == null -> previous.tempPeakC
            previous.tempPeakC == null || now > previous.tempPeakC -> now
            else -> previous.tempPeakC
        }
        val cooled = if (now != null && peak != null) {
            (peak - now).coerceAtLeast(0f)
        } else null
        _system.value = merged.copy(tempPeakC = peak, tempCooledC = cooled)
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

    /** Captures the test setup once, so an old session still shows its real device. */
    private fun deviceSnapshot(): SessionDeviceInfo {
        val ctx = androidContext ?: return SessionDeviceInfo()
        // The device card shows the real model (I2405), not a vendor-prefixed guess.
        val model = android.os.Build.MODEL.trim()
            .replace(Regex("^vivo\\s+", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() }
        // Build.HARDWARE is a compile-time guess ("qcom"). The chip platform comes
        // only from getprop ro.board.platform on the privileged Shizuku shell.
        val kernelPlatform = _system.value.platform
        val platform = kernelPlatform?.takeIf { it.isNotBlank() }?.uppercase()
        val profile = _system.value.performanceProfile?.takeIf { it.isNotBlank() }
        val metrics = android.util.DisplayMetrics()
        runCatching {
            val manager = ctx.getSystemService(Context.WINDOW_SERVICE)
                as? android.view.WindowManager
            @Suppress("DEPRECATION")
            manager?.defaultDisplay?.getRealMetrics(metrics)
        }
        val resolution = if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            "${metrics.widthPixels} x ${metrics.heightPixels}"
        } else null
        return SessionDeviceInfo(
            model = model,
            platform = platform,
            resolution = resolution,
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            profile = profile
        )
    }

    fun startRecording() {
        if (_recording.value) return
        // The Record button is tapped inside FrameScope, so the foreground app at
        // that moment is FrameScope, not the game the run belongs to. The sampler
        // remembers the last real game while FrameScope is in front; use it for
        // the session name and let the samples switch app if the player moves.
        val current = _foregroundApp.value.orEmpty()
        val own = com.framescope.monitor.BuildConfig.APPLICATION_ID
        val remembered = lastMeasuredPackage.value.orEmpty()
        val target = if (current.isBlank() || current == own ||
            current.contains("launcher") || current == "com.android.systemui"
        ) {
            remembered.takeIf { it.isNotBlank() && it != own } ?: current
        } else current
        val label = appLabel(target)
        // Per-core and per-thread loads are differences between two reads. Starting a
        // new run with the old counters would report the idle time before Record.
        systemSampler.resetDeltas()
        _cpuPercent.value = null
        startTelemetryRaw = ""
        publishSystem()
        sessionLogger.start(
            packageName = target,
            label = label,
            initialBattery = _system.value.batteryLevel,
            device = deviceSnapshot(),
            initialShellLog = telemetryHeaderLine()
        )
        // The shell identity probe runs at Record time on the SAME uid=2000 Shizuku
        // UserService as the frame probe. If it answers, it replaces the placeholder
        // start line and the guessed device facts before the user reads the report.
        scope.launch {
            val measured = _foregroundApp.value ?: lastMeasuredPackage.value.orEmpty()
            val fresh = kotlinx.coroutines.withTimeoutOrNull(PRIVILEGED_SAMPLE_TIMEOUT_MS) {
                systemSampler.sample(measured, PRIVILEGED_SHELL_TIMEOUT_MS)
            }
            if (fresh != null && fresh.telemetryDetail.isNotBlank()) {
                _privileged.value = fresh
                startTelemetryRaw = fresh.telemetryRaw
                publishSystem()
                sessionLogger.adoptTelemetry(
                    detail = fresh.telemetryDetail,
                    platform = fresh.platform,
                    profile = fresh.performanceProfile
                )
            }
        }
        // A recording is its own heat story: start the peak from the temperature at
        // the moment Record was pressed, not from a hot session earlier today.
        val atStart = _system.value.deviceTemperatureC()
        _system.value = _system.value.copy(tempPeakC = atStart, tempCooledC = 0f.takeIf { atStart != null })
        _recording.value = true
        _live.value = LiveStats()
    }

    /** Stop pressed by the user. Nothing starts a recording except the button. */
    fun stopRecordingManually(): SessionRecord? = stopRecording()

    fun stopRecording(): SessionRecord? {
        if (!_recording.value) return null
        _recording.value = false
        val record = sessionLogger.stop(settings.fpsThreshold.value, sessionShellLogs())
        _live.value = LiveStats()
        return record
    }

    /** Identity line captured the moment Record was pressed. */
    private fun telemetryHeaderLine(): String =
        _system.value.telemetryDetail.trim().takeIf { it.isNotBlank() }
            ?: "shell_uid=-- | platform=-- | thermal_zone=-- | clusters=--"

    /** A short, real collector snapshot stored with the run for its Shell Logs card. */
    private fun sessionShellLogs(): String = buildString {
        appendLine("captured_from=${lastMeasuredPackage.value ?: "(none)"} " +
            "at=${android.os.SystemClock.uptimeMillis()}")
        appendLine("--- telemetry (uid=2000 shizuku) ---")
        appendLine(startTelemetryRaw.take(4_000).trim().ifBlank { "--" })
        appendLine("--- window manager ---")
        appendLine(lastForegroundOutput().take(3_000).trim().ifBlank { "--" })
        appendLine("--- frame counters ---")
        appendLine(lastCounterDump().take(6_000).trim().ifBlank { "--" })
        hub.lastError.value?.takeIf { it.isNotBlank() }?.let {
            appendLine("--- last collector error ---")
            appendLine(it.trim())
        }
    }.trimEnd()

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

        /** One batched privileged hardware read per second, as required by telemetry. */
        private const val PRIVILEGED_INTERVAL_MS = 1_000L

        /**
         * The big batch asks for SurfaceFlinger, every thermal sensor and every game
         * thread. Games make it slow; it gets enough time to finish instead of being
         * cancelled and throwing away all of its readings.
         */
        private const val PRIVILEGED_SAMPLE_TIMEOUT_MS = 9_000L
        private const val PRIVILEGED_SHELL_TIMEOUT_MS = 8_500L

        /** The little `/proc/stat` read is independent of the heavy batch. */
        private const val CPU_INTERVAL_MS = 1_000L
        // Shell calls are serialised, so the fast read may wait behind a slow batch.
        // It must still get its turn rather than being cancelled before it starts.
        private const val CPU_LOOP_TIMEOUT_MS = 12_000L

        /** The metrics that need a privileged shell. */
        private val PRIVILEGED_METRICS = listOf(
            SystemMetric.CPU_PERCENT,
            SystemMetric.CPU_FREQ_MHZ,
            SystemMetric.GPU_NAME,
            SystemMetric.GPU_FREQ_MHZ,
            SystemMetric.GPU_PERCENT,
            SystemMetric.THERMAL_MAX_C,
            SystemMetric.CPU_TEMP_C,
            SystemMetric.CPU_CORES,
            SystemMetric.DDR_FREQ_MHZ,
            SystemMetric.THREAD_LOAD,
            SystemMetric.THERMAL_STATUS
        )
    }
}
