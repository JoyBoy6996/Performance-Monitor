package com.framescope.monitor.metrics

import com.framescope.monitor.core.FpsState
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Samples real frame timings from SurfaceFlinger.
 *
 * `dumpsys SurfaceFlinger --latency <layer>` returns the last ~128 presented
 * frames with nanosecond timestamps, which lets us compute a true frame time
 * graph even though we only poll a couple of times per second.
 *
 * The layer name has to match SurfaceFlinger's own name byte for byte, so the
 * names are taken verbatim from `dumpsys SurfaceFlinger --list` and every
 * candidate is verified before it is used. If no layer can be measured -
 * a game that renders into a surface we cannot name, a device that refuses
 * the dump - the sampler says so instead of inventing a number.
 */
class FpsSampler(private val hub: ShellHub) {

    private val _state = MutableStateFlow(FpsState())
    val state: StateFlow<FpsState> = _state.asStateFlow()

    private var lastPresent = 0L
    private var totalFrames = 0
    private var jankFrames = 0
    private val history = ArrayDeque<Float>()
    private val frameLog = ArrayDeque<Float>()

    /** Foreground package as seen by the last sample. */
    @Volatile
    var foregroundApp: String? = null
        private set

    /** The layer that is currently being measured, for the diagnostics row. */
    @Volatile
    var measuredLayer: String? = null
        private set

    /**
     * What the last sample attempt saw.
     *
     * Every layer that was tried, how many frames it returned and why it was
     * kept or dropped. Without this the failures are guesswork.
     */
    private val _reports = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The counters of the last timestats reading, per layer. */
    private val timeStatsBase = LinkedHashMap<String, TimeStats.Reading>()

    /** The time the counters were read, in nanoseconds. */
    private var timeStatsAt: Long = 0L
    private var timeStatsEnabled = false

    /** When the counters were last read, in nanoseconds. */
    private var timeStatsLastUse: Long = 0L

    /** The raw counters dump, shown in the report so the shape is visible. */
    @Volatile
    var timeStatsDump: String = ""
        private set

    /**
     * The probe report of every app that was sampled, newest last.
     *
     * The report of the app the user was in is kept when FrameScope comes to
     * the front, otherwise opening the app to read the report would replace
     * the evidence of the app that misbehaved.
     */
    val reports: StateFlow<Map<String, String>> = _reports.asStateFlow()

    fun reset() {
        lastPresent = 0L
        totalFrames = 0
        jankFrames = 0
        history.clear()
        frameLog.clear()
    }

    suspend fun sample(): FpsState {
        val pkg = currentPackage()
        foregroundApp = pkg
        if (pkg.isNullOrBlank()) {
            return invalid("no app", "no foreground app detected")
        }

        // The counters cost a little on every frame, so they are switched off
        // again once they have not been needed for a while.
        if (timeStatsEnabled && timeStatsLastUse > 0L &&
            android.os.SystemClock.elapsedRealtimeNanos() - timeStatsLastUse > 30_000_000_000L
        ) {
            hub.execOrNull("dumpsys SurfaceFlinger --timestats -disable 2>/dev/null")
            timeStatsEnabled = false
            timeStatsBase.clear()
        }

        // The layer list is walked inside the shell below, one round trip for
        // every layer of the app.
        // Read every candidate, then watch the one that drew most recently.
        //
        // An app often owns several layers - a background, a snapshot, the
        // window itself - and only one of them is actually drawing. Picking the
        // first that looks plausible is how the home screen or the settings app
        // ended up showing the frame rate of a stale layer.
        // One shell round trip for every layer of the app.
        val dump = hub.execOrNull(ShellCommands.surfaceLatencyOfPackage(pkg))?.out.orEmpty()
        val candidates = ArrayList<LayerSample>()
        candidates += parseLayerLatencies(dump).filter { plausible(it.presents, it.refresh) }

        if (candidates.isEmpty()) {
            // No layer of the app answered. A game can render into a surface
            // that does not carry its package name, so the newest layers are
            // checked as well and the one that drew last wins.
            val fallback = hub.execOrNull(
                ShellCommands.surfaceLatencyOfPackage(pkg, fallback = true)
            )?.out.orEmpty()
            candidates += parseLayerLatencies(fallback).filter { plausible(it.presents, it.refresh) }
            remember(pkg, buildProbeReport(pkg, dump) + "\nfallback (newest layers):\n" +
                buildProbeReport(pkg, fallback).substringAfter('\n'))
        } else {
            remember(pkg, buildProbeReport(pkg, dump))
        }
        // The counters are read further down, so the section lands one sample
        // later. It is evidence, not a reading.
        if (timeStatsDump.isNotBlank()) {
            val base = _reports.value[pkg].orEmpty()
                .substringBefore("\nframe counters (SurfaceFlinger timestats):")
            _reports.value = retainReport(_reports.value, pkg, base + timeStatsSection())
        }

        for (sample in pickMostRecent(candidates)) {
            val result = build(sample.name, sample.refresh, sample.presents) ?: continue
            measuredLayer = sample.name
            return result
        }

        // No usable SurfaceFlinger layer. A game that renders straight into a
        // buffer queue has no frame timestamps, but SurfaceFlinger still counts
        // the frames it presented for every layer.
        val fromCounters = sampleTimeStats(pkg)
        if (fromCounters != null) {
            timeStatsLastUse = android.os.SystemClock.elapsedRealtimeNanos()
            return fromCounters
        }


        // Nothing from SurfaceFlinger: fall back to the app's own frame
        // statistics, but only if the app is actually rendering continuously.
        sampleGfxInfo(pkg)?.let { return it }
        sampleDefaultLatency()?.let { return it }
        return invalid("no data", "no frame timestamps for $pkg")
    }

