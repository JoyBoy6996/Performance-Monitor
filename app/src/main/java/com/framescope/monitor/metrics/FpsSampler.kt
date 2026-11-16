package com.framescope.monitor.metrics

import android.content.Context
import android.os.SystemClock
import com.framescope.monitor.core.FpsSourceKind
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.shell.AccessState
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Samples real frame timings from SurfaceFlinger and system graphics pipelines.
 *
 * The measurement has one rule above all others: a number is only ever published when it
 * can be attributed to the app that is in front of the user. When that cannot be
 * established the answer is "no data", with the reason, rather than a plausible looking
 * number that belongs to some other surface.
 *
 * The providers are tried in order of how well they can be attributed, and each one keeps
 * its own state:
 *
 * 1. Presentation timestamps of a layer that was verified to belong to the app.
 * 2. SurfaceFlinger's frame counters for the same app, which give an interval estimate.
 * 3. The app's own graphics pipeline, which only describes that app's UI thread.
 */
class FpsSampler(
    private val hub: ShellHub,
    var context: Context? = null
) {

    private val _state = MutableStateFlow(FpsState())
    val state: StateFlow<FpsState> = _state.asStateFlow()

    private val detector = ForegroundDetector(hub)

    /** The app in front of the user, as far as it is known. */
    @Volatile
    var foregroundApp: String? = null
        private set

    /** The layer the current reading was taken from. */
    @Volatile
    var measuredLayer: String? = null
        private set

    /**
     * The package of FrameScope itself.
     *
     * It is read from the build so the name is never written twice.
     */
    private val ownPackage: String = runCatching {
        Class.forName("com.framescope.monitor.BuildConfig")
            .getField("APPLICATION_ID")
            .get(null) as String
    }.getOrDefault("com.framescope.monitor")

    /** Where the foreground answer came from. */
    @Volatile
    var foregroundSource: ForegroundDetector.Source = ForegroundDetector.Source.NONE
        private set

    /** Whether the foreground answer is known to be exact. */
    @Volatile
    var foregroundConfident: Boolean = false
        private set

    /** Why the current target was chosen, or why none could be chosen. */
    @Volatile
    var targetReason: String = ""
        private set

    private val _reports = MutableStateFlow<Map<String, String>>(emptyMap())
    val reports: StateFlow<Map<String, String>> = _reports.asStateFlow()

    /** Counts the frames of the primary provider. */
    private val latencyCounter = FrameCounter(DEFAULT_FRAME_BUDGET_NS)

    /** Counts the frames of the gfxinfo provider. */
    private val gfxCounter = FrameCounter(DEFAULT_FRAME_BUDGET_NS)

    /** Which provider produced the reading that is on screen. */
    private var activeSource: FpsSourceKind = FpsSourceKind.NONE

    /** Set when a layer was found but has not drawn two frames yet. */
    private var pendingWarmUpReason: String? = null

    /** When the layer sweep may run again. */
    private var sweepNextAttemptAt = 0L

    private val history = ArrayDeque<Float>()

    // --- TimeStats provider state -------------------------------------------------

    private var timeStatsEnabled = false
    private var timeStatsEnableFailures = 0
    private var timeStatsFailures = 0
    private var timeStatsNextAttemptAt = 0L
    private var timeStatsBase = LinkedHashMap<String, TimeStats.Reading>()
    private var timeStatsAt = 0L
    private var timeStatsFrames = 0L
    private var timeStatsJank = 0L

    // --- gfxinfo provider state ----------------------------------------------------

    private var gfxNextAttemptAt = 0L
    private var gfxFailures = 0

    init {
        detector.context = context
    }

    fun reset() {
        latencyCounter.reset()
        gfxCounter.reset()
        history.clear()
        activeSource = FpsSourceKind.NONE
        measuredLayer = null
        _state.value = FpsState()
    }

    /**
     * Publishes the absence of a measurement, and why.
     *
     * Used when the measurement cannot even be attempted, so the interface can say
     * "this needs Shizuku" instead of showing an empty reading with no explanation.
     */
    fun reportUnavailable(reason: String, availability: MetricAvailability) {
        _state.value = FpsState(
            fps = 0f,
            frameTimeMs = 0f,
            history = history.toList(),
            totalFrames = latencyCounter.totalUniqueFrames.toInt(),
            jankFrames = latencyCounter.totalJankFrames.toInt(),
            source = "no source",
            surface = null,
            valid = false,
            reason = reason,
            availability = availability,
            measuredAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            sampleAgeMs = 0L,
            measurementWindowMs = 0L,
            sourceKind = FpsSourceKind.NONE
        )
    }

    /**
     * Takes one measurement.
     *
     * @param targetFps the refresh rate the frame budget is derived from
     */
    suspend fun sample(targetFps: Int = 60): FpsState {
        val nowUptime = SystemClock.uptimeNanos()
        detector.context = context
        val detection = detector.detect()

        foregroundSource = detection.source
        foregroundConfident = detection.confident

        val pkg = detection.packageName
        if (pkg != foregroundApp) {
            // A different app is in front. Everything measured so far belonged to the
            // old one, so the counters start again.
            onTargetChanged(pkg)
        }
        foregroundApp = pkg

        if (pkg.isNullOrBlank()) {
            return publishUnavailable(
                source = "no source",
                kind = FpsSourceKind.NONE,
                reason = detection.detail ?: "Waiting for a foreground app",
                availability = if (detection.detail?.contains("Shizuku") == true) {
                    MetricAvailability.SHIZUKU_REQUIRED
                } else {
                    MetricAvailability.PERMISSION_REQUIRED
                },
                nowUptime = nowUptime
            )
        }

        // The app in front is known, but the frame timing needs SurfaceFlinger, which
        // needs a privileged shell. The command fails immediately when there is none, so
        // the app name stays right and the frame rate says why it is missing.
        if (hub.status.value.state != AccessState.READY) {
            return publishUnavailable(
                source = "no source",
                kind = FpsSourceKind.NONE,
                reason = "Shizuku is not connected, so the frame rate of $pkg cannot be measured",
                availability = MetricAvailability.SHIZUKU_REQUIRED,
                nowUptime = nowUptime
            )
        }

        // FrameScope cannot measure itself: its own layers have no frame timeline
        // that belongs to a game, and walking them fills the report with noise.
        // The reading of the app in front of it is what the user wants.
        if (pkg == ownPackage) {
            return publishUnavailable(
                source = "no source",
                kind = FpsSourceKind.NONE,
                reason = "FrameScope is in front - open the game to measure it",
                availability = MetricAvailability.UNSUPPORTED,
                nowUptime = nowUptime
            )
        }

        pendingWarmUpReason = null

        // 1. Primary: presentation timestamps of a verified layer.
        val fromLatency = sampleFromLatency(pkg, targetFps, nowUptime)
        if (fromLatency != null) return fromLatency

        // 2. Fallback: SurfaceFlinger's frame counters for the same app.
        val fromTimeStats = sampleFromTimeStats(pkg, targetFps, nowUptime)
        if (fromTimeStats != null) return fromTimeStats

        // 3. Fallback: the app's own graphics pipeline.
        val fromGfx = sampleFromGfxInfo(pkg, targetFps, nowUptime)
        if (fromGfx != null) return fromGfx

        // A layer was found and it is still warming up - that is a better answer
        // than "no source".
        val warmUp = pendingWarmUpReason
        if (warmUp != null) {
            return publishUnavailable(
                source = "SurfaceFlinger latency",
                kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
                reason = warmUp,
                availability = MetricAvailability.WARMING_UP,
                nowUptime = nowUptime
            )
        }

        return publishUnavailable(
            source = "no source",
            kind = FpsSourceKind.NONE,
            reason = "No frame source could be attributed to $pkg",
            availability = MetricAvailability.UNSUPPORTED,
            nowUptime = nowUptime
        )
    }

    /**
     * Asks every layer for its latency on its own.
     *
     * The layers of the app in front come first. A game can own a dozen of them
     * and only one is drawing, so the newest ones are asked first and the rest
     * is left out. When the app's own layers cannot be found, the newest layers
     * of the device are asked instead - the surface that drew last belongs to
     * the app in front.
     */
    private suspend fun sampleLayersOneByOne(pkg: String): List<LayerSample> {
        val list = hub.execOrNull(ShellCommands.surfaceList(), 3_000)?.out.orEmpty()
        val names = list.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return emptyList()

        val own = names.filter { it.contains(pkg, ignoreCase = true) }
        val candidates = if (own.isNotEmpty()) own.takeLast(6) else names.takeLast(10)

        // The whole sweep has a budget. A layer that hangs the shell must not
        // hold the measurement hostage, so the sweep stops when it is used up.
        val deadline = SystemClock.elapsedRealtime() + SWEEP_BUDGET_MS
        val samples = mutableListOf<LayerSample>()
        for (name in candidates) {
            if (SystemClock.elapsedRealtime() > deadline) break

            val dump = hub.execOrNull(ShellCommands.surfaceLatencyOfLayer(name), 900)?.out.orEmpty()
            if (dump.isNotBlank()) samples += parseLayerLatencies("@@@$name\n$dump")

            // Some builds only answer to the layer name without its instance
            // number, so that one is asked as well.
            val base = name.substringBefore('#')
            if (base != name && base.isNotBlank() &&
                SystemClock.elapsedRealtime() <= deadline
            ) {
                val baseDump =
                    hub.execOrNull(ShellCommands.surfaceLatencyOfLayer(base), 900)?.out.orEmpty()
                if (baseDump.isNotBlank()) {
                    samples += parseLayerLatencies("@@@$base\n$baseDump")
                }
            }
        }
        return samples
    }

    /**
     * The primary provider: the presentation timestamps of one verified layer.
     *
     * Returns null when no layer of the app could be verified, so that the caller can try
     * the next provider. When a layer was verified but has not drawn enough frames yet,
     * the answer is a warming up state rather than a fallback reading.
     */
    private suspend fun sampleFromLatency(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        // A game owns many surfaces and every one of them is asked for its latency.
        // The command needs room, or the whole dump is lost and the app looks dead.
        var dump = hub.execOrNull(ShellCommands.surfaceLatencyOfLayers(pkg), 9_000)?.out.orEmpty()
        var samples = parseLayerLatencies(dump)

        // No layer of the app answered. A game can render into a surface that does
        // not carry its package name, so the newest layers are checked as well and
        // the one that drew last wins.
        var fallbackDump = ""
        if (samples.isEmpty()) {
            fallbackDump = hub.execOrNull(
                ShellCommands.surfaceLatencyOfLayers(pkg, fallback = true),
                9_000
            )?.out.orEmpty()
            if (fallbackDump.isNotBlank()) {
                samples = parseLayerLatencies(fallbackDump)
            }
        }

        // The shell carries a limited amount of text. A game with many surfaces
        // fills it, and the dump then arrives cut in half - the layers that come
        // last, the ones that are drawing, are the ones that are missing. A cut
        // dump is thrown away and every layer is asked on its own instead.
        val cut = dump.contains("output truncated", ignoreCase = true) ||
            fallbackDump.contains("output truncated", ignoreCase = true)
        if (cut) {
            samples = emptyList()
            dump = "the shell cut this dump short, every layer was asked on its own"
            fallbackDump = ""
        }

        // Nothing answered. Every layer is asked on its own now: a game that owns
        // many surfaces can make one command for all of them too slow, and then
        // the whole dump is lost. One layer at a time gives each of them its own
        // time and keeps the ones that answer.
        if (samples.isEmpty() && SystemClock.elapsedRealtime() >= sweepNextAttemptAt) {
            val oneByOne = sampleLayersOneByOne(pkg)
            sweepNextAttemptAt = SystemClock.elapsedRealtime() + SWEEP_COOLDOWN_MS
            if (oneByOne.isNotEmpty()) {
                samples = oneByOne
                dump = "one layer at a time:\n" + oneByOne.joinToString("\n") { it.name }
            }
        }

        val report = buildProbeReport(pkg, dump) +
            if (fallbackDump.isBlank()) "" else {
                "\nfallback (newest layers):\n" + buildProbeReport(pkg, fallbackDump)
            }
        _reports.value = retainReport(_reports.value, pkg, report)
        val selection = selectVerifiedTarget(samples, pkg, allowUnattributed = true)
        targetReason = selection.reason

        val target = selection.target ?: return null

        // Switching provider starts a new baseline: the counters of one provider say
        // nothing about the frames of another.
        if (activeSource != FpsSourceKind.SURFACE_FLAINGER_LATENCY) {
            latencyCounter.reset(target.name)
        }
        activeSource = FpsSourceKind.SURFACE_FLAINGER_LATENCY
        measuredLayer = target.name

        val budgetNs = frameBudgetNs(targetFps)
        latencyCounter.frameBudgetNs = budgetNs

        val result = latencyCounter.onSample(target.name, target.presents, nowUptime)

        val fps = result.recentFps
        if (fps == null || fps <= 0f) {
            // The layer is verified but has not drawn two frames yet. A game can have
            // a surface that never gets a timestamp row, so the next provider gets
            // its turn before this is called a problem.
            pendingWarmUpReason = "Waiting for ${target.name} to present frames"
            return null
        }

        publish(
            fps = fps,
            frameTimeMs = result.frameTimeMs ?: (1000f / fps),
            source = "SurfaceFlinger latency",
            kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
            layer = target.name,
            totalFrames = latencyCounter.totalUniqueFrames,
            jankFrames = latencyCounter.totalJankFrames,
            sampleAgeMs = latencyCounter.frameAgeMs(nowUptime),
            windowMs = result.windowMs,
            low1PercentFps = latencyCounter.low1PercentFps(),
            low5PercentFps = latencyCounter.low5PercentFps(),
            appPackage = pkg,
            nowUptime = nowUptime
        )
        return _state.value
    }

    /**
     * The counter provider: SurfaceFlinger's own frame counters for the app.
     *
     * These are a count, not a timestamp per frame, so the frame rate is an average over
     * the interval between two dumps. That is a different kind of measurement and it is
     * labelled as one.
     */
    private suspend fun sampleFromTimeStats(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now < timeStatsNextAttemptAt) return null

        if (!timeStatsEnabled) {
            // Only a confirmed enable counts as enabled, and a failure is retried later
            // instead of being remembered as a success.
            val result = hub.execOrNull(ShellCommands.surfaceTimeStatsEnable(), 2_000)
            if (result == null || !result.ok) {
                timeStatsEnableFailures++
                timeStatsNextAttemptAt = now + backoffNanos(timeStatsEnableFailures)
                return null
            }
            timeStatsEnabled = true
            timeStatsEnableFailures = 0
        }

        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsDump(), 6_000)?.out.orEmpty()
        // When no layer carries the package name, every layer is read and the one
        // whose counters grow the fastest is taken as the app in front.
        val attributed = TimeStats.readAll(dump, pkg)
        val readings = if (attributed.isNotEmpty()) attributed else TimeStats.readAllUnfiltered(dump)
        val unattributed = attributed.isEmpty()
        if (readings.isEmpty()) {
            timeStatsNextAttemptAt = now + backoffNanos(++timeStatsFailures)
            return null
        }

        val previous = timeStatsBase.toMap()
        timeStatsBase.clear()
        readings.forEach { timeStatsBase[it.layer] = it }

        if (previous.isEmpty() || timeStatsAt <= 0L) {
            timeStatsAt = now
            return null
        }
        val elapsed = now - timeStatsAt
        timeStatsAt = now
        if (elapsed <= 0L) return null

        var best: TimeStats.Reading? = null
        var growth = 0L
        var jankGrowth = 0L
        val growths = mutableListOf<Long>()
        for (reading in readings) {
            val before = previous[reading.layer] ?: continue
            val grew = reading.frames - before.frames
            if (grew <= 0L) continue
            growths.add(grew)
            if (grew > growth) {
                growth = grew
                best = reading
                jankGrowth = (reading.jankyFrames - before.jankyFrames).coerceAtLeast(0L)
            }
        }

        val reading = best ?: return null

        // Without a package name in the layer name, the layer that grew the most is
        // only believable when it clearly beats the others. Two layers that grew
        // about the same say nothing about which one is in front.
        if (unattributed) {
            val sorted = growths.sortedDescending()
            if (sorted.size > 1 && sorted[1] * 3 / 2 > sorted[0]) {
                timeStatsNextAttemptAt = now + backoffNanos(++timeStatsFailures)
                return null
            }
        }
        if (growth <= 0L) {
            timeStatsNextAttemptAt = now + backoffNanos(++timeStatsFailures)
            return null
        }

        timeStatsFailures = 0
        timeStatsNextAttemptAt = 0L

        val fps = growth.toDouble() * 1_000_000_000.0 / elapsed
        if (fps < 1.0 || fps > 360.0) return null

        timeStatsFrames += growth
        timeStatsJank += jankGrowth.coerceAtLeast(0L)

        activeSource = FpsSourceKind.TIMESTATS
        measuredLayer = reading.layer

        publish(
            fps = fps.toFloat(),
            frameTimeMs = (1000.0 / fps).toFloat(),
            source = "SurfaceFlinger counters",
            kind = FpsSourceKind.TIMESTATS,
            layer = reading.layer,
            totalFrames = timeStatsFrames,
            jankFrames = timeStatsJank,
            sampleAgeMs = (now - timeStatsAt) / 1_000_000L,
            windowMs = elapsed / 1_000_000L,
            nowUptime = nowUptime
        )
        return _state.value
    }

    /**
     * The app's own graphics pipeline.
     *
     * This describes the frames the app's UI thread produced. For an app that draws its
     * interface in the normal way that is the app's frame rate; for a game that renders
     * through its own engine it is not the game's frame rate. The source is named so the
     * user can tell the two apart.
     */
    private suspend fun sampleFromGfxInfo(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now < gfxNextAttemptAt) return null

        val out = hub.execOrNull(ShellCommands.gfxInfo(pkg), 2_000)?.out.orEmpty()
        val timestamps = Parsers.parseGfxFrameStats(out)
        if (timestamps.size < 4) {
            gfxNextAttemptAt = now + backoffNanos(++gfxFailures)
            return null
        }

        gfxFailures = 0
        gfxNextAttemptAt = 0L

        if (activeSource != FpsSourceKind.GFXINFO) {
            gfxCounter.reset(pkg)
        }
        activeSource = FpsSourceKind.GFXINFO
        measuredLayer = pkg
        gfxCounter.frameBudgetNs = frameBudgetNs(targetFps)

        val result = gfxCounter.onSample(pkg, timestamps, nowUptime)
        val fps = result.recentFps
        if (fps == null || fps <= 0f) {
            return publishUnavailable(
                source = "gfxinfo",
                kind = FpsSourceKind.GFXINFO,
                reason = "Waiting for $pkg to report frames",
                availability = MetricAvailability.WARMING_UP,
                nowUptime = nowUptime
            )
        }

        publish(
            fps = fps,
            frameTimeMs = result.frameTimeMs ?: (1000f / fps),
            source = "gfxinfo (app UI thread)",
            kind = FpsSourceKind.GFXINFO,
            layer = pkg,
            totalFrames = gfxCounter.totalUniqueFrames,
            jankFrames = gfxCounter.totalJankFrames,
            sampleAgeMs = gfxCounter.frameAgeMs(nowUptime),
            windowMs = result.windowMs,
            low1PercentFps = gfxCounter.low1PercentFps(),
            low5PercentFps = gfxCounter.low5PercentFps(),
            appPackage = pkg,
            nowUptime = nowUptime
        )
        return _state.value
    }

    /** Starts the counters again because the app in front changed. */
    private fun onTargetChanged(pkg: String?) {
        latencyCounter.reset()
        gfxCounter.reset()
        timeStatsBase.clear()
        timeStatsAt = 0L
        timeStatsFrames = 0L
        timeStatsJank = 0L
        activeSource = FpsSourceKind.NONE
        measuredLayer = null
        history.clear()
        if (pkg != null) {
            _state.value = FpsState(
                availability = MetricAvailability.WARMING_UP,
                reason = "Waiting for $pkg to present frames"
            )
        }
    }

    /** Publishes a measurement. */
    private fun publish(
        fps: Float,
        frameTimeMs: Float,
        source: String,
        kind: FpsSourceKind,
        layer: String?,
        totalFrames: Long,
        jankFrames: Long,
        sampleAgeMs: Long,
        windowMs: Long,
        low1PercentFps: Float? = null,
        low5PercentFps: Float? = null,
        appPackage: String? = null,
        nowUptime: Long
    ) {
        history.addLast(fps)
        while (history.size > HISTORY_SIZE) history.removeFirst()

        _state.value = FpsState(
            fps = fps,
            frameTimeMs = frameTimeMs,
            history = history.toList(),
            totalFrames = totalFrames.toInt(),
            jankFrames = jankFrames.toInt(),
            source = source,
            surface = layer,
            valid = true,
            reason = null,
            availability = MetricAvailability.AVAILABLE,
            measuredAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            sampleAgeMs = sampleAgeMs,
            measurementWindowMs = windowMs,
            sourceKind = kind,
            low1PercentFps = low1PercentFps,
            low5PercentFps = low5PercentFps,
            appPackage = appPackage
        )
    }

    /** Publishes the absence of a measurement, and why. */
    private fun publishUnavailable(
        source: String,
        kind: FpsSourceKind,
        reason: String,
        availability: MetricAvailability,
        nowUptime: Long
    ): FpsState {
        val state = FpsState(
            fps = 0f,
            frameTimeMs = 0f,
            history = history.toList(),
            totalFrames = latencyCounter.totalUniqueFrames.toInt(),
            jankFrames = latencyCounter.totalJankFrames.toInt(),
            source = source,
            surface = null,
            valid = false,
            reason = reason,
            availability = availability,
            measuredAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            sampleAgeMs = 0L,
            measurementWindowMs = 0L,
            sourceKind = kind
        )
        _state.value = state
        return state
    }

    /**
     * The frame budget a frame has to meet to count as smooth.
     *
     * Derived from the refresh rate the user is aiming for, so a 120 Hz panel is judged
     * against 8.3 ms and not against 16.6 ms.
     */
    private fun frameBudgetNs(targetFps: Int): Long {
        val fps = targetFps.takeIf { it > 0 } ?: 60
        return 1_000_000_000L / fps
    }

    /**
     * How long to wait before the next attempt of a provider that keeps failing.
     *
     * The wait grows with every failure and is capped, so a provider that cannot work
     * on this device stops costing a shell round trip on every poll.
     */
    private fun backoffNanos(failures: Int): Long {
        val step = failures.coerceIn(1, 6)
        return BASE_BACKOFF_NS * (1L shl (step - 1))
    }

    companion object {
        private const val HISTORY_SIZE = 60
        private const val BASE_BACKOFF_NS = 2_000_000_000L
        private const val DEFAULT_FRAME_BUDGET_NS = 16_666_666L

        /** How long the layer sweep may run in total. */
        private const val SWEEP_BUDGET_MS = 3_000L

        /** How long the app waits before it sweeps the layers again. */
        private const val SWEEP_COOLDOWN_MS = 4_000L
    }
}
