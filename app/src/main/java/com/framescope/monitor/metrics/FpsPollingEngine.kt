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
 * Two things make the number match what the phone really presented:
 *
 * 1. The window is a full second, not the gap between two polls. A game at
 *    120 fps presents 48 frames in 400 ms; one dump that arrives late moves
 *    that rate by several frames. Over a second the same delay moves it by
 *    less than one.
 * 2. A counter is identified by everything SurfaceFlinger keys it by - the
 *    layer, its uid and its refresh and render rates - and never by the layer
 *    name alone. A game that changes from its 60 fps menu to a 120 fps match
 *    gets a *new* counter that starts at zero, while the old one is still in
 *    the dump. Comparing one against the other invents a rate neither of them
 *    ever had.
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
        /** The frame rate of the window, already smoothed and clamped. */
        val fps: Double,
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

    /** One dump, kept only so the next dump has a baseline to grow from. */
    private data class Snapshot(val atNs: Long, val byKey: Map<String, TimeStats.Reading>)

    /** The last two seconds of dumps, for the one second window. */
    private val snapshots = ArrayDeque<Snapshot>()

    /** The last few rates of this counter, so one late dump cannot spike the number. */
    private val recentRates = ArrayDeque<Double>()

    /**
     * The counter that was measured last.
     *
     * When nothing is drawing every counter stands still and the ranking has
     * no growth to go by; the layer that was measured a moment ago is still
     * the app in front and keeps the answer.
     */
    private var lastTargetKey: String? = null

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
        snapshots.clear()
        recentRates.clear()
        lastTargetKey = null
        lastReason = null
    }

    /**
     * Reads the frames of the app in front.
     *
     * Returns null when the counters are not there - an older Android, a build
     * without them, a layer that just appeared - so that the next source gets
     * its turn.
     */
    suspend fun tick(foregroundPackage: String, displayHz: Float? = null): Tick? {
        if (!ensureEnabled()) return null

        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsDump(), DUMP_TIMEOUT_MS)
            ?.out.orEmpty()
        // The text was written by SurfaceFlinger before the shell handed it
        // over, so the moment the answer arrived is closer to the dump than the
        // middle of the call. Under load - in a fight - a dump can take a large
        // part of the window, and half of that delay is exactly the error that
        // turns 120 into 123.
        val at = System.nanoTime()

        val readings = TimeStats.readAllUnfiltered(dump)
        if (readings.isEmpty()) {
            lastReason = "SurfaceFlinger printed no layer counters"
            // Nothing is printed while the collection is switched off, and
            // something else on the phone may have cleared it. Asking again on
            // the next tick is cheaper than never asking again at all.
            enabled = false
            snapshots.clear()
            recentRates.clear()
            return null
        }
        countersAvailable = true

        val latest = readings.associateBy { it.key }
        val earlier = snapshots.lastOrNull()?.byKey.orEmpty()

        snapshots.addLast(Snapshot(at, latest))
        while (snapshots.size > 1 && at - snapshots.first().atNs > HISTORY_NS) {
            snapshots.removeFirst()
        }

        // The first dump only sets the baseline. A counter without a previous
        // value is a total since SurfaceFlinger was switched on, not a rate,
        // and dividing it by this window would report a huge frame rate.
        if (earlier.isEmpty()) {
            lastReason = "Waiting for the next SurfaceFlinger dump"
            return null
        }

        val target = selectCountedLayer(
            readings,
            foregroundPackage,
            ownPackage,
            earlier,
            lastTargetKey
        )
        if (target == null) {
            lastReason = "No layer in the counters belongs to $foregroundPackage"
            return null
        }

        // The baseline is the newest dump that is at least a window old and
        // still holds this counter. When the counter is younger than that, the
        // oldest dump that holds it is the best window there is.
        val baseline = snapshots.lastOrNull {
            it.atNs <= at - WINDOW_NS && it.byKey.containsKey(target.key)
        } ?: snapshots.firstOrNull { it.byKey.containsKey(target.key) }

        val old = baseline?.byKey?.get(target.key)
        val windowNs = if (baseline != null) at - baseline.atNs else 0L
        if (old == null || windowNs <= 0L) {
            lastReason = "${target.layer} appeared in this window"
            return null
        }
        if (target.frames < old.frames) {
            // The counter went backwards. That is not a drop to zero, it is a
            // different counter wearing the same name.
            lastReason = "${target.layer} started a new counter"
            return null
        }

        lastTargetKey = target.key
        lastReason = null

        val frames = target.frames - old.frames
        val rate = if (frames <= 0L) 0.0 else frames * 1_000_000_000.0 / windowNs
        if (frames > 0L) {
            recentRates.addLast(rate)
            while (recentRates.size > RATE_SMOOTHING) recentRates.removeFirst()
        } else {
            recentRates.clear()
        }

        // The middle of the last few one-second windows. A single dump that
        // arrives late can move one window by a frame or two; it cannot move
        // the middle of three.
        var fps = if (frames <= 0L) 0.0 else recentRates.sorted()[recentRates.size / 2]

        // A screen cannot present more frames than it refreshes. The rate
        // SurfaceFlinger recorded for this very layer is the true ceiling, and
        // it is bucketed in steps of 30 Hz - so 144 Hz reads as 150 and a
        // correct 148 fps is never clipped.
        val ceiling = target.displayRefreshRate?.toFloat() ?: displayHz
        if (ceiling != null && ceiling > 0f && fps > ceiling * 1.02) {
            if (fps > ceiling * 1.5) {
                lastReason = "The counters of ${target.layer} read faster than the display"
                return null
            }
            fps = ceiling.toDouble()
        }

        return Tick(
            layer = target.layer,
            frames = frames,
            janky = (target.jankyFrames - old.jankyFrames).coerceAtLeast(0L),
            missed = (target.missedVsync - old.missedVsync).coerceAtLeast(0L),
            windowNs = windowNs,
            fps = fps,
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

        /** The window one reading covers. */
        private const val WINDOW_NS = 1_000_000_000L

        /** How much history is kept, so a one second window always has a base. */
        private const val HISTORY_NS = 2_000_000_000L

        /** How many one second windows the middle is taken from. */
        private const val RATE_SMOOTHING = 3

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
 * the one that is drawing. A counter with no entry in [earlier] grew by nothing
 * that can be known: it is new, and its total is not a rate. When nothing grew -
 * the screen is standing still - the layer that was measured last, and then the
 * one with the most frames, is the answer. Passing no [earlier] baseline ranks
 * the layers by their totals instead.
 */
fun selectCountedLayer(
    readings: List<TimeStats.Reading>,
    foregroundPackage: String,
    ownPackage: String,
    earlier: Map<String, TimeStats.Reading> = emptyMap(),
    preferredKey: String? = null
): TimeStats.Reading? {
    val drawable = readings.filterNot { isNoiseLayer(it.layer, ownPackage) }
    if (drawable.isEmpty()) return null

    fun growth(reading: TimeStats.Reading): Long {
        val baseline = earlier[reading.key] ?: return 0L
        return (reading.frames - baseline.frames).coerceAtLeast(0L)
    }

    val own = drawable.filter { TimeStats.belongsTo(it, foregroundPackage) }
    val pool = if (own.isNotEmpty()) own else drawable

    val ranked = pool.sortedWith(
        compareByDescending<TimeStats.Reading> { growth(it) }
            .thenByDescending { if (preferredKey != null && it.key == preferredKey) 1 else 0 }
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
