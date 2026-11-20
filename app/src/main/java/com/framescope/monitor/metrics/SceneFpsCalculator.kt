package com.framescope.monitor.metrics

import android.os.SystemClock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Scene-style FPS calculator.
 *
 * Unlike a naive "1 / delta" calculation, this measures the actual intervals
 * between frame presentation timestamps over a rolling time window. It deduplicates
 * timestamps (so ring-buffer overlaps don't inflate the count), clamps against the
 * display refresh rate (so jitter can't produce 124/240 fps spikes), and drops to
 * 0 FPS when no new frames arrive (so a frozen/static screen never shows a
 * stale high number).
 *
 * Usage:
 *   val calculator = SceneFpsCalculator(windowMs = 500L, staleTimeoutMs = 350L)
 *   // every poll:
 *   val result = calculator.calculate(newTimestampsNs, refreshRateHz, SystemClock.elapsedRealtime())
 *   // use result.fps, result.avgFrameTimeMs, result.jankFrames, result.isSceneActive
 */
data class SceneFpsResult(
    val fps: Int = 0,
    val avgFrameTimeMs: Float = 0f,
    val minFrameTimeMs: Float = 0f,
    val maxFrameTimeMs: Float = 0f,
    val jankFrames: Int = 0,
    val isSceneActive: Boolean = false
)

class SceneFpsCalculator(
    private val windowMs: Long = 500L,
    private val staleTimeoutMs: Long = 350L
) {
    private val seenTimestampsNs = LinkedHashSet<Long>(256)
    private val activeFrameTimesNs = ArrayDeque<Long>(256)
    private var lastNewFrameWallMs: Long = 0L

    fun reset() {
        seenTimestampsNs.clear()
        activeFrameTimesNs.clear()
        lastNewFrameWallMs = 0L
    }

    /**
     * Feed one poll of frame presentation timestamps.
     *
     * @param newPresentTimestampsNs List of frame present timestamps (ns) from the
     *        frame source (e.g., SurfaceFlinger layer actualPresentTime, frame
     *        timeline, or frame callback). Only NEW timestamps (not seen before)
     *        are processed.
     * @param refreshRateHz Current display refresh rate (e.g., 60f, 90f, 120f, 144f)
     * @param nowWallMs SystemClock.elapsedRealtime() or System.currentTimeMillis()
     */
    fun calculate(
        newPresentTimestampsNs: List<Long>,
        refreshRateHz: Float,
        nowWallMs: Long
    ): SceneFpsResult {
        val safeRefresh = max(refreshRateHz, 1f)
        val windowNs = windowMs * 1_000_000L

        var addedAny = false
        for (ts in newPresentTimestampsNs) {
            if (ts <= 0L || ts == Long.MAX_VALUE) continue
            if (seenTimestampsNs.add(ts)) {
                activeFrameTimesNs.addLast(ts)
                addedAny = true
            }
        }

        if (addedAny) {
            lastNewFrameWallMs = nowWallMs
        }

        // Keep seen set bounded so memory never grows over long sessions
        while (seenTimestampsNs.size > 1024) {
            val oldest = seenTimestampsNs.first()
            seenTimestampsNs.remove(oldest)
        }

        val latestTs = activeFrameTimesNs.lastOrNull()
        if (latestTs != null) {
            val cutoffNs = latestTs - windowNs
            while (activeFrameTimesNs.size > 1 && activeFrameTimesNs.first() < cutoffNs) {
                activeFrameTimesNs.removeFirst()
            }
        }

        // If no new frames arrived recently, scene is static/paused -> drop to 0 FPS
        val isStale = lastNewFrameWallMs == 0L || (nowWallMs - lastNewFrameWallMs) > staleTimeoutMs
        if (isStale || activeFrameTimesNs.size < 2) {
            return SceneFpsResult(
                fps = 0,
                avgFrameTimeMs = 0f,
                minFrameTimeMs = 0f,
                maxFrameTimeMs = 0f,
                jankFrames = 0,
                isSceneActive = false
            )
        }

        val firstNs = activeFrameTimesNs.first()
        val lastNs = activeFrameTimesNs.last()
        val spanNs = lastNs - firstNs
        if (spanNs <= 0L) {
            return SceneFpsResult(fps = 0, isSceneActive = false)
        }

        // The headline number is 1/median(delta), exactly as in the version
        // that measured games correctly. intervals/span is 1/mean(delta): one
        // idle gap inside the window turned a 120 fps game into a 12 fps
        // reading. The median is the interval the screen hit most often.
        val medianNs = medianIntervalNs(activeFrameTimesNs.toList())
            ?: return SceneFpsResult(fps = 0, isSceneActive = false)
        val rawFps = 1_000_000_000.0 / medianNs.toDouble()

        // Scene-style clamping: prevents jitter above panel refresh rate,
        // while keeping small tolerance (+1) for timestamp rounding
        val fps = rawFps.coerceIn(0.0, safeRefresh.toDouble() + 1.0).roundToInt()

        val expectedFrameMs = 1000f / safeRefresh
        val jankThresholdMs = expectedFrameMs * 1.5f + 2f

        var minMs = Float.MAX_VALUE
        var maxMs = 0f
        var janks = 0

        val frames = activeFrameTimesNs.toList()
        for (i in 1 until frames.size) {
            val dtMs = (frames[i] - frames[i - 1]) / 1_000_000.0
            val dtFloat = dtMs.toFloat()
            if (dtFloat < minMs) minMs = dtFloat
            if (dtFloat > maxMs) maxMs = dtFloat
            if (dtFloat > jankThresholdMs) janks++
        }

        return SceneFpsResult(
            fps = fps,
            // Derived from the same median as the FPS so the two always agree.
            avgFrameTimeMs = (medianNs.toDouble() / 1_000_000.0).toFloat(),
            minFrameTimeMs = if (minMs == Float.MAX_VALUE) 0f else minMs,
            maxFrameTimeMs = maxMs,
            jankFrames = janks,
            isSceneActive = true
        )
    }
}