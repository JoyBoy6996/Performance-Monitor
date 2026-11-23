package com.framescope.monitor.metrics

/** The numbers a frame-delta series produces. */
data class FrameStats(
    val fps: Float,
    val frameTimeMs: Float,
    val jank: Int
)

/**
 * The intervals of the last part of a series.
 *
 * A layer dump holds the last ~127 frames, which is more than two seconds at
 * 60 fps and four at 30 fps. A rate over all of them answers for the past
 * rather than for now, and a drop that just happened is spread over it until
 * it is gone. The intervals of the last second describe the moment.
 *
 * At least one interval is always kept, so a series that is slower than the
 * window is not thrown away.
 */
fun recentIntervals(deltas: List<Long>, windowNs: Long = RECENT_WINDOW_NS): List<Long> {
    if (deltas.isEmpty()) return deltas
    var total = 0L
    var start = deltas.size
    while (start > 0) {
        val span = total + deltas[start - 1]
        if (span > windowNs && start < deltas.size) break
        total = span
        start--
    }
    return deltas.subList(start, deltas.size)
}

/** The window a live frame rate is measured over. */
const val RECENT_WINDOW_NS = 800_000_000L

/**
 * Turns the intervals between presented frames into a frame rate.
 * Returns null when the series cannot be a frame rate (idle gaps, impossible
 * intervals, too little data).
 */
fun computeFrameStats(deltas: List<Long>): FrameStats? {
    if (deltas.size < 2) return null

    val sorted = deltas.sorted()
    val median = sorted[sorted.size / 2]

    // Reject idle gaps (> 1s) and impossibly fast frames (< 1ms = > 1000 fps)
    if (median < 1_000_000L || median > 1_000_000_000L) return null

    val totalNs = deltas.sum()
    if (totalNs <= 0L) return null

    val avgDeltaNs = totalNs.toDouble() / deltas.size
    val frameTimeMs = (avgDeltaNs / 1_000_000.0).toFloat()
    if (frameTimeMs <= 0f) return null

    val fps = 1000f / frameTimeMs
    if (fps < 1f || fps > 500f) return null

    // Jank rule: interval exceeds 1.5x the median interval of the series
    val jankThreshold = (median * 1.5).toLong()
    val jank = deltas.count { it > jankThreshold }

    return FrameStats(
        fps = fps,
        frameTimeMs = frameTimeMs,
        jank = jank
    )
}

/**
 * True when a frame rate can belong to this screen.
 *
 * A screen presents at most as many frames as it refreshes, so a number above
 * that is a counter that was read wrong and not a frame rate. A 144 Hz screen
 * cannot show 298 frames per second.
 */
fun isPlausibleFps(fps: Float, refreshRateHz: Float?): Boolean {
    if (fps <= 0f) return false
    val ceiling = (refreshRateHz?.takeIf { it > 0f } ?: 240f) * 1.05f
    return fps <= ceiling
}
