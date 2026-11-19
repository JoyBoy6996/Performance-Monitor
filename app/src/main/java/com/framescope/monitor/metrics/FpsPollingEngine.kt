package com.framescope.monitor.metrics

import com.framescope.monitor.shell.ShellCommands
import com.framescope.monitor.shell.ShellHub

/**
 * The frame rate of the app in front, counted by SurfaceFlinger itself.
 *
 * A game draws into a buffer queue that keeps no frame timeline, so the
 * presentation timestamps of its layer are empty - that is why the frame rate
 * of a game stayed empty while ordinary apps were measured. SurfaceFlinger
 * still counts the frames it presents, and those counters work for every app.
 *
 * The counters are cumulative, so one tick is the growth between two dumps
 * divided by the time between them. The dump is not cleared: a clear throws
 * away every counter SurfaceFlinger keeps, and a layer that has not presented
 * a frame in the next window would then disappear from the dump entirely -
 * which is exactly the case an idle game is in. Keeping the counters also
 * means the overlay does not disturb the counters of anything else.
 *
 * The moment a dump was taken is the midpoint of the call that took it. The
 * shell answers some time after SurfaceFlinger has written the text, so the
 * midpoint is the closest the app can get to the real instant without a clock
 * the two processes share.
 */
class FpsPollingEngine(
    private val hub: ShellHub,
    private val ownPackage: String
) {

    /** What one tick found. */
    data class Tick(
        /** The layer the frames came from. */
        val layer: String,
        /** The frames that layer presented during the window. */
        val frames: Long,
        /** The frames of the window that were late. */
        val janky: Long,
        /** The frames of the window that were dropped. */
        val missed: Long,
        /** How long the window was, in nanoseconds. */
        val windowNs: Long,
        /** The rate the app asked the display for, in fps. */
        val renderRate: Int?,
        /** The refresh rate the display presented this layer at, in Hz. */
        val displayRefreshRate: Int?,
        /** The raw dump, for the report. */
        val raw: String
    ) {
        /** True when the app drew nothing because the screen was standing still. */
        val staticScreen: Boolean get() = frames <= 0L
    }

    /** The counters of the previous dump, by layer name. */
    private var previous: Map<String, TimeStats.Reading> = emptyMap()

    /** When the previous dump was taken, on the monotonic clock. */
    private var previousAtNs = 0L

    private var enabled = false
    private var enableFailures = 0
    private var nextEnableAttemptNs = 0L

    /** True when the device answered with counters at least once. */
    var countersAvailable: Boolean = false
        private set

    /** Why the last tick could not produce a rate, when it could not. */
    var lastReason: String? = null
        private set

    /** Starts again, because the app in front changed. */
    fun reset() {
        previous = emptyMap()
        previousAtNs = 0L
        lastReason = null
    }

    /**
     * Reads the frames of the app in front.
     *
     * Returns null when the counters are not there - an older Android, a build
     * without them, a layer that just appeared - so that the next source gets
     * its turn.
     */
    suspend fun tick(foregroundPackage: String): Tick? {
        if (!ensureEnabled()) return null

        val before = System.nanoTime()
        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsDump(), DUMP_TIMEOUT_MS)
            ?.out.orEmpty()
        val after = System.nanoTime()

        val readings = TimeStats.readAllUnfiltered(dump)
        if (readings.isEmpty()) {
            lastReason = "SurfaceFlinger printed no layer counters"
            // Nothing is printed while the collection is switched off, and
            // something else on the phone may have cleared it. Asking again on
            // the next tick is cheaper than never asking again at all.
            enabled = false
            return null
        }
        countersAvailable = true

        val at = before + (after - before) / 2
        val earlier = previous
        val windowNs = if (previousAtNs > 0L) at - previousAtNs else 0L
        previous = readings.associateBy { it.layer }
        previousAtNs = at

        // The first dump only sets the baseline. A counter without a previous
        // value is a total since SurfaceFlinger was switched on, not a rate,
        // and dividing it by this window would report a huge frame rate.
        if (windowNs <= 0L || earlier.isEmpty()) return null

        val target = selectCountedLayer(readings, foregroundPackage, ownPackage, earlier)
        if (target == null) {
            lastReason = "No layer in the counters belongs to $foregroundPackage"
            return null
        }

        val old = earlier[target.layer]
        if (old == null) {
            // The layer was created after the previous dump. Its counter covers
            // an unknown span of time, so it is the next window that is usable.
            lastReason = "${target.layer} appeared in this window"
            return null
        }

        lastReason = null
        return Tick(
            layer = target.layer,
            frames = (target.frames - old.frames).coerceAtLeast(0L),
            janky = (target.jankyFrames - old.jankyFrames).coerceAtLeast(0L),
            missed = (target.missedVsync - old.missedVsync).coerceAtLeast(0L),
            windowNs = windowNs,
            renderRate = target.renderRate,
            displayRefreshRate = target.displayRefreshRate,
            raw = dump
        )
    }

    /**
     * Switches the collection on.
     *
     * SurfaceFlinger prints nothing at all until it has been asked to collect,
     * and it keeps collecting until something clears it. A failure is retried
     * with a growing wait instead of being remembered as a success.
     */
    private suspend fun ensureEnabled(): Boolean {
        if (enabled) return true
        val now = System.nanoTime()
        if (now < nextEnableAttemptNs) return false

        val result = hub.execOrNull(ShellCommands.surfaceTimeStatsEnable(), ENABLE_TIMEOUT_MS)
        if (result == null || !result.ok) {
            enableFailures++
            nextEnableAttemptNs = now + backoffNanos(enableFailures)
            lastReason = "SurfaceFlinger refused to start its frame counters"
            return false
        }
        enabled = true
        enableFailures = 0
        return true
    }

    /** The wait before the next attempt, growing with every failure. */
    private fun backoffNanos(failures: Int): Long {
        val step = failures.coerceIn(1, 6)
        return BASE_BACKOFF_NS * (1L shl (step - 1))
    }

    companion object {
        private const val ENABLE_TIMEOUT_MS = 2_000L

        /** A dump of every layer and its histograms needs room to arrive. */
        private const val DUMP_TIMEOUT_MS = 4_000L

        private const val BASE_BACKOFF_NS = 2_000_000_000L
    }
}

