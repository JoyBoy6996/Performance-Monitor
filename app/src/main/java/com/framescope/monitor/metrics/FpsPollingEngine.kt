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
 * One tick is one window wide: the counters are cleared right after they are
 * read, so the frames they hold are exactly the frames of the window. The
 * frame rate is the number of frames divided by the length of the window - no
 * averaging, no guessing.
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
        /** The frames that were late. */
        val janky: Long,
        /** The frames that missed a vsync. */
        val missed: Long,
        /** True when the app drew nothing because the screen was standing still. */
        val staticScreen: Boolean,
        /** How long the window was, in nanoseconds. */
        val windowNs: Long,
        /** The raw dump, for the report. */
        val raw: String
    )

    /** The last moment the counters were cleared, on the monotonic clock. */
    private var windowStartNs = 0L

    /** True when the device answered with counters at least once. */
    var countersAvailable: Boolean = false
        private set

    /**
     * Reads the frames of the app in front.
     *
     * Returns null when the counters are not there - an older Android, a build
     * without them - so that the next source gets its turn.
     */
    suspend fun tick(foregroundPackage: String): Tick? {
        val dump = hub.execOrNull(ShellCommands.surfaceTimeStatsWindow(), 3_000)?.out.orEmpty()
        val readings = TimeStats.readAllUnfiltered(dump)
        if (readings.isEmpty()) return null

        countersAvailable = true
        val now = System.nanoTime()
        val windowNs = if (windowStartNs > 0L) now - windowStartNs else 0L
        windowStartNs = now

        val best = select(readings, foregroundPackage) ?: return null
        return Tick(
            layer = best.layer,
            frames = best.frames,
            janky = best.jankyFrames,
            missed = best.missedVsync,
            staticScreen = best.frames <= 0L,
            windowNs = windowNs,
            raw = dump
        )
    }

    /**
     * Picks the layer that belongs to the app in front.
     *
     * The layers of the app itself win. A game often names its surface after
     * the engine and not after the package, so when no layer of the package
     * drew anything, the layer that drew the most is taken - but only when it
     * is clearly ahead of the others, because two layers that drew about the
     * same say nothing about which one is in front.
     */
    fun select(readings: List<TimeStats.Reading>, foregroundPackage: String): TimeStats.Reading? =
        selectCountedLayer(readings, foregroundPackage, ownPackage)
}

/**
 * Picks the layer that belongs to the app in front, out of the frame counters.
 *
 * The overlay of this app and the system bars draw on their own and are never
 * the app in front, so they are dropped before anything else is looked at.
 */
fun selectCountedLayer(
    readings: List<TimeStats.Reading>,
    foregroundPackage: String,
    ownPackage: String
): TimeStats.Reading? {
    val drawable = readings.filterNot { isNoiseLayer(it.layer, ownPackage) }
    if (drawable.isEmpty()) return null

    val own = drawable.filter {
        foregroundPackage.isNotBlank() &&
            it.layer.contains(foregroundPackage, ignoreCase = true)
    }
    val ownDrew = own.any { it.frames > 0L }
    val pool = if (ownDrew) own else drawable

    val best = pool.maxByOrNull { it.frames } ?: return null
    if (!ownDrew && pool.size > 1) {
        val second = pool.sortedByDescending { it.frames }[1]
        if (second.frames * 3 / 2 > best.frames) return null
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
