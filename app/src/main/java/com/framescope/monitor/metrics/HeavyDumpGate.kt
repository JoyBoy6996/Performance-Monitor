package com.framescope.monitor.metrics

/**
 * The gate for the expensive SurfaceFlinger dumps.
 *
 * `dumpsys SurfaceFlinger --timestats -dump` prints the counters of every
 * layer of the phone. On a game that is hundreds of kilobytes, and the
 * compositor holds its own lock while it prints them, so the game feels every
 * one of them: the frames of the match stutter while the dump is made.
 *
 * The counters grow into a window over a second, so one dump a second is
 * enough to keep a rate coming. The cheap probe - the latency of the layer
 * that is on the screen - covers the polls in between, and the last reading
 * stays on the pill until a new one arrives.
 *
 * One gate is shared by every heavy dump in the app, so the device-wide
 * counter and the counter of the app in front cannot both print the whole
 * device within the same second.
 */
internal object HeavyDumpGate {

    /** The smallest gap between two heavy dumps. */
    const val GAP_MS = 900L

    private var lastAtMs = 0L

    /** Whether a dump has been made at all yet. */
    private var hasDump = false

    /**
     * Asks for the right to print the counters of the whole device.
     *
     * Returns true when the last dump is old enough and takes the gate, and
     * false when the device was asked too recently.
     */
    @Synchronized
    fun take(nowMs: Long): Boolean {
        if (hasDump && nowMs - lastAtMs < GAP_MS) return false
        hasDump = true
        lastAtMs = nowMs
        return true
    }

    /** Forgets the last dump, so the next one may be asked at once. */
    @Synchronized
    fun reset() {
        hasDump = false
        lastAtMs = 0L
    }
}
