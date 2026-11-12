package com.framescope.monitor.metrics

import com.framescope.monitor.BuildConfig
import com.framescope.monitor.core.AppSettings
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveStats
import com.framescope.monitor.core.ProtectedApps
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.steadyWindow
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Drives the sampling loop, keeps live stats and records sessions. */
class MetricsEngine(
    private val hub: ShellHub,
    private val settings: AppSettings,
    private val store: SessionStore
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fpsSampler = FpsSampler(hub)
    private val systemSampler = SystemSampler(hub)

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

    /** Plain language explanation of why there is no live data, if any. */
    val diagnostic: StateFlow<String?> = _diagnostic.asStateFlow()

    /** Frame rate per second, the same series the sessions are built from. */
    private val _liveSeries = MutableStateFlow<List<Float>>(emptyList())
    val liveSeries: StateFlow<List<Float>> = _liveSeries.asStateFlow()

    private val _measuredLayer = MutableStateFlow<String?>(null)

    /** The SurfaceFlinger layer that is currently being measured. */
    val measuredLayer: StateFlow<String?> = _measuredLayer.asStateFlow()

    /**
     * What the last sample attempt saw, layer by layer.
     *
     * The report of the last app that was not FrameScope itself is the useful
     * one: opening the app to read it does not throw away the evidence.
     */
    val probeReport: StateFlow<String> = fpsSampler.reports
        .map { reports ->
            val foreign = reports.entries.lastOrNull { it.key != BuildConfig.APPLICATION_ID }
            (foreign ?: reports.entries.lastOrNull())?.value.orEmpty()
        }
        .stateIn(scope, SharingStarted.Eagerly, "")

    private var loop: Job? = null

    // recording state
    private var recordStart = 0L
    private var recordApp: String? = null
    private var fpsSamples = ArrayList<Float>()
    private var frameTimeSamples = ArrayList<Float>()
    private var secondFps = ArrayList<Float>()
    private var secondCpu = ArrayList<Float>()
    private var secondTemp = ArrayList<Float>()
    private var secondFrameTime = ArrayList<Float>()
    private var secondBattery = ArrayList<Float>()
    private var liveSecondFps = ArrayList<Float>()
    private var bucketFps = ArrayList<Float>()
    private var bucketCpu = ArrayList<Float>()
    private var bucketTemp = ArrayList<Float>()
    private var bucketFrameTime = ArrayList<Float>()
    private var bucketBattery = ArrayList<Float>()
    private var lastBucketSecond = -1L
    private var lastAutoApp: String? = null
    private var autoAppSince = 0L
    private var maxTemp = 0f
    private var cpuSum = 0f
    private var cpuCount = 0
    private var maxFrameTime = 0f
    private var sessionFrames = 0L
    private var lastSamplerFrames = 0L
    private var lastSamplerSource: String? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var slowCounter = 0
            while (isActive) {
                val started = System.currentTimeMillis()
                // The master switch: with the measurement off nothing is
                // sampled and the shell is left alone.
                val enabled = settings.measurementEnabled.value
                val fpsState = if (enabled) {
                    runCatching { fpsSampler.sample() }.getOrNull()
                } else {
                    fpsSampler.reset()
                    null
                }
                val currentApp = fpsSampler.foregroundApp
                _foregroundApp.value = currentApp
                _measuredLayer.value = fpsSampler.measuredLayer
                _diagnostic.value = when {
                    !enabled -> null
                    else -> hub.lastError.value
                        ?: fpsState?.reason
                        ?: if (fpsState?.valid == false) "no frame data yet" else null
                }
                // The backend worked once: the phone keeps the key, so the
                // setup must not be asked for again.
                if (hub.status.value.state == AccessState.READY && !settings.setupCompleted.value) {
                    settings.setupCompleted.value = true
                }
                updateProtectedFlag()
                handleAutoRecord(currentApp)

                slowCounter++
                if (slowCounter % SLOW_EVERY == 0) {
                    val metrics = runCatching { systemSampler.sample() }.getOrNull()
                    if (metrics != null) _system.value = metrics
                }

                if (fpsState != null) accumulate(fpsState)

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

    fun resetCounters() {
        fpsSamples.clear()
        frameTimeSamples.clear()
        secondFps.clear()
        secondCpu.clear()
        secondTemp.clear()
        secondFrameTime.clear()
        secondBattery.clear()
        bucketFps.clear()
        bucketCpu.clear()
        bucketTemp.clear()
        bucketFrameTime.clear()
        bucketBattery.clear()
        maxTemp = 0f
        cpuSum = 0f
        cpuCount = 0
        maxFrameTime = 0f
        sessionFrames = 0L
        lastSamplerFrames = 0L
        _live.value = LiveStats()
    }

    fun startRecording() {
        if (_recording.value) return
        resetCounters()
        recordStart = System.currentTimeMillis()
        recordApp = _foregroundApp.value
        _recording.value = true
    }

    fun stopRecording(): SessionRecord? {
        if (!_recording.value) return null
        _recording.value = false
        flushBucket()
        // A run that never produced a usable frame series is noise, not data.
        if (fpsSamples.size < MIN_SAMPLES || secondFps.size < 2) {
            resetCounters()
            return null
        }
        val stats = computeStats(System.currentTimeMillis() - recordStart)
        val threshold = settings.fpsThreshold.value
        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            appPackage = recordApp.orEmpty(),
            appLabel = appLabel(recordApp),
            startedAt = recordStart,
            durationMs = System.currentTimeMillis() - recordStart,
            avgFps = stats.avgFps,
            maxFps = stats.maxFps,
            minFps = stats.minFps,
            low1Percent = stats.low1Percent,
            low5Percent = stats.low5Percent,
            smoothness = stats.smoothness,
            totalFrames = sessionFrames.toInt(),
            jankFrames = stats.jankFrames,
            avgFrameTimeMs = stats.avgFrameTimeMs,
            maxFrameTimeMs = stats.maxFrameTimeMs,
            maxTempC = if (maxTemp > 0f) maxTemp else null,
            avgCpu = if (cpuCount > 0) cpuSum / cpuCount else null,
            fpsSeries = secondFps.toList(),
            cpuSeries = secondCpu.toList(),
            tempSeries = secondTemp.toList(),
            frameTimeSeries = secondFrameTime.toList(),
            batterySeries = secondBattery.toList(),
            longestFrameMs = maxFrameTime,
            dropSeconds = secondFps.steadyWindow().count { it < threshold }
        )
        store.add(record)
        return record
    }

    /**
     * Auto record starts a session whenever a different app comes to the front.
     *
     * A short visit (a notification shade, a screenshot) is not a session, so
     * the app has to stay in front for a moment before the run starts.
     */
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

    private fun accumulate(state: FpsState) {
        if (!state.valid) return
        val metrics = _system.value

        // The per second buckets always run, the live chart needs them even
        // when nothing is being recorded.
        bucketFps.add(state.fps)
        bucketFrameTime.add(state.frameTimeMs)
        metrics.cpuPercent?.let {
            cpuSum += it
            cpuCount++
            bucketCpu.add(it)
        }
        metrics.thermalMaxC?.let {
            if (it > maxTemp) maxTemp = it
            bucketTemp.add(it)
        }
        metrics.batteryLevel?.let { bucketBattery.add(it.toFloat()) }

        if (_recording.value) {
            fpsSamples.add(state.fps)
            frameTimeSamples.add(state.frameTimeMs)
            if (state.frameTimeMs > maxFrameTime) maxFrameTime = state.frameTimeMs
            // The sampler counts the frames it saw. The difference between two
            // readings is how many frames were presented since the last one.
            // A different source counts from its own zero, so the difference
            // would be nonsense until the counter passes the old one.
            if (state.source != lastSamplerSource) {
                lastSamplerSource = state.source
                lastSamplerFrames = 0L
            }
            if (state.totalFrames > lastSamplerFrames) {
                sessionFrames += (state.totalFrames - lastSamplerFrames)
                lastSamplerFrames = state.totalFrames.toLong()
            }
        }

        val nowSecond = System.currentTimeMillis() / 1000
        if (lastBucketSecond < 0) lastBucketSecond = nowSecond
        if (nowSecond - lastBucketSecond >= 1) {
            flushBucket()
            lastBucketSecond = nowSecond
        }
        if (_recording.value && fpsSamples.size % 10 == 0) {
            _live.value = computeStats(System.currentTimeMillis() - recordStart)
        }
    }

    private fun flushBucket() {
        if (bucketFps.isNotEmpty()) {
            val value = bucketFps.average().toFloat()
            liveSecondFps.add(value)
            while (liveSecondFps.size > 240) liveSecondFps.removeAt(0)
            _liveSeries.value = liveSecondFps.toList()
            if (_recording.value) secondFps.add(value)
            bucketFps.clear()
        }
        if (bucketCpu.isNotEmpty()) {
            val value = bucketCpu.average().toFloat()
            if (_recording.value) secondCpu.add(value)
            bucketCpu.clear()
        }
        if (bucketTemp.isNotEmpty()) {
            val value = bucketTemp.max()
            if (_recording.value) secondTemp.add(value)
            bucketTemp.clear()
        }
        if (bucketFrameTime.isNotEmpty()) {
            val value = bucketFrameTime.average().toFloat()
            if (_recording.value) secondFrameTime.add(value)
            bucketFrameTime.clear()
        }
        if (bucketBattery.isNotEmpty()) {
            val value = bucketBattery.min()
            if (_recording.value) secondBattery.add(value)
            bucketBattery.clear()
        }
    }

    private fun computeStats(durationMs: Long): LiveStats {
        if (fpsSamples.isEmpty()) return LiveStats(durationMs = durationMs)
        val sorted = fpsSamples.sorted()
        val avg = fpsSamples.average().toFloat()
        val max = sorted.last()
        val min = sorted.first()
        val low1 = percentile(sorted, 0.01f)
        val low5 = percentile(sorted, 0.05f)
        val threshold = settings.fpsThreshold.value
        // The slower part before the action started is a chosen rate, not a
        // stutter, so the smoothness is judged from the moment the run
        // reached its top rate.
        val steady = fpsSamples.steadyWindow()
        val smooth = if (steady.isEmpty()) {
            0f
        } else {
            steady.count { it >= threshold } * 100f / steady.size
        }
        // Jank is relative to the frame time of this run. A 30 fps game is not
        // a game full of jank, and a 120 Hz panel has a different normal frame
        // time than a 60 Hz one.
        val sortedFrameTimes = frameTimeSamples.sorted()
        val medianFrameTime = sortedFrameTimes[sortedFrameTimes.size / 2]
        val jank = frameTimeSamples.count { it > medianFrameTime * 1.6f }
        return LiveStats(
            avgFps = avg,
            maxFps = max,
            minFps = min,
            low1Percent = low1,
            low5Percent = low5,
            smoothness = smooth,
            totalFrames = sessionFrames.toInt(),
            jankFrames = jank,
            avgFrameTimeMs = if (frameTimeSamples.isEmpty()) 0f else frameTimeSamples.average().toFloat(),
            maxFrameTimeMs = frameTimeSamples.maxOrNull() ?: 0f,
            durationMs = durationMs,
            maxTempC = if (maxTemp > 0f) maxTemp else null,
            avgCpu = if (cpuCount > 0) cpuSum / cpuCount else null
        )
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float {
        if (sorted.isEmpty()) return 0f
        val count = (sorted.size * fraction).toInt().coerceAtLeast(1)
        return sorted.take(count).average().toFloat()
    }

    private fun updateProtectedFlag() {
        val pkg = _foregroundApp.value
        val active = settings.protectBankApps.value &&
            ProtectedApps.isProtected(pkg, settings.protectedPackages())
        if (active != _protectedForeground.value) _protectedForeground.value = active
    }

    private fun appLabel(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "Unknown app"
        val ctx = androidContext ?: return pkg
        return runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    /** Set by the application so the engine can resolve app labels. */
    var androidContext: android.content.Context? = null

    companion object {
        private const val SLOW_EVERY = 3
        private const val MIN_SAMPLES = 10
        private const val MIN_AUTO_RECORD_MS = 3_000L
    }
}
