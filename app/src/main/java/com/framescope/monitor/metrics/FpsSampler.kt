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
     * The counters the device printed the last time it was asked.
     *
     * This is the text that decides the frame rate of a game. When the number
     * is wrong, this text says why.
     */
    @Volatile
    var lastCounterDump: String = ""
        private set

    /** The text the foreground query answered with the last time it ran. */
    @Volatile
    var lastForegroundOutput: String = ""
        private set

    /**
     * The last app that was measured.
     *
     * FrameScope itself is never measured, so this is the app that was in front
     * of the user before the app was opened - the game, usually. The diagnostics
     * need that name to ask SurfaceFlinger about the right layers.
     */
    private val _lastMeasuredPackage = MutableStateFlow<String?>(null)
    val lastMeasuredPackage: StateFlow<String?> = _lastMeasuredPackage.asStateFlow()

    /** When that app was last in front, on the elapsed clock. */
    private var lastMeasuredAtMs = 0L

    /**
     * The device-wide frame counter over the last second.
     *
     * A game's own timestats entry can stay frozen while it presents hundreds
     * of frames; the total at the top of the dump is the counter that moves. A
     * one-second baseline answers quickly when a scene changes, and the cap
     * settling below keeps that shorter window from overshooting 90 or 120.
     */
    private val globalHistory = ArrayDeque<Pair<Long, Long>>()

    /**
     * Why the device-wide counter has no reading this poll.
     *
     * A short window is only a delay: the next poll must arrive quickly so the
     * window can finish. A failed dump is different - then the slow per-layer
     * fallbacks are the only way to get a reading. Keeping them apart stops the
     * game from getting stuck on an old number.
     */
    private var globalWindowPending = false
    private var globalCounterUnavailable = false

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
    private val sceneFpsCalculator = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)

    /** Counts the frames of the gfxinfo provider. */
    private val gfxCounter = FrameCounter(DEFAULT_FRAME_BUDGET_NS)

    /** Which provider produced the reading that is on screen. */
    private var activeSource: FpsSourceKind = FpsSourceKind.NONE

    /** Set when a layer was found but has not drawn two frames yet. */
    private var pendingWarmUpReason: String? = null

    /** When the layer sweep may run again. */
    private var sweepNextAttemptAt = 0L

    /**
     * When a screen with no frames may be re-checked against the app's layers.
     *
     * A counter that stands still is not proof that nothing is drawing: a game
     * owns several surfaces and SurfaceFlinger counts them one by one, so the
     * surface the counters named can be a still one while another one draws.
     */
    private var idleSweepNextAt = 0L

    private val history = ArrayDeque<Float>()

    /**
     * The last five published readings, oldest last.
     *
     * Each entry is (uptime ms, FPS). The user of the overlay and the raw
     * probe can read this after the game closes, so the poll that named BGMI
     * real FPS and the poll that named it 144 or 0 can be compared by hand.
     */
    private val debugFrameLog = ArrayDeque<Pair<Long, Float>>(5)

    /** Reads the last five published readings; oldest last. */
    fun debugFrameLog(): List<Pair<Long, Float>> = debugFrameLog.toList()

    /**
     * The same readings as a text block, for the raw probe.
     *
     * Each line is `<uptime-ms> <FPS>`; the newest is last. The user reads
     * this after the game closes to see what the overlay showed for the last
     * polls, including polls that said 0 or read as the panel's 144.
     */
    fun debugFrameLogText(): String =
        debugFrameLog.joinToString("\n") { (at, fps) -> "$at $fps" }

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
        sweepNextAttemptAt = 0L
        idleSweepNextAt = 0L
        globalHistory.clear()
        globalWindowPending = false
        globalCounterUnavailable = false
        _state.value = FpsState()
    }

    /**
     * Publishes the absence of a measurement, and why.
     *
     * Used when the measurement cannot even be attempted, so the interface can say
     * "this needs Shizuku" instead of showing an empty reading with no explanation.
     */
    fun reportUnavailable(reason: String, availability: MetricAvailability) {
        _state.value = rememberLastReading(
            source = "no source",
            kind = FpsSourceKind.NONE,
            reason = reason,
            availability = availability
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
                lastForegroundOutput = detector.lastOutput
            }
        }

        foregroundSource = detection.source
        foregroundConfident = detection.confident

        val detected = detection.packageName

        // The raw probe must keep naming the last real game even while FrameScope
        // or the launcher is in front, so `_lastMeasuredPackage` is only ever
        // updated for a game. The live FPS below uses the app actually on screen:
        // the device-wide presented-frame counter measures the current screen for
        // any app, not only games.
        val remembered = _lastMeasuredPackage.value
        val rememberedFresh = !remembered.isNullOrBlank() && !isNonGame(remembered) &&
            nowMs - lastMeasuredAtMs < REMEMBERED_TARGET_MS
        val detectedGame = !detected.isNullOrBlank() && detected != ownPackage &&
            !isNonGame(detected)

        // What we publish is the real foreground app. Home and FrameScope are
        // measured too, so the pill shows their live screen FPS instead of a
        // stale game number. Only a blank detector moment falls back to the game.
        val pkg = if (detected.isNullOrBlank() && rememberedFresh) remembered else detected

        if (pkg != foregroundApp) {
            // A different app is in front. The per-layer counters start again;
            // the device-wide counter is continuous and needs no reset.
            onTargetChanged(pkg)
        }
        foregroundApp = pkg
        if (detectedGame) {
            _lastMeasuredPackage.value = detected
            lastMeasuredAtMs = nowMs
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

        pendingWarmUpReason = null

        // The counter of the game's own layer is the only source that separates
        // the game from the rest of the screen. The device-wide counter follows
        // the panel - this phone refreshes at 150 Hz, so it reads 120+ even in a
        // 90 fps lobby. It must never win while a real game layer has a growing
        // counter of its own.
        val fromCounters = if (detectedGame) {
            sampleFromCounters(pkg, targetFps, nowUptime)
        } else null
        if (fromCounters != null) return fromCounters

        // The device-wide counter is the live screen FPS for home and other
        // apps, and the fallback for a game whose layer counter is frozen.
        val fromGlobal = sampleFromGlobalCounter(
            pkg,
            nowUptime,
            appPackage = if (detectedGame) pkg else remembered
        )
        if (fromGlobal != null) return fromGlobal

        // A short window is only a delay. Return quickly so the next poll can
        // finish it. The per-layer fallbacks below take seconds; running them
        // here pushes the next poll past the window, the counter never gets a
        // reading, and the previous FPS stays on the overlay forever.
        // Layer sources also only describe a real game - on home or inside
        // FrameScope they name a system surface, which must never be published
        // as the app's frame rate.
        if (!detectedGame || (globalWindowPending && !globalCounterUnavailable)) {
            return publishUnavailable(
                source = "SurfaceFlinger presented frames",
                kind = FpsSourceKind.TIMESTATS,
                reason = "Waiting for the next frame window of $pkg",
                availability = MetricAvailability.WARMING_UP,
                nowUptime = nowUptime
            )
        }

        // Fallbacks for a game whose own counter entry is frozen.
        val fromActive = sampleFromActiveLayer(pkg, targetFps, nowUptime)
        if (fromActive != null) return fromActive

        val fromLatency = sampleFromLatency(pkg, targetFps, nowUptime)
        if (fromLatency != null) return fromLatency

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
        val list = hub.execOrNull(ShellCommands.surfaceList(), LIST_TIMEOUT_MS)?.out.orEmpty()
        val candidates = layerNamesFor(list, pkg, ownLimit = 6, newestLimit = 6)
        if (candidates.isEmpty()) return emptyList()

        // The whole sweep has a budget. A layer that hangs the shell must not
        // hold the measurement hostage, so the sweep stops when it is used up.
        val deadline = SystemClock.elapsedRealtime() + SWEEP_BUDGET_MS
        val samples = mutableListOf<LayerSample>()
        for (name in candidates) {
            if (SystemClock.elapsedRealtime() > deadline) break

            val dump = hub.execOrNull(ShellCommands.surfaceLatencyOfLayer(name), 900)?.out.orEmpty()
            if (dump.isNotBlank()) samples += parseLayerLatencies("@@@$name\n$dump")
        }
        return samples
    }

    /**
     * The frames SurfaceFlinger counted while the current app was in front.
     *
     * The per-layer counter of a game can stay frozen while the game keeps
     * presenting frames; on this device the total at the top of the timestats
     * dump grows by the real frame count (264 frames in two seconds for a game
     * running at 120). The growth over the one-second window is the reading, and
     * it cannot pick a wrong layer because it names no layer. That also makes it
     * the live screen FPS of the home screen or any non-game app.
     */
    private suspend fun sampleFromGlobalCounter(
        pkg: String,
        nowUptime: Long,
        appPackage: String? = pkg
    ): FpsState? {
        val dump = hub.execOrNull(
            ShellCommands.surfaceTotalFrames(),
            GLOBAL_COUNTER_TIMEOUT_MS
        )?.out.orEmpty()
        val frames = GLOBAL_FRAMES_RE.find(dump)?.groupValues?.get(1)?.toLongOrNull()
        if (frames == null) {
            // The dump did not answer. The slow per-layer sources may still work.
            globalCounterUnavailable = true
            globalWindowPending = false
            return null
        }
        globalCounterUnavailable = false

        val nowMs = SystemClock.elapsedRealtime()
        val newest = globalHistory.lastOrNull()
        if (newest != null && frames < newest.second) {
            // The counter restarted. Its previous samples no longer describe
            // the same timeline, so this poll starts a fresh window.
            globalHistory.clear()
            globalHistory.addLast(nowMs to frames)
            globalWindowPending = true
            return null
        }
        globalHistory.addLast(nowMs to frames)
        // Keep the window near a second, but never throw away the previous
        // sample. A game poll can be slow; without this the old sample is pruned
        // before the next poll and the counter never gets a window at all.
        while (
            globalHistory.size > 2 &&
            nowMs - globalHistory.first().first > GLOBAL_COUNTER_WINDOW_MS
        ) globalHistory.removeFirst()

        val baseline = globalHistory.first()
        val elapsedMs = nowMs - baseline.first
        if (elapsedMs < MIN_COUNTER_WINDOW_MS) {
            globalWindowPending = true
            return null
        }
        val fps = (frames - baseline.second) * 1_000f / elapsedMs
        val displayHz = refreshRateHz()
        if (!isPlausibleFps(fps, displayHz)) {
            globalWindowPending = false
            return null
        }
        globalWindowPending = false

        // This counter sees every surface the compositor presents while the game
        // is in front, not only the game's own frames. A few extra status-bar or
        // overlay frames can therefore push a 90 fps lobby to 92 or a 120 fps
        // match to 129. Hold the number at the cap it just crossed; never push a
        // reading upward, and leave every other source untouched.
        val settledFps = settleToGameCap(fps, displayHz)

        activeSource = FpsSourceKind.TIMESTATS
        measuredLayer = "the frames presented while $pkg was in front"
        publish(
            fps = settledFps,
            frameTimeMs = 1_000f / settledFps,
            source = "SurfaceFlinger presented frames",
            kind = FpsSourceKind.TIMESTATS,
            layer = measuredLayer,
            totalFrames = frames - baseline.second,
            jankFrames = 0L,
            sampleAgeMs = 0L,
            windowMs = elapsedMs,
            appPackage = appPackage,
            nowUptime = nowUptime
        )
        return _state.value
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
        lastCounterDump = tick.raw

        val windowSeconds = if (tick.windowNs > 0L) tick.windowNs / 1_000_000_000.0 else 0.0
        if (windowSeconds <= 0.0) return null

        // The counter is a cumulative total. It only says something when it
        // grew during this window. A standing counter - a game that froze, an
        // app that went idle, or a surface that stopped presenting - is NOT a
        // zero frame rate. Publishing 0 here is what made the overlay show
        // "0.0 fps / 0.0 ms" even though the game had been measured correctly
        // moments before. The old sampler kept its last reading instead.
        val idle = tick.staticScreen && tick.belongsToApp
        if (idle || tick.frames <= 0L) return null
        // When the game does not name its surface, the engine falls back to the
        // layer that grew most. That could be any other app; its rate must never
        // be published as the game's. Let the global counter answer instead.
        if (!tick.belongsToApp) return null
        val fps: Double = tick.fps
        if (fps <= 0.0) return null
        // When the layer named its display rate, the counters already clamped
        // the growth to it - and that rate is bucketed in 30 Hz steps, so a
        // 165 Hz panel reads as 180 and must not be judged against 165.
        if (tick.displayRefreshRate == null && !isPlausibleFps(fps.toFloat())) {
            return null
        }

        // The exact frame times of the layer the counters picked. They are the
        // frames themselves, so they are preferred over the count as soon as
        // two dumps of them exist.
        //
        // This is asked even when the counters say nothing is drawing: a game
        // can keep its own frame counter still - the counter of a layer that
        // renders into a buffer queue is not always advanced - while its frames
        // are presented. The timestamps are then the truth, and they already
        // carry their own freshness check, so an idle screen still falls
        // through to the display rate below.
        val exact = sampleExactLatency(pkg, tick.layer, targetFps)
        if (exact != null) return exact

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

        // The counter sees the game plus the small compositor overhead, so a
        // 90 fps lobby can read 93 and a 120 fps match 129. Settle a small
        // overshoot onto the real cap; never push a low reading upward.
        val settledFps = settleToGameCap(fps.toFloat(), refreshRateHz())
        publish(
            fps = settledFps,
            frameTimeMs = if (settledFps > 0f) (1000f / settledFps).toFloat() else 0f,
            source = "SurfaceFlinger counters",
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
     * How old a presentation timestamp is, on whichever clock the device used.
     *
     * SurfaceFlinger can stamp frames with `CLOCK_MONOTONIC` (uptime) or with
     * `CLOCK_ELAPSED` (elapsed realtime). The two clocks differ by all the time
     * the phone spent asleep - on this device that difference was 134117 ms.
     * Comparing an elapsed-realtime timestamp with uptime is what printed that
     * age even for a frame that had just been drawn.
     *
     * Both clocks are offered because the source is not named in the dump. Only
     * an age that is small on one of them can describe a live frame; the smaller
     * of the plausible answers is the age shown in the overlay.
     */
    private fun presentationAgeNs(
        newestNs: Long,
        nowUptimeNs: Long,
        nowElapsedNs: Long,
        maxAgeNs: Long = FRAME_MAX_AGE_NS
    ): Long? {
        if (newestNs <= 0L) return null
        val uptimeAge = nowUptimeNs - newestNs
        val elapsedAge = nowElapsedNs - newestNs
        val plausible = listOf(uptimeAge, elapsedAge).filter { it in 0L..maxAgeNs }
        return plausible.minOrNull()
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
        // The counters print the layer name, which is exactly what `--latency`
        // compares against. A list entry wrapped in `RequestedLayerState{...}`
        // is not a name, so the wrapper is removed before the layer is asked.
        val name = surfaceLayerName(layer)
        if (name.isBlank()) return null

        val dump = hub.execOrNull(
            ShellCommands.surfaceLatencyOfLayer(name),
            EXACT_LATENCY_TIMEOUT_MS
        )?.out.orEmpty()
        if (dump.isBlank()) return null

        val sample = parseLayerLatencies("@@@$name\n$dump").firstOrNull() ?: return null
        if (sample.presents.size < MIN_EXACT_FRAMES) return null

        // The presentation times are on CLOCK_MONOTONIC. The app asks about
        // both clocks it can compare with, so a build that reports the other
        // one is not mistaken for a layer that stopped drawing.
        val uptime = SystemClock.uptimeNanos()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val newest = sample.presents.last()
        val ageNs = presentationAgeNs(newest, uptime, elapsed) ?: return null

        // Use SceneFpsCalculator for the FPS value: deduplication prevents
        // ring-buffer overlaps from inflating the count, and the stale timeout
        // drops to 0 when no new frames arrive.
        val displayHz = refreshRateHz() ?: 60f
        val sceneResult = sceneFpsCalculator.calculate(
            sample.presents, displayHz, SystemClock.elapsedRealtime()
        )
        if (!sceneResult.isSceneActive || sceneResult.fps <= 0) return null
        val fps = sceneResult.fps.toFloat()
        if (!isPlausibleFps(fps)) return null

        activeSource = FpsSourceKind.SURFACE_FLAINGER_LATENCY
        measuredLayer = layer

        publish(
            fps = fps,
            frameTimeMs = sceneResult.avgFrameTimeMs.takeIf { it > 0f } ?: (1000f / fps),
            source = "SurfaceFlinger frame times",
            kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
            layer = layer,
            totalFrames = sample.presents.size.toLong(),
            jankFrames = sceneResult.jankFrames.toLong(),
            sampleAgeMs = ageNs / 1_000_000L,
            windowMs = (sceneResult.avgFrameTimeMs * sceneResult.fps).toLong().coerceAtLeast(0L),
            low1PercentFps = null,
            low5PercentFps = null,
            appPackage = pkg,
            nowUptime = uptime
        )
        return _state.value
    }

    /** A launcher or a system surface is never the game the user is measuring. */
    private fun isNonGame(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("launcher") || p == "com.android.systemui"
    }

    /**
     * True when a frame rate can belong to this screen.
     *
     * A screen cannot present more frames than it refreshes, so a number above
     * that is a counter that was read wrong and not a frame rate.
     */
    private fun isPlausibleFps(fps: Float): Boolean =
        isPlausibleFps(fps, refreshRateHz())

    /**
     * Removes the few extra frames the compositor adds on top of a game's cap.
     *
     * The device-wide counter is the only source that keeps counting when a
     * game's own entry is frozen, but it counts every surface, so 90 can appear
     * as 92 and 120 as 129. Only a small overshoot above a normal game cap is
     * pulled down; a reading below a cap is never inflated, and a game running
     * at a rate that is not one of these steps is published as measured.
     */
    private fun settleToGameCap(fps: Float, displayHz: Float?): Float {
        val ceiling = displayHz?.takeIf { it > 0f } ?: return fps
        val caps = floatArrayOf(
            30f, 45f, 60f, 90f, 120f, 144f, 165f,
            ceiling, ceiling / 2f, ceiling * 0.6f, ceiling * 0.8f
        )
            .filter { it <= ceiling * 1.02f }
            .distinct()
            .sortedDescending()
        for (cap in caps) {
            if (fps > cap && fps <= cap * 1.08f) return cap
        }
        return fps
    }

    /** The refresh rate of the display that is on, when the system can tell. */
    private fun refreshRateHz(): Float? {
        val ctx = context ?: return null
        val collectors = NativeCollectors(ctx.applicationContext)
        val rate = collectors.refreshRate()
        return rate.value
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
        // The names are taken from the layer list here, not in the shell: a list
        // entry is a "RequestedLayerState{name parentId=..}" descriptor and
        // SurfaceFlinger compares the name it is asked for character for
        // character. Handing over the descriptor asked for a layer that does not
        // exist, and the answer was the refresh period alone.
        val list = hub.execOrNull(ShellCommands.surfaceList(), LIST_TIMEOUT_MS)?.out.orEmpty()
        val own = layerNamesFor(list, pkg)
        var dump = if (own.isEmpty()) "" else hub.execOrNull(
            ShellCommands.surfaceLatencyOfNames(own),
            LATENCY_TIMEOUT_MS
        )?.out.orEmpty()
        var samples = parseLayerLatencies(dump)

        // No layer of the app answered. A game can render into a surface that does
        // not carry its package name, so the newest layers are checked as well and
        // the one that drew last wins.
        var fallbackDump = ""
        if (samples.isEmpty()) {
            val newest = layerNamesFor(list, "", ownLimit = 0, newestLimit = 12)
            if (newest.isNotEmpty()) {
                fallbackDump = hub.execOrNull(
                    ShellCommands.surfaceLatencyOfNames(newest),
                    LATENCY_TIMEOUT_MS
                )?.out.orEmpty()
                if (fallbackDump.isNotBlank()) {
                    samples = parseLayerLatencies(fallbackDump)
                }
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

        // A layer keeps its last frames in the ring buffer long after the app
        // stopped drawing. Those frames are history, not a measurement. Refuse
        // them before selection so a stale surface of another app can never be
        // called the foreground app's FPS. The age is worked out on whichever
        // clock the device stamped the frames with, so deep sleep cannot turn a
        // live frame into a "134117 ms old" reading.
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val fresh = samples.mapNotNull { sample ->
            val newest = sample.presents.lastOrNull() ?: return@mapNotNull null
            val ageNs = presentationAgeNs(newest, nowUptime, nowElapsedNs) ?: return@mapNotNull null
            sample to ageNs
        }
        val freshSamples = fresh.map { it.first }
        val staleCount = samples.size - freshSamples.size
        if (freshSamples.isEmpty()) {
            pendingWarmUpReason = "No layer of $pkg presented a frame recently"
            val staleNote = if (staleCount > 0) "\n$staleCount layer(s) had only old frames" else ""
            _reports.value = retainReport(
                _reports.value,
                pkg,
                buildProbeReport(pkg, dump) + staleNote
            )
            return null
        }

        val report = buildProbeReport(pkg, dump) +
            if (fallbackDump.isBlank()) "" else {
                "\nfallback (newest layers):\n" + buildProbeReport(pkg, fallbackDump)
            } +
            if (staleCount > 0) "\nignored $staleCount layer(s) with old frames" else ""
        _reports.value = retainReport(_reports.value, pkg, report)
        // FrameScope's own overlay and the system bars present frames of their
        // own. They are never the game, so they are removed before any layer is
        // chosen - including the unnamed last resort.
        val usableSamples = freshSamples.filterNot { isNoiseLayer(it.name, ownPackage) }
        if (usableSamples.isEmpty()) {
            pendingWarmUpReason = "No layer of $pkg presented a frame recently"
            return null
        }

        // A game draws into a surface named after its engine, so the layer that
        // carries the package name is often missing. An unnamed layer is kept
        // only when its own timestamps prove it is drawing continuously. That
        // check is what rejects the idle 2.6 fps surface this device once named
        // as the game, without rejecting the real game surface.
        fun unnamedIsGame(sample: LayerSample): Boolean {
            if (packageEvidenceOf(sample.name, pkg) != PackageEvidence.NONE) return true
            if (sample.presents.size < MIN_UNATTRIBUTED_FRAMES) return false
            val deltas = sample.presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
            val stats = computeFrameStats(deltas) ?: return false
            return stats.fps >= MIN_UNATTRIBUTED_FPS
        }
        val candidates = usableSamples.filter { unnamedIsGame(it) }
        if (candidates.isEmpty()) {
            pendingWarmUpReason = "No continuously drawing layer of $pkg was found"
            return null
        }

        // A game owns several surfaces. The one named after its activity is often
        // only a UI layer that presents at a fraction of the engine's rate; the
        // engine's own surface presents the frames the player sees. Of all the
        // layers that proved they are drawing, the fastest one is the game.
        val measured = candidates.mapNotNull { sample ->
            val deltas = sample.presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
            val stats = computeFrameStats(deltas) ?: return@mapNotNull null
            Triple(sample, stats, packageEvidenceOf(sample.name, pkg))
        }.maxByOrNull { it.second.fps } ?: run {
            pendingWarmUpReason = "Waiting for ${pkg} to present frames"
            return null
        }
        val target = measured.first
        val ownStats = measured.second
        val attributed = measured.third != PackageEvidence.NONE
        val sampleAgeNs = fresh.first { it.first === target }.second
        targetReason = "fastest continuously drawing layer ${target.name} " +
            "(${if (attributed) "exact package match" else "attributed by continuous drawing"})"

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

        // The timestamps themselves are the frames, so they are the reading. A
        // rolling counter that only sees a few new frames per dump underreports
        // a game running at 90-120 as 24, which is what the overlay showed.
        val fps = ownStats.fps
        // An idle UI surface presents a handful of frames a second. Calling
        // that the game produced 4 fps was wrong; it is not a frame rate of
        // the game and is refused instead of replacing a real reading.
        if (fps < MIN_GAME_FPS || !isPlausibleFps(fps)) {
            pendingWarmUpReason = "Waiting for ${target.name} to present frames"
            return null
        }

        publish(
            fps = fps,
            frameTimeMs = ownStats.frameTimeMs,
            source = "SurfaceFlinger latency",
            kind = FpsSourceKind.SURFACE_FLAINGER_LATENCY,
            layer = target.name,
            totalFrames = latencyCounter.totalUniqueFrames,
            jankFrames = latencyCounter.totalJankFrames,
            sampleAgeMs = sampleAgeNs / 1_000_000L,
            windowMs = result.windowMs,
            low1PercentFps = latencyCounter.low1PercentFps(),
            low5PercentFps = latencyCounter.low5PercentFps(),
            appPackage = pkg,
            nowUptime = nowUptime
        )
        return _state.value
    }

    /**
     * The layer SurfaceFlinger is presenting right now.
     *
     * Asked without a name, `--latency` answers with the active layer. On a game
     * that layer carries the engine's name and no package name, so it is the
     * only probe that finds the frames of a game like com.pubg.imobile. It is
     * asked only while the game is the foreground app, so the answer belongs to
     * that game. A dump that only holds the refresh period is refused.
     */
    private suspend fun sampleFromActiveLayer(
        pkg: String,
        targetFps: Int,
        nowUptime: Long
    ): FpsState? {
        val dump = hub.execOrNull(
            ShellCommands.surfaceLatencyDefault(),
            ACTIVE_LATENCY_TIMEOUT_MS
        )?.out.orEmpty()
        if (dump.isBlank()) return null

        val (_, presents) = Parsers.parseLatency(dump)
        if (presents.size < MIN_ACTIVE_FRAMES) return null

        // This probe only runs when the game itself is the foreground app, so a
        // dump that answers here is the game's own surface. The clock its frames
        // are stamped with cannot always be matched to either clock of the app;
        // refusing the reading for that reason is what left the overlay on
        // "--fps". The age is reported for diagnosis, never used to throw away
        // the only source that measures this game.
        val sampleAgeMs = presentationAgeNs(
            presents.last(),
            nowUptime,
            SystemClock.elapsedRealtimeNanos()
        )?.div(1_000_000L) ?: 0L

        val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
        val stats = computeFrameStats(deltas) ?: return null
        val fps = stats.fps
        if (fps < MIN_GAME_FPS || !isPlausibleFps(fps)) return null

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
            sampleAgeMs = sampleAgeMs,
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
        // gfxinfo also keeps a short history. A frame that is no longer recent
        // is a paused app, not a live frame rate, and must not publish an old age.
        val ageNs = presentationAgeNs(timestamps.last(), nowUptime, now) ?: run {
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
        // A number that no screen can show is a timestamp that was read wrong,
        // not a frame rate. The 144 Hz panel cannot present 298 frames, and
        // that is what the interface showed before this check.
        if (fps != null && fps > 0f && !isPlausibleFps(fps)) return null
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
            sampleAgeMs = ageNs / 1_000_000L,
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
        sweepNextAttemptAt = 0L
        idleSweepNextAt = 0L
        globalHistory.clear()
        if (pkg != null) {
            // Reset the baselines for the new app, but keep the number that was
            // already on screen. The old sampler never blanked its reading when
            // the app in front changed; writing an empty state here dropped the
            // overlay to "--fps" every time FrameScope or the launcher came up.
            val last = _state.value
            _state.value = if (last.fps > 0f) {
                last.copy(
                    availability = MetricAvailability.WARMING_UP,
                    reason = "Waiting for $pkg to present frames"
                )
            } else {
                FpsState(
                    availability = MetricAvailability.WARMING_UP,
                    reason = "Waiting for $pkg to present frames"
                )
            }
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
        debugFrameLog.addLast(SystemClock.uptimeMillis() to fps)
        while (debugFrameLog.size > 5) debugFrameLog.removeFirst()

        // The last five readings are also written to a file so they survive
        // the game closing: the user opens FrameScope, runs the raw probe,
        // and reads the file or the probe text to compare the poll that said
        // 0 or 144 with the polls that said real FPS.
        runCatching {
            val text = debugFrameLogText()
            if (text.isNotBlank()) context?.filesDir?.resolve("fps_debug.txt")?.writeText(text)
        }

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

    /**
     * Builds a "no new reading" state that keeps the last real measurement.
     *
     * This is the behaviour of the version that measured games correctly: when
     * a source has nothing new - a frozen game, an idle screen, a dump that did
     * not arrive - the number on screen stays at the last true value and only
     * the status changes. Showing 0 as a frame rate is a different claim: it
     * says the screen rendered nothing, which cannot be concluded from a source
     * that simply did not answer.
     */
    private fun rememberLastReading(
        source: String,
        kind: FpsSourceKind,
        reason: String,
        availability: MetricAvailability
    ): FpsState {
        val last = _state.value
        // A real number is still a real number after one empty poll. Writing an
        // invalid state here is what made the overlay flip to "--fps" between
        // real readings, which is what the user saw. Keep the reading and its
        // own, already clock-checked age; never recompute that age from wall
        // time, which is how the "134117 ms old" line was produced.
        val nowElapsedNs = SystemClock.elapsedRealtimeNanos()
        val keep = last.fps > 0f &&
            nowElapsedNs - last.measuredAtElapsedNanos < KEEP_LAST_READING_NS
        return FpsState(
            fps = if (keep) last.fps else 0f,
            frameTimeMs = if (keep) last.frameTimeMs else 0f,
            history = if (keep) last.history else history.toList(),
            totalFrames = if (keep) last.totalFrames else latencyCounter.totalUniqueFrames.toInt(),
            jankFrames = if (keep) last.jankFrames else latencyCounter.totalJankFrames.toInt(),
            source = source,
            surface = if (keep) last.surface else null,
            valid = keep,
            reason = reason,
            availability = if (keep) MetricAvailability.AVAILABLE else availability,
            measuredAtElapsedNanos = if (keep) last.measuredAtElapsedNanos else nowElapsedNs,
            sampleAgeMs = if (keep) last.sampleAgeMs else 0L,
            measurementWindowMs = if (keep) last.measurementWindowMs else 0L,
            sourceKind = kind,
            low1PercentFps = if (keep) last.low1PercentFps else null,
            low5PercentFps = if (keep) last.low5PercentFps else null,
            appPackage = if (keep) last.appPackage else null
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
        val state = rememberLastReading(source, kind, reason, availability)
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
         * How long the layer list may take.
         *
         * The list is what the names come from: `--latency` only answers to the
         * exact layer name, and a list entry is a descriptor around it.
         */
        private const val LIST_TIMEOUT_MS = 3_000L

        /**
         * How long the exact timestamps of one known layer may take.
         *
         * That is one small dump, not a sweep, so it has to be quick: it runs
         * on every poll, after the counters have already named the layer.
         */
        private const val EXACT_LATENCY_TIMEOUT_MS = 1_200L

        /** Fewer frames than this say nothing about a frame rate. */
        private const val MIN_EXACT_FRAMES = 8

        /** The first `totalFrames` of the dump is the device-wide counter. */
        private val GLOBAL_FRAMES_RE = Regex("""totalFrames\s*=\s*(\d+)""")

        /** A game that is drawing does not present four frames a second. */
        private const val MIN_GAME_FPS = 20f

        /** How long the device-wide counter may take to answer. */
        private const val GLOBAL_COUNTER_TIMEOUT_MS = 3_000L

        /** The counter difference is taken over about one second, not one poll. */
        private const val GLOBAL_COUNTER_WINDOW_MS = 1_000L

        /** A window shorter than this makes a counter difference meaningless. */
        private const val MIN_COUNTER_WINDOW_MS = 500L

        /** Fewer frames than this on the active layer say nothing about FPS. */
        private const val MIN_ACTIVE_FRAMES = 4

        /**
         * An unnamed layer has to prove it is drawing before it can be called
         * the game. A short burst or an idle surface is not a frame rate.
         */
        private const val MIN_UNATTRIBUTED_FRAMES = 8
        private const val MIN_UNATTRIBUTED_FPS = 24f

        /** How long the unnamed active-layer probe may take. */
        private const val ACTIVE_LATENCY_TIMEOUT_MS = 3_000L

        /**
         * How long a real reading is kept while the game or FrameScope is in
         * front. The launcher clears it at once; past this point a number is
         * history and is blanked rather than shown as a current reading.
         */
        private const val KEEP_LAST_READING_NS = 300_000_000_000L

        /**
         * How long the game that was just in front stays the measurement target
         * when FrameScope's own window or the launcher takes the focus.
         */
        private const val REMEMBERED_TARGET_MS = 600_000L

        /**
         * How old the newest frame of a layer may be to still be its meter.
         *
         * This matches the overlay's own stale window. A layer that stopped
         * drawing before that is history, not the surface the user is looking
         * at; its last frames are refused instead of being shown with an age.
         */
        private const val FRAME_MAX_AGE_NS = 2_000_000_000L

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

        /**
         * How often a screen with no frames is re-checked layer by layer.
         *
         * The check asks every layer of the app in one round trip, so it is not
         * free; two or three times a second would be wasted on a screen that is
         * genuinely standing still.
         */
        private const val IDLE_SWEEP_COOLDOWN_MS = 3_000L
    }
}