    /**
     * The frame rate from the counters SurfaceFlinger keeps per layer.
     *
     * The counter is cumulative, so the frame rate is how much it grew since
     * the previous reading. It is the only source that also works for a game
     * without a frame timeline.
     */
    private suspend fun sampleTimeStats(packageName: String): FpsState? {
        if (!timeStatsEnabled) {
            hub.execOrNull(ShellCommands.surfaceTimeStatsEnable())
            timeStatsEnabled = true
        }
        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsDump())?.out.orEmpty()
        timeStatsDump = dump

        val readings = TimeStats.readAll(dump, packageName)
        if (readings.isEmpty()) return null
        val now = android.os.SystemClock.elapsedRealtimeNanos()
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

        // A game owns several layers: the scene, a heads up display, a banner.
        // The one that presents the most frames in this interval is the one
        // that is actually drawing, so it is the one that is measured.
        var best: Triple<TimeStats.Reading, Long, Long>? = null
        for (reading in readings) {
            val before = previous[reading.layer] ?: continue
            val grown = reading.frames - before.frames
            if (grown <= 0L) continue
            val candidate = Triple(reading, grown, reading.jankyFrames - before.jankyFrames)
            if (best == null || candidate.second > best.second) best = candidate
        }
        val (reading, grown, jankyGrowth) = best ?: return null

        val fps = grown.toDouble() * 1_000_000_000.0 / elapsed
        if (fps < 1.0 || fps > 400.0) return null

