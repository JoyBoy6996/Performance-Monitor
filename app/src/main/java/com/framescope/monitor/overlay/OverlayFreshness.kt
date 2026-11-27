package com.framescope.monitor.overlay

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
