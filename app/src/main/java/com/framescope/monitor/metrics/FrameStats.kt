package com.framescope.monitor.metrics

/** The numbers a frame-delta series produces. */
data class FrameStats(
    val fps: Float,
    val frameTimeMs: Float,
    val jank: Int
)

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