        measuredLayer = reading.layer
        return FpsState(
            fps = fps.toFloat(),
            frameTimeMs = (1000.0 / fps).toFloat(),
            totalFrames = reading.frames.toInt(),
            jankFrames = jankyGrowth.coerceAtLeast(0L).toInt(),
            source = "SurfaceFlinger counters",
            surface = reading.layer,
            valid = true
        )
    }

    /** Keeps the report of an app so it survives a switch to another app. */
    private fun remember(packageName: String, report: String) {
        _reports.value = retainReport(_reports.value, packageName, report)
    }

    /** The shape of the counters dump, added to the report. */
    private fun timeStatsSection(): String {
        val dump = timeStatsDump
        if (dump.isBlank()) return ""
        return "\nframe counters (SurfaceFlinger timestats):\n" +
            dump.lineSequence().filter { it.isNotBlank() }.take(10)
                .joinToString("\n") { "      | " + it.trim().take(90) } + "\n"
    }

    /** The foreground package, read on its own so a failure is visible. */
    private suspend fun currentPackage(): String? {
        val out = hub.execOrNull(ShellCommands.foregroundPackage())?.out
        if (out == null) return null
        return Parsers.foregroundPackage(out)
    }

    /**
     * Rejects series that cannot be a frame rate: too few frames, an interval
     * that no display can produce, or a signal that does not cluster around a
     * single interval (which is what idle gaps look like).
     */
    private fun plausible(presents: List<Long>, refreshNs: Long = 0L): Boolean =
        plausibleSeries(presents, refreshNs)

    /** Turns a frame series into the live state. */
    private fun build(layer: String, refresh: Long, presents: List<Long>): FpsState? {
        if (measuredLayer != null && measuredLayer != layer) {
            // Timestamps from another layer cannot be continued.
            lastPresent = 0L
            history.clear()
            frameLog.clear()
        }
        val newFrames = if (lastPresent <= 0L) {
            presents.drop(1)
        } else {
            presents.filter { it > lastPresent }
        }
        if (newFrames.isEmpty()) {
            // Nothing new since the last poll, keep the previous reading.
            return _state.value.copy(surface = layer, source = "SurfaceFlinger")
        }

        var previous = if (lastPresent <= 0L) presents.first() else lastPresent
        val deltas = ArrayList<Long>()
        for (present in newFrames) {
            val delta = present - previous
            previous = present
            if (delta <= 0L || delta > 1_000_000_000L) continue
            deltas += delta
        }
        if (deltas.size < 2) return null
        lastPresent = previous

        val stats = computeFrameStats(deltas) ?: return null
        val median = stats.medianNs
        val janky = stats.jank
        totalFrames += deltas.size
        jankFrames += janky

        for (delta in deltas) {
            val fpsForFrame = (1_000_000_000.0 / delta).toFloat()
            history.addLast(fpsForFrame)
            frameLog.addLast(fpsForFrame)
        }
        while (history.size > 4096) history.removeFirst()
        while (frameLog.size > 240) frameLog.removeFirst()

        // The headline number is the median interval, which ignores the odd
        // stall and matches what a capture card would report.
        val medianFps = (1_000_000_000.0 / median).toFloat()
        val shownFps = if (history.size >= 10) {
            val recent = history.takeLast(10)
            (recent.average() * 0.5 + medianFps * 0.5).toFloat()
        } else {
            medianFps
        }

        _state.value = FpsState(
            fps = shownFps,
            // The frame time always matches the frame rate that is shown.
            frameTimeMs = (1000f / shownFps),
            history = frameLog.toList(),
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            source = "SurfaceFlinger",
            surface = layer,
            valid = true,
            reason = null
        )
        return _state.value
    }

    private fun invalid(source: String, reason: String): FpsState {
        val next = _state.value.copy(valid = false, source = source, reason = reason)
        _state.value = next
        return next
    }

    /**
     * Fallback for apps that do not use a SurfaceView layer.
     *
     * `gfxinfo framestats` only records frames the app actually drew, so an
     * idle app produces huge gaps. Those gaps are not frame times and are
     * dropped; if nothing is left the app is simply not rendering.
     */
    private suspend fun sampleGfxInfo(pkg: String?): FpsState? {
        if (pkg.isNullOrBlank()) return null
        val dump = hub.execOrNull(ShellCommands.gfxInfo(pkg))?.out ?: return null
        val frames = Parsers.parseGfxFrameStats(dump)
        if (frames.size < 4) return null

        val deltas = frames.zipWithNext { a, b -> b - a }.filter { it > 0L && it < 1_000_000_000L }
        if (deltas.size < 3) return null
        val median = deltas.sorted()[deltas.size / 2]
        // Longer than 100 ms between frames means the app was idle, not slow.
        if (median > 100_000_000L) {
            return invalid("idle", "$pkg is not drawing frames")
        }
        val clustered = deltas.filter { it >= median / 2 && it <= median * 2 }
        if (clustered.size < deltas.size / 2) return null

        val stats = computeFrameStats(clustered) ?: return null
        val frameTime = stats.frameTimeMs
        val fps = stats.fps
        val janky = stats.jank
        totalFrames += clustered.size
        jankFrames += janky
        for (delta in clustered) {
            val value = (1_000_000_000.0 / delta).toFloat()
            history.addLast(value)
            frameLog.addLast(value)
        }
        while (history.size > 4096) history.removeFirst()
        while (frameLog.size > 240) frameLog.removeFirst()

        _state.value = FpsState(
            fps = fps,
            frameTimeMs = (1000f / fps),
            history = frameLog.toList(),
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            source = "HWUI gfxinfo",
            surface = pkg,
            valid = true,
            reason = null
        )
        return _state.value
    }

    /** Last resort: the latency of the layer SurfaceFlinger considers active. */
    private suspend fun sampleDefaultLatency(): FpsState? {
        val dump = hub.execOrNull(ShellCommands.surfaceLatencyDefault())?.out ?: return null
        val (refresh, presents) = Parsers.parseLatency(dump)
        if (!plausible(presents, refresh)) return null
        val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
        if (deltas.isEmpty()) return null
        val median = deltas.sorted()[deltas.size / 2]
        val fps = (1_000_000_000.0 / median).toFloat()
        totalFrames += deltas.size
        history.addLast(fps)
        frameLog.addLast(fps)
        while (history.size > 4096) history.removeFirst()
        while (frameLog.size > 240) frameLog.removeFirst()
        _state.value = FpsState(
            fps = fps,
            frameTimeMs = (1000f / fps),
            history = frameLog.toList(),
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            source = "SurfaceFlinger (active)",
            valid = true,
            reason = null
        )
        return _state.value
    }
}


/**
 * The numbers behind a frame series.
 *
 * Kept separate from the sampler so the arithmetic can be checked on its own:
 * a 60 Hz stream has to come out as 60 fps and 16.7 ms, and a stream with
 * stalls has to count them as jank.
 */
internal data class FrameStats(
    val fps: Float,
    val frameTimeMs: Float,
    val medianNs: Long,
    val deltas: List<Long>,
    val jank: Int
)

