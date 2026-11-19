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
 * 1. SurfaceFlinger's frame counters for the app, which count the frames it presented.
 * 2. The presentation timestamps of that very layer, when the build still keeps them.
 *    They are the frames themselves, not a count between two clock readings, and they
 *    are used whenever they are there - this is the source a desktop frame meter reads.
 * 3. The app's own graphics pipeline, which only describes that app's UI thread.
 */
class FpsSampler(
    private val hub: ShellHub,
    var context: Context? = null
) {

    private val _state = MutableStateFlow(FpsState())
    val state: StateFlow<FpsState> = _state.asStateFlow()

    private val detector = ForegroundDetector(hub)

    /**
     * The last answer about the app in front, and when it was taken.
     *
     * Asking the window manager is a privileged `dumpsys` call. Doing it on
     * every poll would cost as much as the measurement itself, so a known
     * answer is reused for a short while.
     */
    private var lastDetection: ForegroundDetector.Detection? = null
    private var lastDetectionAtMs = 0L

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

    /**
     * The last app that was measured.
     *
     * FrameScope itself is never measured, so this is the app that was in front
     * of the user before the app was opened - the game, usually. The diagnostics
     * need that name to ask SurfaceFlinger about the right layers.
     */
    private val _lastMeasuredPackage = MutableStateFlow<String?>(null)
    val lastMeasuredPackage: StateFlow<String?> = _lastMeasuredPackage.asStateFlow()

    /**
     * The frame counters of SurfaceFlinger.
     *
     * This is the source that works for games: a game draws into a buffer
     * queue that keeps no frame timeline, so its presentation timestamps are
     * empty, but SurfaceFlinger still counts the frames it presents.
     */
    private val counters = FpsPollingEngine(hub, ownPackage)

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
        val nowMs = SystemClock.elapsedRealtime()
        val cachedDetection = lastDetection
        val detection = if (
            cachedDetection != null &&
            cachedDetection.known &&
            nowMs - lastDetectionAtMs < FOREGROUND_TTL_MS
        ) {
            cachedDetection
        } else {
            detector.detect().also {
                lastDetection = it
                lastDetectionAtMs = nowMs
            }
        }

        foregroundSource = detection.source
        foregroundConfident = detection.confident

        val pkg = detection.packageName
        if (pkg != foregroundApp) {
            // A different app is in front. Everything measured so far belonged to the
            // old one, so the counters start again.
            onTargetChanged(pkg)
        }
        foregroundApp = pkg
        if (!pkg.isNullOrBlank() && pkg != ownPackage) {
            _lastMeasuredPackage.value = pkg
        }

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

        // 1. Primary: the frames SurfaceFlinger itself counted for the app. This
        // is the only source that also works for a game: a game renders into a
        // buffer queue that keeps no frame timeline, but SurfaceFlinger still
        // counts every frame it presents.
        val fromCounters = sampleFromCounters(pkg, targetFps, nowUptime)
        if (fromCounters != null) return fromCounters

        // 2. The presentation timestamps of a layer of the app. This carries the
        // per-frame detail - the slowest frames of the window are only visible
        // here - and is kept as the second source.
        val fromLatency = sampleFromLatency(pkg, targetFps, nowUptime)
        if (fromLatency != null) return fromLatency

        // 3. The layer that is on the screen right now. A game names its surface
        // after its engine and not after its package, so the layer of the game
        // cannot always be found by name - but it is the layer that is active.
        val fromActive = sampleFromActiveLayer(pkg, targetFps, nowUptime)
        if (fromActive != null) return fromActive

        // 4. Fallback: the app's own graphics pipeline.
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
     * The primary provider: the frames SurfaceFlinger counted for the app.
     *
     * The counters are cumulative, so this is the growth over a one second
     * window divided by the length of that window. The window is a second and
     * not the gap between two polls: a game at 120 fps presents 48 frames in
     * 400 ms, and one dump that arrives late moves that rate by several
     * frames, while over a second it moves it by less than one.
     *
     * Once the counters name the layer, its own presentation timestamps are
     * asked for. When the build keeps them, they are the exact frames and
     * they answer first; when it does not - Android is deprecating the frame
     * tracker - the counters are the whole answer. A screen that is standing
     * still presents nothing, and there the display refresh rate is shown
     * instead of a zero that would look like a broken app.
     */
    private suspend fun sampleFromCounters(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        val tick = counters.tick(pkg, refreshRateHz()) ?: return null
        measuredLayer = tick.layer

        val windowSeconds = if (tick.windowNs > 0L) tick.windowNs / 1_000_000_000.0 else 0.0
        if (windowSeconds <= 0.0) return null

        // A screen that is standing still presents no frames at all, and a zero
        // would look like a broken measurement. The display is still refreshing,
        // so that is what is shown, named as what it is.
        val idle = tick.staticScreen
        val fps: Double = if (idle) {
            (tick.displayRefreshRate ?: refreshRateHz()?.toInt())?.toDouble() ?: return null
        } else {
            tick.fps
        }
        if (fps <= 0.0) return null
        // When the layer named its display rate, the counters already clamped
        // the growth to it - and that rate is bucketed in 30 Hz steps, so a
        // 165 Hz panel reads as 180 and must not be judged against 165.
        if (!idle && tick.displayRefreshRate == null && !isPlausibleFps(fps.toFloat())) {
            return null
        }

        // The exact frame times of the layer the counters picked. They are the
        // frames themselves, so they are preferred over the count as soon as
        // two dumps of them exist.
        if (!idle) {
            val exact = sampleExactLatency(pkg, tick.layer, targetFps)
            if (exact != null) return exact
        }

        activeSource = FpsSourceKind.TIMESTATS

        // The counters are described by their own report: a SurfaceFlinger
        // dump is hundreds of lines, and putting it in a StateFlow on every
        // poll would cost more than the measurement did.
        _reports.value = retainReport(
            _reports.value,
            pkg,
            buildString {
                appendLine("app: $pkg")
                appendLine("source: SurfaceFlinger counters")
                appendLine("layer: ${tick.layer}")
                appendLine("frames in this window: ${tick.frames}")
                appendLine("janky frames: ${tick.janky}")
                appendLine("window: ${(windowSeconds * 1000.0).toLong()} ms")
                tick.renderRate?.let { appendLine("render rate: $it fps") }
                tick.displayRefreshRate?.let { appendLine("display refresh rate: $it Hz") }
            }
        )

        publish(
            fps = fps.toFloat(),
            frameTimeMs = (1000.0 / fps).toFloat(),
            source = if (idle) {
                "display refresh (nothing is drawing)"
            } else {
                "SurfaceFlinger counters"
            },
            kind = FpsSourceKind.TIMESTATS,
            layer = tick.layer,
            totalFrames = tick.frames,
            jankFrames = tick.janky,
            sampleAgeMs = 0L,
            windowMs = (windowSeconds * 1000.0).toLong(),
            appPackage = pkg,
            nowUptime = nowUptime
        )
        return _state.value
    }

    /**
     * The exact presentation times of one layer.
     *
     * `dumpsys SurfaceFlinger --latency <layer>` answers with the timestamps
     * SurfaceFlinger recorded for every frame the layer presented. Nothing has
     * to be divided by a clock the two processes share, so a slow dump cannot
     * move the number - the only thing a desktop frame meter does differently
     * is draw it.
     *
     * Nothing is published when the layer has no timestamps: a game on a build
     * that has deprecated the frame tracker answers with nothing, and the
     * counters are then the only source.
     */
    private suspend fun sampleExactLatency(
        pkg: String,
        layer: String,
        targetFps: Int
    ): FpsState? {
        val dump = hub.execOrNull(
            ShellCommands.surfaceLatencyOfLayer(layer),
            EXACT_LATENCY_TIMEOUT_MS
        )?.out.orEmpty()
        if (dump.isBlank()) return null

        val sample = parseLayerLatencies("@@@$layer\n$dump").firstOrNull() ?: return null
        if (sample.presents.size < MIN_EXACT_FRAMES) return null

        // The presentation times are on CLOCK_MONOTONIC. The app asks about
        // both clocks it can compare with, so a build that reports the other
        // one is not mistaken for a layer that stopped drawing.
        val uptime = SystemClock.uptimeNanos()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val newest = sample.presents.last()
        val fresh = (uptime - newest) in 0L..EXACT_MAX_AGE_NS ||
            (elapsed - newest) in 0L..EXACT_MAX_AGE_NS
        if (!fresh) return null

        latencyCounter.frameBudgetNs = frameBudgetNs(targetFps)
        val result = latencyCounter.onSample(layer, sample.presents, uptime)
        val fps = result.recentFps ?: return null
        if (!isPlausibleFps(fps)) return null

        activeSource = FpsSourceKind.SURFACE_FLAINGER_LATENCY
        measuredLayer = layer

        publish(
            fps = fps,
            frameTimeMs = result.frameTimeMs ?: (1000f / fps),
            source = "SurfaceFlinger frame times",
            kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
            layer = layer,
            totalFrames = latencyCounter.totalUniqueFrames,
            jankFrames = latencyCounter.totalJankFrames,
            sampleAgeMs = result.sampleAgeMs,
            windowMs = result.windowMs,
            low1PercentFps = latencyCounter.low1PercentFps(),
            low5PercentFps = latencyCounter.low5PercentFps(),
            appPackage = pkg,
            nowUptime = uptime
        )
        return _state.value
    }

    /**
     * True when a frame rate can belong to this screen.
     *
     * A screen cannot present more frames than it refreshes, so a number above
     * that is a counter that was read wrong and not a frame rate.
     */
    private fun isPlausibleFps(fps: Float): Boolean {
        if (fps <= 0f) return false
        val ceiling = (refreshRateHz() ?: 240f) * 1.05f
        return fps <= ceiling
    }

    /** The refresh rate of the display that is on, when the system can tell. */
    private fun refreshRateHz(): Float? {
        val ctx = context ?: return null
        val collectors = NativeCollectors(ctx.applicationContext)
        val rate = collectors.refreshRate()
        return rate.value
    }

    /**
     * The layer that is on the screen right now.
     *
     * SurfaceFlinger answers with the active layer when it is asked without a
     * name, which is the app the user is looking at - the game, when a game is
     * in front. This is the only probe that finds a game whose surface carries
     * no package name at all.
     */
    private suspend fun sampleFromActiveLayer(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        val dump = hub.execOrNull(ShellCommands.surfaceLatencyDefault(), 3_000)?.out.orEmpty()
        if (dump.isBlank()) return null

        val (_, presents) = Parsers.parseLatency(dump)
        if (presents.size < 4) return null

        // The same arithmetic every other source uses: the mean interval
        // between the frames is the frame time, and the jank is every interval
        // that misses the budget.
        val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
        val stats = computeFrameStats(deltas) ?: return null
        val fps = stats.fps
        if (!isPlausibleFps(fps)) return null

        activeSource = FpsSourceKind.SURFACE_FLAINGER_LATENCY
        measuredLayer = "the layer that is on the screen"

        latencyCounter.frameBudgetNs = frameBudgetNs(targetFps)
        val result = latencyCounter.onSample("active", presents, nowUptime)

        publish(
            fps = fps,
            frameTimeMs = stats.frameTimeMs,
            source = "SurfaceFlinger (the layer on the screen)",
            kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
            layer = "active",
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
        var dump = hub.execOrNull(
            ShellCommands.surfaceLatencyOfLayers(pkg),
            LATENCY_TIMEOUT_MS
        )?.out.orEmpty()
        var samples = parseLayerLatencies(dump)

        // No layer of the app answered. A game can render into a surface that does
        // not carry its package name, so the newest layers are checked as well and
        // the one that drew last wins.
        var fallbackDump = ""
        if (samples.isEmpty()) {
            fallbackDump = hub.execOrNull(
                ShellCommands.surfaceLatencyOfLayers(pkg, fallback = true),
                LATENCY_TIMEOUT_MS
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
        if (fps == null || !isPlausibleFps(fps)) {
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
        // The counters are cumulative per layer, so a new app starts a new
        // baseline instead of being credited with the previous app's frames.
        counters.reset()
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

        /**
         * How long a known app in front is trusted.
         *
         * The privileged lookup is a `dumpsys window`, which costs more than the
         * measurement it enables. An app switch is visible within this window,
         * and the frame counters are attributed per layer anyway.
         */
        private const val FOREGROUND_TTL_MS = 1_000L

        /** How long the layer sweep may run in total. */
        private const val SWEEP_BUDGET_MS = 3_000L

        /**
         * How long the exact timestamps of one known layer may take.
         *
         * That is one small dump, not a sweep, so it has to be quick: it runs
         * on every poll, after the counters have already named the layer.
         */
        private const val EXACT_LATENCY_TIMEOUT_MS = 1_200L

        /** Fewer frames than this say nothing about a frame rate. */
        private const val MIN_EXACT_FRAMES = 8

        /**
         * How old the newest frame of a layer may be to still be its meter.
         *
         * A layer that stopped drawing half a second ago is not the surface
         * the user is looking at, and its last few frames are not a live rate.
         */
        private const val EXACT_MAX_AGE_NS = 500_000_000L

        /**
         * How long the latency dump of a package's layers may take.
         *
         * It used to be nine seconds, which was longer than the whole sample
         * was allowed to take - the sample was cancelled before it arrived.
         * The counters answer first now, so this only matters for a phone where
         * the counters are missing.
         */
        private const val LATENCY_TIMEOUT_MS = 4_000L

        /** How long the app waits before it sweeps the layers again. */
        private const val SWEEP_COOLDOWN_MS = 4_000L
    }
}