/**
 * Picks the layer that belongs to the app in front, out of the frame counters.
 *
 * The overlay of this app and the system bars draw on their own and are never
 * the app in front, so they are dropped before anything else is looked at.
 *
 * The layer that grew the most since [earlier] wins, because the app in front is
 * the one that is drawing. When nothing grew - the screen is standing still -
 * the layer with the most frames is the one that drew last, and its growth of
 * zero becomes the "nothing is drawing" answer. Passing no [earlier] baseline
 * ranks the layers by their totals instead, which is how the counters are
 * compared when no window is known.
 */
fun selectCountedLayer(
    readings: List<TimeStats.Reading>,
    foregroundPackage: String,
    ownPackage: String,
    earlier: Map<String, TimeStats.Reading> = emptyMap()
): TimeStats.Reading? {
    val drawable = readings.filterNot { isNoiseLayer(it.layer, ownPackage) }
    if (drawable.isEmpty()) return null

    fun growth(reading: TimeStats.Reading): Long =
        (reading.frames - (earlier[reading.layer]?.frames ?: 0L)).coerceAtLeast(0L)

    val own = drawable.filter { TimeStats.belongsTo(it, foregroundPackage) }
    val pool = if (own.isNotEmpty()) own else drawable

    val ranked = pool.sortedWith(
        compareByDescending<TimeStats.Reading> { growth(it) }
            .thenByDescending { it.frames }
    )
    val best = ranked.first()
    // Without a package name on any layer, the layer that grew the most is only
    // believable when it is ahead of the next one. Two layers that grew within
    // a few percent of each other say nothing about which one is in front.
    if (own.isEmpty() && ranked.size > 1 &&
        growth(ranked[1]) * 3L / 2L > growth(best)
    ) {
        return null
    }
    return best
}

/** True for the layers that never belong to the app in front. */
fun isNoiseLayer(layer: String, ownPackage: String): Boolean {
    val lower = layer.lowercase()
    if (ownPackage.isNotBlank() && lower.contains(ownPackage.lowercase())) return true
    return lower.contains("statusbar") ||
        lower.contains("navigationbar") ||
        lower.contains("systemui") ||
        lower.contains("shizuku") ||
        lower.contains("framescope")
}