internal fun computeFrameStats(deltas: List<Long>): FrameStats? {
    if (deltas.size < 2) return null
    val sorted = deltas.sorted()
    val median = sorted[sorted.size / 2]
    // Below a millisecond no display can show a frame, above 100 ms the app
    // was idle rather than slow.
    if (median < 1_000_000L || median > 100_000_000L) return null
    val frameTime = (deltas.average() / 1_000_000.0).toFloat()
    if (frameTime <= 0f) return null
    val fps = (1000.0 / frameTime).toFloat()
    // Jank is a frame that took noticeably longer than the usual interval.
    val jank = deltas.count { it > median * 1.6 }
    return FrameStats(fps = fps, frameTimeMs = frameTime, medianNs = median, deltas = deltas, jank = jank)
}


/** One SurfaceFlinger layer that produced a usable frame series. */
internal data class LayerSample(
    val name: String,
    val refresh: Long,
    val presents: List<Long>
)

/**
 * Orders the layers so the one that drew most recently comes first.
 *
 * The comparison is between the layers of the same device, so it does not
 * depend on the clock the timestamps come from.
 */
internal fun pickMostRecent(samples: List<LayerSample>): List<LayerSample> =
    samples.sortedByDescending { it.presents.last() }


/**
 * Rejects series that cannot be a frame rate: too few frames, an interval
 * that no display can produce, or a signal that does not cluster around a
 * single interval (which is what idle gaps look like).
 */
internal fun plausibleSeries(presents: List<Long>, refreshNs: Long = 0L): Boolean {
    if (presents.size < 4) return false
    val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
    if (deltas.size < 3) return false
    val median = deltas.sorted()[deltas.size / 2]
    if (median < 1_000_000L || median > 100_000_000L) return false
    // A display cannot present frames many times faster than it refreshes.
    // Timestamps that say otherwise are not present times.
    if (refreshNs > 0L && median * 3 < refreshNs) return false
    val clustered = deltas.count { it >= median / 2 && it <= median * 2 }
    return clustered * 10 >= deltas.size * 6
}

/**
 * Adds the report of an app and drops the oldest one.
 *
 * The order matters: the most recently updated app is last, so the report of
 * the app that is on screen can be found without looking at the keys.
 */
internal fun retainReport(
    current: Map<String, String>,
    packageName: String,
    report: String
): Map<String, String> {
    val next = LinkedHashMap(current)
    next.remove(packageName)
    next[packageName] = report
    while (next.size > 4) {
        val oldest = next.keys.firstOrNull() ?: break
        next.remove(oldest)
    }
    return next
}

/**
 * A readable account of what the layers of an app returned.
 *
 * It names every layer, the number of frames it had and why it was accepted
 * or dropped, so a wrong reading can be traced to the exact layer.
 */
internal fun buildProbeReport(packageName: String, dump: String): String {
    val out = StringBuilder()
    out.append("app: ").append(packageName).append('\n')
    var count = 0
    for (block in dump.split("@@@")) {
        val name = block.lines().firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) continue
        count++
        val body = block.substringAfter('\n')
        val (refresh, presents) = Parsers.parseLatency(body)
        val reason = when {
            presents.isEmpty() -> "no frames"
            !plausibleSeries(presents, refresh) -> "rejected (median ${medianMs(presents)} ms)"
            else -> "${presents.size} frames, median ${medianMs(presents)} ms, ok"
        }
        out.append("  ").append(name).append(" -> ").append(reason).append('\n')
        // The first raw rows show the shape of the dump on this device.
        body.lineSequence().filter { it.isNotBlank() }.take(2).forEach {
            out.append("      | ").append(it.trim().take(90)).append('\n')
        }
        if (count >= 20) break
    }
    if (count == 0) out.append("  (no layer answered)\n")
    return out.toString()
}

private fun medianMs(presents: List<Long>): String {
    val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
    if (deltas.isEmpty()) return "?"
    val median = deltas.sorted()[deltas.size / 2]
    return String.format("%.1f", median / 1_000_000.0)
}

/**
 * Splits the output of the multi layer latency dump.
 *
 * Each block starts with the layer name on a `@@@` line, followed by the
 * regular SurfaceFlinger latency format.
 */
internal fun parseLayerLatencies(dump: String): List<LayerSample> {
    val result = ArrayList<LayerSample>()
    var name: String? = null
    val block = StringBuilder()

    fun flush() {
        val current = name ?: return
        val text = block.toString()
        block.setLength(0)
        if (text.isBlank()) return
        val (refresh, presents) = Parsers.parseLatency(text)
        if (presents.isEmpty()) return
        result += LayerSample(current, refresh, presents)
    }

    for (line in dump.lines()) {
        if (line.startsWith("@@@")) {
            flush()
            name = line.removePrefix("@@@").trim()
        } else if (name != null) {
            block.append(line).append('\n')
        }
    }
    flush()
    return result
}
