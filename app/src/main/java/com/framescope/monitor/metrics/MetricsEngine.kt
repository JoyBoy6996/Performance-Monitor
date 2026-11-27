package com.framescope.monitor.metrics

import android.content.Context
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.CpuGroupMetric
import android.os.SystemClock
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveHistory
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.SessionDeviceInfo
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.ShellLogs
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.telemetryModeLine
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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

    init {
        // Turning the measurement on has to end the wait of the frame rate
        // loop at once, wherever the switch was touched: the overlay, the
        // Resume button of the Monitor tab or the Setup screen.
        scope.launch {
            settings.measurementEnabled.flow.collect { enabled ->
                if (enabled) {
                    freshStart = true
                    wakeUp.value = wakeUp.value + 1
                }
            }
        }
    }

    /**
     * The readings the app takes by itself, without a privileged shell.
     *
     * They are the fallback, never the first choice: the shell answers the
     * same questions better. But a phone without Shizuku still says what its
     * chip is and how warm it is, and a run that knows nothing is worse than
     * a run that knows what it could read.
     */
    private var basicCollector: BasicCollector? = null

    /**
     * The collector of the app's own readings.
     *
     * It is built when the context of the app is known, and kept for the
     * rest of the life of the engine.
     */
    private fun basicCollector(): BasicCollector? {
        basicCollector?.let { return it }
        val ctx = androidContext ?: return null
        return BasicCollector(ctx).also { basicCollector = it }
    }

    /** What the device is, read once when a run starts. */
    private var basicStart: BasicCollector.SessionStart? = null

    /** The readings of the last tick of the app's own collector. */
    private var basicSample: BasicCollector.Sample? = null

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

    /** When the current run was started, on the elapsed clock. */
    private var recordingStartedAtMs: Long = 0L

    private val _live = MutableStateFlow(LiveStats())
    val live: StateFlow<LiveStats> = _live.asStateFlow()

    private val _protectedForeground = MutableStateFlow(false)
    val protectedForeground: StateFlow<Boolean> = _protectedForeground.asStateFlow()

    private val _diagnostic = MutableStateFlow<String?>(null)
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    private val _liveSeries = MutableStateFlow<List<Float>>(emptyList())
    val liveSeries: StateFlow<List<Float>> = _liveSeries.asStateFlow()

    /**
     * The short memory of the live readings, for the graphs of the dashboard.
     *
     * It is filled while the measurements run and forgets itself when the
     * measurement is switched off, so the graphs never show a line that belongs
     * to a run that is over.
     */
    private val _liveHistory = MutableStateFlow(LiveHistory())
    val liveHistory: StateFlow<LiveHistory> = _liveHistory.asStateFlow()
    private val historyBuffer = LiveHistoryBuffer()

    private val _measuredLayer = MutableStateFlow<String?>(null)
    val measuredLayer: StateFlow<String?> = _measuredLayer.asStateFlow()


    private var fpsJob: Job? = null
    private var nativeJob: Job? = null
    private var privilegedJob: Job? = null
    private var cpuJob: Job? = null

    /**
     * Wakes the frame rate loop up.
     *
     * The loop waits between two samples so a slow command cannot pile up.
     * When the measurement is turned on the wait has to end at once: the
     * overlay appears with "-- FPS" on it and that is not a reading.
     */
    private val wakeUp = MutableStateFlow(0)

    /** Set while the first sample of a fresh measurement is still missing. */
    private var freshStart = false

    /**
     * Turns the measurement on and takes the first sample at once.
     *
     * This is the one action that turns the measurement on. The overlay, the
     * Resume button and the quick settings tile all come through here, so they
     * cannot drift apart.
     */
    fun startMeasurement() {
        settings.measurementEnabled.value = true
        freshStart = true
        wakeUp.value = wakeUp.value + 1
    }

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
                    historyBuffer.addCpu(SystemClock.elapsedRealtime(), it)
                    _liveHistory.value = historyBuffer.snapshot()
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
                // The graphs of the dashboard belong to the running measurement.
                // When the measurement is off they are emptied, so they never show
                // a line of a run that is over.
                historyBuffer.clear()
                _liveHistory.value = historyBuffer.snapshot()
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

            followAppIntoGame()

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

                historyBuffer.addFrames(
                    SystemClock.elapsedRealtime(),
                    fpsState.fps,
                    fpsState.frameTimeMs
                )
                _liveHistory.value = historyBuffer.snapshot()
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000L

            // A fresh start wants a number now: the first poll sets the
            // baselines and the second one answers. The short wait is used
            // once, right after the measurement was turned on.
            val fastRetry = freshStart
            freshStart = false
            val wait = if (fastRetry) {
                150L
            } else {
                // The gap between two polls is never shorter than the gap the
                // shell needs to breathe. A poll that asks SurfaceFlinger for
                // the counters of the whole device takes a moment, and asking
                // again before the last answer has been read keeps the
                // compositor busy - which the game feels as a stutter.
                val interval = settings.sampleIntervalMs.value
                    .coerceAtLeast(MIN_POLL_GAP_MS)
                (interval - elapsedMs).coerceAtLeast(60L)
            }

            // The wait ends early when the measurement is turned on again.
            val marker = wakeUp.value
            withTimeoutOrNull(wait) {
                wakeUp.first { it != marker }
            }
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
            // The app reads what it may read by itself on every tick, whatever
            // the shell says. The shell answers most of it, and the app covers
            // the rest - the clock of the processor, the temperature of the
            // chip, the load of the graphics unit. A reading that could not be
            // made stays missing.
            basicSample = runCatching { basicCollector()?.sample() }.getOrNull()
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
        // A reading the app made by itself is a real reading. The metric is
        // then available, and the screen says where the number came from
        // instead of telling the user to install Shizuku for a number that is
        // already on the screen.
        val basic = basicSample
        basicFilledMetrics(basic).forEach { metric ->
            if (availability[metric] != MetricAvailability.AVAILABLE) {
                availability[metric] = MetricAvailability.AVAILABLE
                // The load of the processor in the basic mode is the place of
                // the clock of every core between its lowest and its highest.
                // The screen says so, so the number is read for what it is.
                sources[metric] = if (metric == SystemMetric.CPU_PERCENT) {
                    "clock of the processor (app read)"
                } else {
                    "app's own reading"
                }
            }
        }
        val basicStart = basicStart
        val merged = SystemMetrics(
            cpuPercent = cpuPercent ?: basic?.cpuPercent,
            cpuFreqMHz = privileged.cpuFreqMHz ?: basic?.cpuFreqMhz,
            gpuName = privileged.gpuName,
            gpuFreqMHz = privileged.gpuFreqMHz ?: basic?.gpuClockMhz,
            batteryLevel = native.batteryLevel,
            batteryTempC = native.batteryTempC,
            powerWatts = native.powerWatts,
            ramUsedGb = native.ramUsedGb,
            ramTotalGb = native.ramTotalGb,
            ramPercent = native.ramPercent,
            batteryCurrentMa = native.batteryCurrentMa,
            batteryVoltageMv = native.batteryVoltageMv,
            batteryCycles = native.batteryCycles,
            thermalMaxC = privileged.thermalMaxC ?: basic?.cpuTempC,
            cpuTempC = privileged.cpuTempC ?: basic?.cpuTempC,
            gpuPercent = privileged.gpuPercent ?: basic?.gpuPercent,
            cpuGroups = mergedCpuGroups(privileged.cpuGroups, basic, basicStart),
            cpuCoreLoadPct = privileged.cpuCoreLoadPct
                .takeIf { list -> list.any { it != null } }
                ?: basic?.perCoreLoadPct.orEmpty(),
            cpuCoreFreqMHz = privileged.cpuCoreFreqMHz
                .takeIf { list -> list.any { it != null } }
                ?: basic?.perCoreFreqMhz.orEmpty(),
            ddrFreqMHz = privileged.ddrFreqMHz,
            threadLoads = privileged.threadLoads,
            thermalStatus = privileged.thermalStatus,
            platform = privileged.platform ?: basicStart?.platform,
            performanceProfile = privileged.performanceProfile ?: basicStart?.profile,
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

        historyBuffer.addDevice(
            SystemClock.elapsedRealtime(),
            merged.deviceTemperatureC(),
            merged.ramPercent,
            merged.gpuPercent
        )
        _liveHistory.value = historyBuffer.snapshot()
    }

    /**
     * The clusters of the processor, as the app read them itself.
     *
     * The names are the same as the ones of the privileged shell, so a run
     * measured in the basic mode and a run measured with the shell show the
     * same chart.
     */
    private fun basicCpuGroups(
        sample: BasicCollector.Sample?,
        start: BasicCollector.SessionStart?
    ): List<CpuGroupMetric> {
        val clusters = start?.clusters.orEmpty()
        if (clusters.isEmpty()) return emptyList()
        return clusters.mapIndexed { index, cluster ->
            CpuGroupMetric(
                name = cluster.name,
                percent = sample?.clusterLoadPct?.getOrNull(index),
                freqMHz = sample?.clusterFreqMhz?.getOrNull(index)
            )
        }
    }

    /**
     * The clusters of the processor, with the reading of the shell first.
     *
     * A cluster that the shell could not measure takes the reading the app
     * made by itself, if there is one. The two are matched by their name and
     * not by their place: the shell sorts the clusters by their clock and the
     * app keeps them in the order of the cores.
     */
    private fun mergedCpuGroups(
        privileged: List<CpuGroupMetric>,
        sample: BasicCollector.Sample?,
        start: BasicCollector.SessionStart?
    ): List<CpuGroupMetric> {
        val basic = basicCpuGroups(sample, start)
        if (privileged.isEmpty()) return basic
        if (basic.isEmpty()) return privileged
        return privileged.map { group ->
            val other = basic.firstOrNull { it.name == group.name }
            group.copy(
                percent = group.percent ?: other?.percent,
                freqMHz = group.freqMHz ?: other?.freqMHz
            )
        }
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
        // from getprop ro.board.platform on the privileged Shizuku shell, and from
        // the same property read by the app itself when there is no shell.
        val kernelPlatform = _system.value.platform
        val platform = kernelPlatform?.takeIf { it.isNotBlank() }?.uppercase()
            ?: basicStart?.platform
        val profile = _system.value.performanceProfile?.takeIf { it.isNotBlank() }
            ?: basicStart?.profile
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
        // What the device is, read at the moment the run starts. The shell
        // answers this better, but a run without Shizuku still knows the
        // platform of its chip and the clusters of its processor.
        basicStart = runCatching { basicCollector()?.sessionStart() }.getOrNull()
        // The Record button is tapped inside FrameScope, so the foreground app at
        // that moment is FrameScope, not the game the run belongs to. The sampler
        // remembers the last real game while FrameScope is in front; use it for
        // the session name and let the samples switch app if the player moves.
        val current = _foregroundApp.value.orEmpty()
        val own = com.framescope.monitor.BuildConfig.APPLICATION_ID
        val remembered = lastMeasuredPackage.value.orEmpty()
        // The app in front is the app the run belongs to. A window that floats
        // on top of the game - a video in picture-in-picture - is never the
        // game, and neither is the launcher, the keyboard or the system bars.
        val target = if (current.isNotBlank() && current != own &&
            !Parsers.isForegroundNoise(current)
        ) {
            current
        } else {
            remembered.takeIf { it.isNotBlank() && it != own &&
                !Parsers.isForegroundNoise(it) } ?: current
        }
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
                    profile = fresh.performanceProfile,
                    mode = currentModeLine()
                )
            }
        }
        // A recording is its own heat story: start the peak from the temperature at
        // the moment Record was pressed, not from a hot session earlier today.
        val atStart = _system.value.deviceTemperatureC()
        _system.value = _system.value.copy(tempPeakC = atStart, tempCooledC = 0f.takeIf { atStart != null })
        _recording.value = true
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        _live.value = LiveStats()
    }

    /** Stop pressed by the user. Nothing starts a recording except the button. */
    fun stopRecordingManually(): SessionRecord? = stopRecording()

    /**
     * The run follows the app the user actually switched into.
     *
     * Record is often pressed a moment before the game takes the screen: the
     * player taps Record in FrameScope and then switches. For the first
     * twenty seconds of a run the name of the app follows the app that comes
     * to the front, so the report names the game and not the app that was in
     * front when the button was pressed.
     *
     * A floating window never changes the name of the run: only an app that
     * takes the screen does.
     */
    private fun followAppIntoGame() {
        if (!sessionLogger.isRecording) return
        if (recordingStartedAtMs == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - recordingStartedAtMs
        if (elapsed > FOLLOW_APP_WINDOW_MS) return
        val candidate = _foregroundApp.value?.trim().orEmpty()
        if (candidate.isBlank()) return
        if (candidate == com.framescope.monitor.BuildConfig.APPLICATION_ID) return
        if (Parsers.isForegroundNoise(candidate)) return
        sessionLogger.retarget(candidate, appLabel(candidate))
    }

    fun stopRecording(): SessionRecord? {
        if (!_recording.value) return null
        _recording.value = false
        val record = sessionLogger.stop(settings.fpsThreshold.value, sessionShellLogs())
        _live.value = LiveStats()
        return record
    }

    /**
     * The lines that were captured the moment Record was pressed.
     *
     * The first line always says how the run was measured: the privileged
     * shell answered, or the app read what it could by itself. A run that
     * says nothing was captured looks like a run that was not measured, and
     * it was.
     */
    private fun telemetryHeaderLine(): String = buildString {
        appendLine(currentModeLine())
        val detail = _system.value.telemetryDetail.trim()
        if (ShellLogs.isRealIdentity(detail)) appendLine(detail)
    }.trim()

    /**
     * The line that says which of the two ways the run was measured.
     *
     * The privileged shell reads the hardware as the device itself reads it.
     * Without it the app reads what the system lets it read, and the line
     * says so instead of leaving a gap.
     */
    private fun currentModeLine(): String {
        val status = hub.status.value
        val shell = hub.telemetryShell.value
        // Both the Shizuku UserService and the local ADB bridge are a shell of
        // uid=2000, so both answer the question "how was this run measured".
        val privileged = shell == "Shizuku" || shell == "Local ADB bridge" ||
            (status.state == AccessState.READY &&
                (status.shellSource == "Shizuku" || status.shellSource == "Local ADB bridge"))
        val start = basicStart
        return telemetryModeLine(
            shizukuActive = privileged,
            platform = start?.platform,
            clusters = start?.clusters?.map { it.name }.orEmpty()
        )
    }

    /**
     * A short, real collector snapshot stored with the run for its Shell Logs card.
     *
     * Every section is printed only when the shell really answered it. A
     * heading with "--" under it is a fake reading, so it is left out.
     */
    private fun sessionShellLogs(): String = buildString {
        appendLine("captured_from=${lastMeasuredPackage.value ?: "(none)"} " +
            "at=${android.os.SystemClock.uptimeMillis()}")
        section("telemetry", startTelemetryRaw.take(4_000))
        section("window manager", lastForegroundOutput().take(3_000))
        section("frame counters", lastCounterDump().take(6_000))
        hub.lastError.value?.takeIf { it.isNotBlank() }?.let {
            appendLine("--- last collector error ---")
            appendLine(it.trim())
        }
    }.trimEnd()

    /** A section of the log is only printed when it carries a real reading. */
    private fun StringBuilder.section(title: String, body: String) {
        val text = body.trim()
        if (ShellLogs.isPlaceholder(text)) return
        appendLine("--- $title ---")
        appendLine(text)
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
        /**
         * The longest one frame-rate sample may take.
         *
         * It has to cover the slowest source inside it - the counters wait up to
         * four seconds for SurfaceFlinger - or the sample is cancelled before the
         * source that works for a game gets its turn. It does not have to be
         * twelve seconds: a poll that hangs for that long leaves the pill on
         * "--" for that long and keeps the shell busy while the game is played.
         */
        private const val SAMPLE_TIMEOUT_MS = 5_000L

        /**
         * The smallest gap between two frame-rate polls.
         *
         * The setting of the user is honoured above this. Below it, the polls
         * come so fast that the shell is asked for the counters of the whole
         * device more often than the counters can grow into a window - and
         * the compositor is kept busy while the game is played.
         */
        private const val MIN_POLL_GAP_MS = 800

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

        /**
         * How long a run keeps following the app that comes to the front.
         *
         * Record is often pressed a moment before the game takes the screen.
         * For the first twenty seconds the name of the run follows the app in
         * front, so the report names the game and not the app that was in front
         * when the button was pressed.
         */
        private const val FOLLOW_APP_WINDOW_MS = 20_000L
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
