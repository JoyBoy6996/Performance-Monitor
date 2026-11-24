package com.framescope.monitor.ui.screens

import androidx.compose.ui.graphics.Color
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.ui.components.ChartEvent

/**
 * The moments worth pointing at in a run.
 *
 * The function is pure, so the moments can be checked without a screen.
 *
 * The old details screen drew these moments in a "What happened" timeline
 * above the cards. That timeline repeated what the cards already say, so it
 * was removed together with the rest of the duplicated top block. The list
 * itself is kept: it is the one place that turns a raw run into named
 * moments, and it is the same list the live screen and the comparison use.
 */
fun sessionEvents(session: SessionRecord, threshold: Int): List<ChartEvent> {
    val events = mutableListOf<ChartEvent>()
    val times = session.seriesTimesMs

    // A change of the frame rate cap is the most interesting thing in a run.
    session.capChanges.forEach { change ->
        val label = buildString {
            append("Frame rate cap ")
            append(change.fromFps?.toString() ?: "--")
            append(" → ")
            append(change.toFps)
            append(" fps")
            if (change.cause == "heat") append(" (heat)")
            change.temperatureC?.let { append(" · ${it.toInt()}°C") }
        }
        events += ChartEvent(
            timeMs = change.second * 1000L,
            label = label,
            color = Color(0xFF62A9FF)
        )
    }

    // A step of the thermal level.
    var lastLevel: Float? = null
    session.thermalStatusSeries.forEachIndexed { index, level ->
        if (level != lastLevel) {
            if (lastLevel != null) {
                val label = if (level > (lastLevel ?: 0f)) {
                    "Thermal level ${level.toInt()} (throttling)"
                } else {
                    "Thermal level back to ${level.toInt()}"
                }
                events += ChartEvent(
                    timeMs = times.getOrNull(index) ?: index * 1000L,
                    label = label,
                    color = Color(0xFFFFAA63)
                )
            }
            lastLevel = level
        }
    }

    // A second that fell far below the target.
    session.fpsSeries.forEachIndexed { index, fps ->
        if (fps.finiteOrNull() == null) return@forEachIndexed
        if (fps > 0f && fps < threshold * 0.6f) {
            events += ChartEvent(
                timeMs = times.getOrNull(index) ?: index * 1000L,
                label = "${fps.toInt()} fps, far below the target",
                color = Color(0xFFFF6B6B)
            )
        }
    }

    return events.sortedBy { it.timeMs }
}
