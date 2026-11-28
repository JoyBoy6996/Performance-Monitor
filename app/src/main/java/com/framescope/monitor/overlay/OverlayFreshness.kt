package com.framescope.monitor.overlay

import kotlin.math.roundToInt

/**
 * How old a reading may be before the pill says so.
 *
 * A reading of a game comes from the compositor, and the compositor is asked
 * for the counters of every layer of the phone. On a phone with many layers
 * that one answer takes a second and a half, and a rate needs two of them -
 * the counter is a total, and a rate is the growth between two totals. A
 * reading is therefore a little under three seconds old by the time it
 * reaches the pill, even when everything worked.
 *
 * Calling every one of those readings out of date was wrong: the pill said
 * "old" on every screen, over and over, and the number it showed was the
 * best one there was. Four seconds is the line: a reading younger than that
 * is as fresh as this device can make it, and an older one is marked.
 */
internal object OverlayFreshness {

    /** A reading older than this is out of date. */
    const val STALE_AFTER_MS = 4_000L

    /** Whether the reading is too old to be shown as a live one. */
    fun isStale(sampleAgeMs: Long): Boolean = sampleAgeMs > STALE_AFTER_MS
}

/**
 * The rate of the panel, as the pill shows it.
 *
 * The panel of an LTPO screen can do 144 frames a second while it runs at
 * 120, and the number next to the frame rate has to be the one the screen
 * can do - a reading of 120 next to a label of 120/144 looks like a fault.
 */
internal fun panelRateLabel(maxHz: Float?, nowHz: Float?): String {
    val max = maxHz?.takeIf { it > 0f } ?: return nowHz?.let { "${it.roundToInt()} Hz" } ?: ""
    val now = nowHz?.takeIf { it > 0f }
    return if (now != null && now < max - 0.5f) {
        "${now.roundToInt()}/${max.roundToInt()} Hz"
    } else {
        "${max.roundToInt()} Hz"
    }
}
