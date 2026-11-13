package com.framescope.monitor.metrics

import android.app.usage.UsageStatsManager
import android.content.Context
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.shell.Parsers
import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Samples real frame timings from SurfaceFlinger and system graphics pipelines.
 *
 * `dumpsys SurfaceFlinger --latency <layer/package>` returns the nanosecond timestamps
 * of presented frames (3-column layout: desired, actual, ready).
 */
class FpsSampler(
    private val hub: ShellHub,
    var context: Context? = null
) {

    private val _state = MutableStateFlow(FpsState())
    val state: StateFlow<FpsState> = _state.asStateFlow()

    private var lastPresent = 0L
    private var totalFrames = 0
    private var jankFrames = 0
    private val history = ArrayDeque<Float>()

    @Volatile
    var foregroundApp: String? = null
        private set

    @Volatile
    var measuredLayer: String? = null
        private set

    private val _reports = MutableStateFlow<Map<String, String>>(emptyMap())
    val reports: StateFlow<Map<String, String>> = _reports.asStateFlow()

    private val timeStatsBase = LinkedHashMap<String, TimeStats.Reading>()
    private var timeStatsAt: Long = 0L
    private var timeStatsEnabled = false
    private var timeStatsLastUse: Long = 0L

    @Volatile
    var timeStatsDump: String = ""
        private set

    fun reset() {
        lastPresent = 0L
        totalFrames = 0
        jankFrames = 0
        history.clear()
        _state.value = FpsState()
    }

    suspend fun sample(targetFps: Int = 60): FpsState {
        val pkg = currentPackage()
        foregroundApp = pkg

        if (pkg.isNullOrBlank()) {
            val s = invalid("No app", "Waiting for a foreground app")
            _state.value = s
            return s
        }

        // 1. Primary: Query SurfaceFlinger latency for candidate layers of package
        val candidateLayers = getCandidateLayers(pkg)
        val sfCmd = if (candidateLayers.isNotEmpty()) {
            buildString {
                append("{ ")
                for (layer in candidateLayers) {
                    val safe = layer.replace("\"", "")
                    append("echo \"@@@$safe\"; dumpsys SurfaceFlinger --latency \"$safe\" 2>/dev/null | head -40; ")
                    val base = safe.substringBefore('#')
                    if (base != safe) {
                        append("echo \"@@@$base\"; dumpsys SurfaceFlinger --latency \"$base\" 2>/dev/null | head -40; ")
                    }
                }
                append("}")
            }
        } else {
            ShellCommands.surfaceLatencyOfPackage(pkg)
        }
        val dump = hub.execOrNull(sfCmd, 2_500)?.out.orEmpty()

        _reports.value = retainReport(_reports.value, pkg, buildProbeReport(pkg, dump))

        val parsed = parseSurfaceFlingerOutput(dump)
        if (parsed != null && parsed.valid) {
            _state.value = parsed
            return parsed
        }

        // 2. Fallback: Default SurfaceFlinger active latency
        val defaultDump = hub.execOrNull(ShellCommands.surfaceLatencyDefault(), 1_500)?.out.orEmpty()
        val defaultParsed = parseSurfaceFlingerOutput(defaultDump)
        if (defaultParsed != null && defaultParsed.valid) {
            measuredLayer = "SurfaceFlinger (default)"
            _state.value = defaultParsed
            return defaultParsed
        }

        // 3. Fallback: SurfaceFlinger TimeStats
        val fromTimeStats = sampleTimeStats(pkg)
        if (fromTimeStats != null && fromTimeStats.valid) {
            timeStatsLastUse = android.os.SystemClock.elapsedRealtimeNanos()
            _state.value = fromTimeStats
            return fromTimeStats
        }

        // 4. Fallback: dumpsys gfxinfo
        val fromGfx = sampleGfxInfo(pkg, targetFps)
        if (fromGfx != null && fromGfx.valid) {
            _state.value = fromGfx
            return fromGfx
        }

        val noSignal = invalid("No signal", "SurfaceFlinger returned no frame data for $pkg")
        _state.value = noSignal
        return noSignal
    }

    private var cachedLayers: List<String> = emptyList()
    private var lastLayersAt: Long = 0L
    private var lastLayersPkg: String? = null

    private fun normalizeLayerName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("RequestedLayerState{") && s.endsWith("}")) {
            s = s.removePrefix("RequestedLayerState{").removeSuffix("}").trim()
            // Strip OriginOS/Android 15+ timestamp + parentId while preserving #<id>:
            // "Window:22ff352 type=1 com.pkg/Act 11-13 11:10:26.046#335355 parentId=335338"
            // -> "Window:22ff352 type=1 com.pkg/Act#335355"
            s = s.replace(Regex("\\s+\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d+(#\\d+).*$")) { it.groupValues[1] }
            s = s.replace(Regex("\\s+(?:parentId|relativeParentId|z)=.*$"), "").trim()
        }
        return s
    }

    private suspend fun getCandidateLayers(pkg: String): List<String> {
        val now = System.currentTimeMillis()
        if (pkg != lastLayersPkg || now - lastLayersAt > 2500L || cachedLayers.isEmpty()) {
            val listDump = hub.execOrNull("dumpsys SurfaceFlinger --list 2>/dev/null", 1_500)?.out.orEmpty()
            cachedLayers = listDump.lines()
                .map { normalizeLayerName(it) }
                .filter { it.isNotEmpty() }
            lastLayersAt = now
            lastLayersPkg = pkg
        }
        val filtered = cachedLayers.filterNot {
            it.startsWith("Bounds for") ||
                it.startsWith("Background for") ||
                it.startsWith("Task=") ||
                it.startsWith("Leaf:") ||
                it.startsWith("OneHanded:") ||
                it.startsWith("AppZoomOut:") ||
                it.startsWith("HideDisplayCutout:") ||
                it.startsWith("Display ") ||
                it.startsWith("Display Overlays") ||
                it.startsWith("Input Overlays") ||
                it.startsWith("Accessibility Overlays") ||
                it.startsWith("Ime") ||
                it.startsWith("SnapshotWindowParent") ||
                it.startsWith("home_task_overlay") ||
                it.contains("__@@DcDiming") ||
                it.contains("com.framescope.monitor") ||
                it.contains("StatusBar") ||
                it.contains("NavigationBar") ||
                it.contains("FakeGestureBar") ||
                it.contains("ScreenDecor") ||
                it.contains("NotificationShade")
        }
        val matching = filtered.filter { it.contains(pkg) }
        if (matching.isNotEmpty()) {
            val surfaceViews = matching.filter { it.contains("SurfaceView", ignoreCase = true) || it.contains("BLAST") }
            val others = matching.filterNot { it in surfaceViews }
            return (surfaceViews.takeLast(3) + others.takeLast(2)).distinct().take(4)
        }
        return filtered.takeLast(3)
    }

    private fun parseSurfaceFlingerOutput(dump: String): FpsState? {
        if (dump.isBlank()) return null
        val samples = parseLayerLatencies(dump)
        val sorted = pickMostRecent(samples)
        for (sample in sorted) {
            val deltas = sample.presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
            val stats = computeFrameStats(deltas)
            if (stats != null) {
                measuredLayer = sample.name.ifBlank { foregroundApp ?: "layer" }
                val frameCount = sample.presents.size - 1
                totalFrames += frameCount
                jankFrames += stats.jank

                history.add(stats.fps)
                while (history.size > 20) history.removeFirst()

                return FpsState(
                    fps = stats.fps,
                    frameTimeMs = stats.frameTimeMs,
                    history = history.toList(),
                    totalFrames = totalFrames,
                    jankFrames = jankFrames,
                    source = "SurfaceFlinger latency",
                    surface = measuredLayer,
                    valid = true,
                    reason = null
                )
            }
        }
        return null
    }

    private suspend fun sampleTimeStats(packageName: String): FpsState? {
        if (!timeStatsEnabled) {
            hub.execOrNull(ShellCommands.surfaceTimeStatsEnable(), 2_000)
            timeStatsEnabled = true
        }
        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsDump(), 2_500)?.out.orEmpty()
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

        var bestReading: TimeStats.Reading? = null
        var maxGrowth = 0L
        var jankGrowth = 0L
        for (reading in readings) {
            val before = previous[reading.layer] ?: continue
            val grown = reading.frames - before.frames
            if (grown > maxGrowth) {
                maxGrowth = grown
                bestReading = reading
                jankGrowth = (reading.jankyFrames - before.jankyFrames).coerceAtLeast(0L)
            }
        }

        val reading = bestReading ?: return null
        if (maxGrowth <= 0L) return null

        val fps = maxGrowth.toDouble() * 1_000_000_000.0 / elapsed
        if (fps < 1.0 || fps > 360.0) return null

        measuredLayer = reading.layer
        totalFrames += maxGrowth.toInt()
        jankFrames += jankGrowth.toInt()

        history.add(fps.toFloat())
        while (history.size > 20) history.removeFirst()

        return FpsState(
            fps = fps.toFloat(),
            frameTimeMs = (1000.0 / fps).toFloat(),
            history = history.toList(),
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            source = "SurfaceFlinger timestats",
            surface = reading.layer,
            valid = true,
            reason = null
        )
    }

    private suspend fun sampleGfxInfo(pkg: String, targetFps: Int): FpsState? {
        val out = hub.execOrNull(ShellCommands.gfxInfo(pkg), 2_000)?.out.orEmpty()
        val timestamps = Parsers.parseGfxFrameStats(out)
        if (timestamps.size < 4) return null

        val totalDurationNs = timestamps.last() - timestamps.first()
        if (totalDurationNs <= 0L) return null

        val fps = (timestamps.size - 1).toDouble() * 1_000_000_000.0 / totalDurationNs
        if (fps < 1.0 || fps > 360.0) return null

        val frameTimeMs = (totalDurationNs.toDouble() / (timestamps.size - 1) / 1_000_000.0).toFloat()
        measuredLayer = "gfxinfo ($pkg)"

        history.add(fps.toFloat())
        while (history.size > 20) history.removeFirst()

        return FpsState(
            fps = fps.toFloat(),
            frameTimeMs = frameTimeMs,
            history = history.toList(),
            totalFrames = totalFrames + timestamps.size - 1,
            jankFrames = jankFrames,
            source = "dumpsys gfxinfo",
            surface = pkg,
            valid = true,
            reason = null
        )
    }

    private suspend fun currentPackage(): String? {
        // 1. Shell detection via dumpsys activity activities | grep mResumedActivity
        val out = hub.execOrNull(
            "{ dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' ; " +
                "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' ; } | head -3",
            2_000
        )?.out

        if (!out.isNullOrBlank()) {
            val detected = Parsers.foregroundPackage(out)
            if (!detected.isNullOrBlank() && detected != "null") return detected
        }

        // 2. Fallback: UsageStatsManager if granted
        val ctx = context
        if (ctx != null) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val end = System.currentTimeMillis()
                val begin = end - 300_000L
                val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end) }.getOrNull()
                val mostRecent = stats
                    ?.filter { it.lastTimeUsed > begin && it.totalTimeInForeground > 0L }
                    ?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null) {
                    return mostRecent.packageName
                }
            }
        }

        return null
    }

    private fun invalid(source: String, reason: String): FpsState = FpsState(
        fps = 0f,
        frameTimeMs = 0f,
        history = history.toList(),
        totalFrames = totalFrames,
        jankFrames = jankFrames,
        source = source,
        surface = null,
        valid = false,
        reason = reason
    )
}
