package com.framescope.monitor.metrics

import kotlin.math.ceil

/**
 * Counts presented frames exactly once, however often the source repeats itself.
 *
 * SurfaceFlinger hands out a rolling history, not a delta. The same frame is therefore
 * visible in every poll until it falls out of the window, and a naive reader counts it
 * again and again. This class keeps a cursor into the history and only ever processes
 * timestamps it has not seen before.
 *
 * All state here belongs to one target - one layer of one app. When the target changes,
 * the caller starts a new counter rather than carrying the old one over.
 */
class FrameCounter(
    frameBudgetNs: Long,
    private val recentWindowNs: Long = DEFAULT_RECENT_WINDOW_NS,
    private val maxFrameSamples: Int = DEFAULT_MAX_FRAME_SAMPLES
) {

    /**
     * The frame budget a frame has to meet to count as smooth.
     *
     * Derived from the display mode, so a 120 Hz panel gets a 8.3 ms budget instead of
     * the 16.6 ms a 60 Hz panel gets. Changing it does not reclassify frames that were
     * already counted; it only applies to the frames that come after.
     */
    var frameBudgetNs: Long = frameBudgetNs.coerceAtLeast(1L)
        set(value) {
            field = value.coerceAtLeast(1L)
        }

    /** The layer this counter is following. */
    var layerName: String? = null
        private set

    /** The newest presentation timestamp that has been counted. */
    var cursorPresentNs: Long = Long.MIN_VALUE
        private set

    /** Frames counted since this target was acquired. */
    var totalUniqueFrames: Long = 0L
        private set

    /** Intervals that missed the frame budget since this target was acquired. */
    var totalJankFrames: Long = 0L
        private set

    /** When the last accepted frame was seen, on the monotonic clock. */
    var lastPresentElapsedNanos: Long = 0L
        private set

    /**
     * The newest accepted presentation timestamp, in the source's own clock.
     *
     * SurfaceFlinger reports presentation times on CLOCK_MONOTONIC, which is the same
     * base as [android.os.SystemClock.uptimeNanos]. Subtracting one from the other gives
     * the real age of the frame instead of the age of the reading.
     */
    var lastPresentSourceNs: Long = Long.MIN_VALUE
        private set

    private var previousPresentNs: Long = Long.MIN_VALUE
    private var startedAtElapsedNanos: Long = 0L
    private var baselined = false

    /** Presentation timestamps inside the current rolling window. */
    private val recentTimestamps = ArrayDeque<Long>()

    /** Frame times in milliseconds, bounded so a long run cannot grow without limit. */
    private val frameTimesMs = ArrayDeque<Double>()

    /**
     * The frame times in order, built once per poll and thrown away when a new frame
     * arrives.
     *
     * The percentiles need the samples in order, and sorting the whole history on every
     * poll is exactly the kind of waste this app should not have.
     */
    private var sortedFrameTimes: List<Double>? = null

    /** What one poll produced. */
    data class Result(
        /** Frames that were presented since the previous poll. */
        val newFrames: Int,
        /** Intervals that missed the frame budget since the previous poll. */
        val newJank: Int,
        /** The frame rate over the rolling window, or null when there is not enough data. */
        val recentFps: Float?,
        /** The mean frame time of the rolling window, in milliseconds. */
        val frameTimeMs: Float?,
        /** The frame rate over the whole time this target has been measured. */
        val averageFps: Float?,
        /** How old the newest accepted frame is, in milliseconds. */
        val sampleAgeMs: Long,
        /** The exact span of the rolling window, in milliseconds. */
        val windowMs: Long,
        /** True when the source restarted and the history had to be re-baselined. */
        val sourceReset: Boolean,
        /** True when the target changed and the counter started over. */
        val targetChanged: Boolean
    )

    /**
     * Feeds one poll into the counter.
     *
     * @param layer the layer the timestamps belong to
     * @param presents presentation timestamps, as reported by the source
     * @param nowElapsedNanos the current monotonic time
     */
    fun onSample(layer: String, presents: List<Long>, nowElapsedNanos: Long): Result {
        if (layerName != null && layerName != layer) {
            // A different layer means a different surface: start again from scratch
            // rather than counting one app's frames as another's.
            reset(layer)
            return Result(
                newFrames = 0,
                newJank = 0,
                recentFps = null,
                frameTimeMs = null,
                averageFps = null,
                sampleAgeMs = 0L,
                windowMs = 0L,
                sourceReset = false,
                targetChanged = true
            )
        }

        if (layerName == null) {
            layerName = layer
            startedAtElapsedNanos = nowElapsedNanos
        }

        if (presents.isEmpty()) {
            return Result(
                newFrames = 0,
                newJank = 0,
                recentFps = recentFps(),
                frameTimeMs = meanFrameTimeMs(),
                averageFps = averageFps(nowElapsedNanos),
                sampleAgeMs = sampleAgeMs(nowElapsedNanos),
                windowMs = windowMs(),
                sourceReset = false,
                targetChanged = false
            )
        }

        val newest = presents.last()

        // The source restarted its timeline: the timestamps we hold are from a different
        // epoch and nothing in them is a new frame. Re-baseline instead of counting them.
        if (baselined && newest < cursorPresentNs) {
            reset(layer)
            baselined = true
            cursorPresentNs = newest
            previousPresentNs = Long.MIN_VALUE
            lastPresentElapsedNanos = nowElapsedNanos
            return Result(
                newFrames = 0,
                newJank = 0,
                recentFps = null,
                frameTimeMs = null,
                averageFps = null,
                sampleAgeMs = 0L,
                windowMs = 0L,
                sourceReset = true,
                targetChanged = false
            )
        }

        // The very first sample only establishes the baseline; its frames are history we
        // did not observe being presented.
        if (!baselined) {
            baselined = true
            cursorPresentNs = newest
            previousPresentNs = newest
            lastPresentElapsedNanos = nowElapsedNanos
            return Result(
                newFrames = 0,
                newJank = 0,
                recentFps = null,
                frameTimeMs = null,
                averageFps = null,
                sampleAgeMs = 0L,
                windowMs = 0L,
                sourceReset = false,
                targetChanged = false
            )
        }

        val fresh = presents.filter { it > cursorPresentNs }
        var newFrames = 0
        var newJank = 0

        for (timestamp in fresh) {
            newFrames++
            val interval = if (previousPresentNs == Long.MIN_VALUE) 0L else timestamp - previousPresentNs

            if (interval > 0L && interval <= IDLE_GAP_NS) {
                if (interval > frameBudgetNs) newJank++
                frameTimesMs.addLast(interval / 1_000_000.0)
                sortedFrameTimes = null
                while (frameTimesMs.size > maxFrameSamples) frameTimesMs.removeFirst()
                recentTimestamps.addLast(timestamp)
            } else if (interval > IDLE_GAP_NS) {
                // More than a second without a frame means the app was not drawing.
                // That is not a very slow frame, it is an idle period, and the window
                // starts again from here.
                recentTimestamps.clear()
                recentTimestamps.addLast(timestamp)
            } else {
                previousPresentNs = timestamp
                recentTimestamps.addLast(timestamp)
            }
            previousPresentNs = timestamp
        }

        if (fresh.isNotEmpty()) {
            cursorPresentNs = fresh.last()
            lastPresentSourceNs = fresh.last()
            totalUniqueFrames += newFrames
            totalJankFrames += newJank
            lastPresentElapsedNanos = nowElapsedNanos
        }

        // Keep the rolling window bounded.
        val windowStart = cursorPresentNs - recentWindowNs
        while (recentTimestamps.isNotEmpty() && recentTimestamps.first() < windowStart) {
            recentTimestamps.removeFirst()
        }

        return Result(
            newFrames = newFrames,
            newJank = newJank,
            recentFps = recentFps(),
            frameTimeMs = meanFrameTimeMs(),
            averageFps = averageFps(nowElapsedNanos),
            sampleAgeMs = sampleAgeMs(nowElapsedNanos),
            windowMs = windowMs(),
            sourceReset = false,
            targetChanged = false
        )
    }

    /** The frame rate over the rolling window. */
    fun recentFps(): Float? {
        if (recentTimestamps.size < 2) return null
        val first = recentTimestamps.first()
        val last = recentTimestamps.last()
        val durationNs = last - first
        if (durationNs <= 0L) return null
        val intervals = recentTimestamps.size - 1
        return (intervals.toDouble() * 1_000_000_000.0 / durationNs).toFloat()
    }

    /** The mean frame time of the rolling window, in milliseconds. */
    fun meanFrameTimeMs(): Float? {
        if (recentTimestamps.size < 2) return null
        val first = recentTimestamps.first()
        val last = recentTimestamps.last()
        val durationNs = last - first
        if (durationNs <= 0L) return null
        return (durationNs.toDouble() / (recentTimestamps.size - 1) / 1_000_000.0).toFloat()
    }

    /** The span of the rolling window, in milliseconds. */
    fun windowMs(): Long {
        if (recentTimestamps.size < 2) return 0L
        return (recentTimestamps.last() - recentTimestamps.first()) / 1_000_000L
    }

    /** The frame rate over the whole time this target has been measured. */
    fun averageFps(nowElapsedNanos: Long): Float? {
        if (!baselined || totalUniqueFrames <= 0L) return null
        val elapsedNs = nowElapsedNanos - startedAtElapsedNanos
        if (elapsedNs <= 0L) return null
        return (totalUniqueFrames.toDouble() * 1_000_000_000.0 / elapsedNs).toFloat()
    }

    /** How long ago the reading was taken, in milliseconds. */
    fun sampleAgeMs(nowElapsedNanos: Long): Long {
        if (!baselined) return 0L
        return ((nowElapsedNanos - lastPresentElapsedNanos) / 1_000_000L).coerceAtLeast(0L)
    }

    /**
     * How old the newest accepted frame really is, in milliseconds.
     *
     * This is the age of the frame itself, measured in the source's clock, and it is the
     * number that says how far behind the screen the reading is.
     */
    fun frameAgeMs(nowSourceClockNanos: Long): Long {
        if (lastPresentSourceNs == Long.MIN_VALUE) return 0L
        return ((nowSourceClockNanos - lastPresentSourceNs) / 1_000_000L).coerceAtLeast(0L)
    }

    /**
     * The frame time at a given percentile, in milliseconds.
     *
     * `0.99` is the frame time that one percent of the frames were slower than, which
     * is the frame time the 1% low is derived from.
     */
    fun percentileFrameTimeMs(percentile: Double): Double? {
        if (frameTimesMs.isEmpty()) return null
        val sorted = sortedFrameTimes ?: frameTimesMs.sorted().also { sortedFrameTimes = it }
        val p = percentile.coerceIn(0.0, 1.0)
        val index = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    /**
     * The 1% low: the frame rate that one percent of the frames were slower than.
     *
     * Derived from frame times rather than from averaged frame rates, because an average
     * of frame rates cannot describe the worst frames of a run.
     */
    fun low1PercentFps(): Float? =
        percentileFrameTimeMs(0.99)?.takeIf { it > 0.0 }?.let { (1000.0 / it).toFloat() }

    /** The 5% low: the frame rate that five percent of the frames were slower than. */
    fun low5PercentFps(): Float? =
        percentileFrameTimeMs(0.95)?.takeIf { it > 0.0 }?.let { (1000.0 / it).toFloat() }

    /** Clears everything and starts again with a new target. */
    fun reset(layer: String? = null) {
        layerName = layer
        cursorPresentNs = Long.MIN_VALUE
        totalUniqueFrames = 0L
        totalJankFrames = 0L
        lastPresentElapsedNanos = 0L
        lastPresentSourceNs = Long.MIN_VALUE
        previousPresentNs = Long.MIN_VALUE
        startedAtElapsedNanos = 0L
        baselined = false
        recentTimestamps.clear()
        sortedFrameTimes = null
        frameTimesMs.clear()
    }

    companion object {
        /** A frame slower than this is a gap, not a frame. */
        const val IDLE_GAP_NS = 1_000_000_000L

        /** The default rolling window for the live frame rate. */
        const val DEFAULT_RECENT_WINDOW_NS = 800_000_000L

        /** How many frame times are kept for the percentiles. */
        const val DEFAULT_MAX_FRAME_SAMPLES = 4096
    }
}
