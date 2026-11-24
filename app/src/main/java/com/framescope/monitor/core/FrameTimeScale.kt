package com.framescope.monitor.core

/**
 * The top of the scale of a frame-time chart.
 *
 * A fixed 100 ms scale hides every stutter of a fast run: a 10 ms frame in a
 * 100 ms scale is a tenth of the height, and the chart looks flat. The scale
 * follows the run instead - a quarter above the worst frame, and never less
 * than 20 ms - and it is rounded to a clean step so the labels on the axis
 * are readable.
 *
 * The app and the report share this scale, so a run looks the same in both.
 */
fun frameTimeCeiling(series: List<Float>, recordedMaxMs: Float? = null): Float {
    val highest = maxOf(
        series.filter { it.finiteOrNull() != null && it > 0f }.maxOrNull() ?: 0f,
        recordedMaxMs?.takeIf { it.finiteOrNull() != null && it > 0f } ?: 0f
    )
    val wanted = maxOf(highest * 1.25f, 20f)
    return cleanScaleStep(wanted)
}

/**
 * A round number for the top of a scale.
 *
 * The number is always a multiple of a step that is small enough to keep the
 * curve readable and big enough to keep the labels whole.
 */
private fun cleanScaleStep(value: Float): Float {
    val step = when {
        value <= 25f -> 5f
        value <= 60f -> 10f
        value <= 120f -> 20f
        value <= 300f -> 50f
        else -> 100f
    }
    return kotlin.math.ceil(value / step).toFloat() * step
}
