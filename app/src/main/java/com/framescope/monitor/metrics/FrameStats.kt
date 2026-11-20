package com.framescope.monitor.metrics

/** The numbers a frame-delta series produces. */
data class FrameStats(
    val fps: Float,
    val frameTimeMs: Float,
    val jank: Int,
    /** The median interval, in nanoseconds, the headline number came from. */
    val medianNs: Long = 0L
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

    // The headline number is the MEDIAN interval, exactly as in the version
    // that measured games correctly. 1/mean(delta) - which is the same as
    // (count-1)/span - lets a single idle gap drag a 120 fps stream down to a
    // reported 12 fps. The median ignores the occasional stall. The reported
    // frame time is derived from it so the two numbers always agree.
    val frameTimeMs = (median.toDouble() / 1_000_000.0).toFloat()
    if (frameTimeMs <= 0f) return null

    val fps = 1000f / frameTimeMs
    if (fps < 1f || fps > 500f) return null

    // Jank rule: interval exceeds 1.5x the median interval of the series
    val jankThreshold = (median * 1.5).toLong()
    val jank = deltas.count { it > jankThreshold }

    return FrameStats(
        fps = fps,
        frameTimeMs = frameTimeMs,
        jank = jank,
        medianNs = median
    )
}

/**
 * The median interval of a presentation series, in nanoseconds.
 *
 * Every live FPS calculator in the app uses this instead of 1/mean. One long
 * gap between frames must not turn a fast game into a slow reading; the median
 * is the interval the display actually hit most often.
 */
fun medianIntervalNs(presents: List<Long>): Long? {
    if (presents.size < 2) return null
    val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
    if (deltas.isEmpty()) return null
    val sorted = deltas.sorted()
    return sorted[sorted.size / 2]
}

/**
 * A presentation series that can describe a steady frame rate.
 *
 * The old sampler used this before it computed a number: at least four
 * present times, a median between 1 ms and 100 ms, and at least sixty percent
 * of the intervals clustered around the median. Idle layers have long gaps and
 * fail this, so their stray timestamps never become an FPS reading.
 */
fun isPlausibleSeries(presents: List<Long>, refreshPeriodNs: Long = 0L): Boolean {
    if (presents.size < 4) return false
    val deltas = presents.zipWithNext { a, b -> b - a }.filter { it > 0L }
    if (deltas.size < 3) return false
    val median = medianIntervalNs(presents) ?: return false
    if (median < 1_000_000L || median > 100_000_000L) return false
    if (refreshPeriodNs > 0L && median * 3 < refreshPeriodNs) return false
    val clustered = deltas.count { it >= median / 2 && it <= median * 2 }
    return clustered * 10 >= deltas.size * 6
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
