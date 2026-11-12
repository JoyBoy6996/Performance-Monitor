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

        // 1. Primary: Query SurfaceFlinger latency for package or layers
        val sfCmd = "{ dumpsys SurfaceFlinger --latency '$pkg' 2>/dev/null ; " +
            ShellCommands.surfaceLatencyOfPackage(pkg) + " ; } | head -130"
        val dump = hub.execOrNull(sfCmd, 2_500)?.out.orEmpty()

        val parsed = parseSurfaceFlingerOutput(dump, targetFps)
        if (parsed != null && parsed.valid) {
            _state.value = parsed
            return parsed
        }

        // 2. Fallback: Default SurfaceFlinger active latency
        val defaultDump = hub.execOrNull(ShellCommands.surfaceLatencyDefault(), 1_500)?.out.orEmpty()
        val defaultParsed = parseSingleLatencyBlock("default", defaultDump, targetFps)
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

    private fun parseSurfaceFlingerOutput(dump: String, targetFps: Int): FpsState? {
        if (dump.isBlank()) return null

        // If batch output with @@@layer markers
        if (dump.contains("@@@")) {
            val sections = dump.split("@@@").filter { it.isNotBlank() }
            for (section in sections) {
                val lines = section.trim().lines()
                val layerName = lines.firstOrNull() ?: continue
                val content = lines.drop(1).joinToString("\n")
                val state = parseSingleLatencyBlock(layerName, content, targetFps)
                if (state != null && state.valid) {
                    measuredLayer = layerName
                    return state
                }
            }
        }

        // Single block
        val state = parseSingleLatencyBlock(pkgOrLayer = foregroundApp ?: "layer", dump = dump, targetFps = targetFps)
        if (state != null && state.valid) {
            measuredLayer = foregroundApp
            return state
        }
        return null
    }

    private fun parseSingleLatencyBlock(pkgOrLayer: String, dump: String, targetFps: Int): FpsState? {
        val lines = dump.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 4) return null

        val refreshPeriodNs = lines[0].trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 16_666_666L
        val effectiveRefreshNs = if (refreshPeriodNs in 2_000_000L..40_000_000L) refreshPeriodNs else 16_666_666L

        val presents = ArrayList<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (parts.isEmpty()) continue
            // 3-column layout: desired, actual, ready
            val actual = when {
                parts.size >= 2 -> parts[1]
                else -> parts[0]
            }
            if (actual > 0L && actual != Long.MAX_VALUE && actual != 9223372036854775807L) {
                presents.add(actual)
            }
        }

        if (presents.size < 3) return null

        val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
        if (deltas.size < 2) return null

        val targetIntervalMs = if (targetFps > 0) 1000f / targetFps else (effectiveRefreshNs / 1_000_000f)
        val jankThresholdNs = (targetIntervalMs * 1.5f * 1_000_000L).toLong()

        var janks = 0
        for (d in deltas) {
            if (d > jankThresholdNs) janks++
        }

        val totalTimeNs = presents.last() - presents.first()
        if (totalTimeNs <= 0L) return null

        val fps = (presents.size - 1).toDouble() * 1_000_000_000.0 / totalTimeNs
        if (fps <= 0.5 || fps > 360.0) return null

        val frameTimeMs = (totalTimeNs.toDouble() / (presents.size - 1) / 1_000_000.0).toFloat()

        history.add(fps.toFloat())
        while (history.size > 20) history.removeFirst()

        totalFrames += presents.size - 1
        jankFrames += janks

        return FpsState(
            fps = fps.toFloat(),
            frameTimeMs = frameTimeMs,
            history = history.toList(),
            totalFrames = totalFrames,
            jankFrames = jankFrames,
            source = "SurfaceFlinger latency",
            surface = pkgOrLayer,
            valid = true,
            reason = null
        )
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
                val begin = end - 10_000
                val stats = runCatching { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end) }.getOrNull()
                val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
                if (mostRecent != null && mostRecent.lastTimeUsed > begin) {
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
