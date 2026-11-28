package com.framescope.monitor.overlay

import kotlin.math.roundToInt

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

/**
 * The text of the frame time on the pill.
 *
 * The pill shows the reading and nothing else. The age of a reading - "old",
 * "4382 ms old" - was written into the middle of a run and told the reader
 * about the measurement instead of about the game. It is never shown: the
 * number is the reading, and how long ago it was taken is a detail of the
 * report, where it belongs.
 */
internal fun pillFrameTimeText(frameTimeMs: Float, valid: Boolean): String {
    if (!valid) return ""
    return "%.1f ms".format(frameTimeMs)
}
