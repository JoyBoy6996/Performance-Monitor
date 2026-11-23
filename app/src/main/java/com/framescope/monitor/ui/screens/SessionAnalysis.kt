package com.framescope.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.ui.components.ChartEvent
import com.framescope.monitor.ui.components.ChartRange
import com.framescope.monitor.ui.components.ChartPoint
import com.framescope.monitor.ui.components.ChartSeries
import com.framescope.monitor.ui.components.ChipRow
import com.framescope.monitor.ui.components.InfoRow
import com.framescope.monitor.ui.components.LinkedChart
import com.framescope.monitor.ui.components.LinkedChartGroup
import com.framescope.monitor.ui.components.MiniStat
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.TelemetryColumn
import com.framescope.monitor.ui.components.TelemetryDataTable
import com.framescope.monitor.ui.components.TelemetryRow
import com.framescope.monitor.ui.components.chartPoints
import com.framescope.monitor.ui.components.withinWindow
import com.framescope.monitor.ui.components.formatElapsed
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.components.hasRealTimes
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.MonoValue
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.metric
import com.framescope.monitor.ui.theme.status

/** What the user wants to see of a run: the graphs or the numbers. */
enum class SessionView(val label: String) {
    CHARTS("Charts"),
    TABLE("Table")
}

/**
 * The switch between the graphs and the table.
 *
 * The choice is remembered while the screen is open. A run can be read both
 * ways: the graph shows how the run moved, the table shows every second.
 */
@Composable
fun SessionViewToggle(view: SessionView, onChange: (SessionView) -> Unit) {
    ChipRow(
        options = SessionView.entries.map { it.label },
        selected = setOf(view.label),
        onToggle = { label ->
            val entry = SessionView.entries.firstOrNull { it.label == label } ?: return@ChipRow
            if (entry != view) onChange(entry)
        }
    )
}

/**
 * The summary of a run.
 *
 * The numbers that decide whether the run was good are in the front, and every
 * one of them says where it came from: the average of the run, the lows, the
 * frames that were counted and the heat of the device.
 */
@Composable
fun SessionSummaryCard(session: SessionRecord, threshold: Int) {
    val stateColor = fpsStateColor(session.avgFps, threshold)
    val palette = MaterialTheme.metric
    val jankPercent = if (session.totalFrames > 0) {
        session.jankFrames * 100f / session.totalFrames
    } else {
        null
    }

    Panel(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Average frame rate",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format("%.1f", session.avgFps),
                        style = MonoValue,
                        color = stateColor
                    )
                    Text(
                        text = " fps",
                        style = MonoInline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Lowest seconds",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "1% ${String.format("%.0f", session.low1Percent)} · " +
                        "5% ${String.format("%.0f", session.low5Percent)}",
                    style = MonoInline,
                    color = palette.fps
                )
            }
        }

        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth()) {
            MiniStat(
                label = "Frames",
                value = session.totalFrames.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Jank",
                value = jankPercent?.let { String.format("%.1f", it) } ?: "--",
                unit = "%",
                color = if ((jankPercent ?: 0f) > 1f) {
                    MaterialTheme.status.warn
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Frame time",
                value = String.format("%.1f", session.avgFrameTimeMs),
                unit = "ms",
                color = palette.frameTime,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Length",
                value = formatElapsed(session.durationMs),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth()) {
            MiniStat(
                label = "Max temp",
                value = session.maxTempC?.let { String.format("%.0f", it) } ?: "--",
                unit = "°C",
                color = palette.temperature,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Avg CPU",
                value = session.avgCpu?.let { String.format("%.0f", it) } ?: "--",
                unit = "%",
                color = palette.cpu,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Smooth",
                value = session.sceneSmoothnessPercent?.let { String.format("%.0f", it) } ?: "--",
                unit = "%",
                color = palette.fps,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Panel",
                value = session.displayHz?.let { String.format("%.0f", it) } ?: "--",
                unit = "Hz",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth()) {
            MiniStat(
                label = "Cap",
                value = session.capChanges.lastOrNull()?.toFps?.toString()
                    ?: session.capSeries.lastOrNull()?.takeIf { it > 0f }?.toInt()?.toString()
                    ?: "--",
                unit = "fps",
                color = palette.fps,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Steadiness",
                value = session.jitterMs?.let { String.format("%.1f", it) } ?: "--",
                unit = "ms",
                color = palette.frameTime,
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Slow seconds",
                value = session.dropSeconds.toString(),
                unit = "s",
                color = if (session.dropSeconds > 0) {
                    MaterialTheme.status.warn
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                label = "Cap changes",
                value = session.capChanges.size.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The charts of a run.
 *
 * Every metric gets its own chart and its own scale, because two different
 * units on one pair of axes say something that is not true. The charts share
 * the moment that is picked: choosing a second in the frame rate also shows
 * what the temperature was at that second.
 */
@Composable
fun SessionChartsPanel(
    session: SessionRecord,
    threshold: Int,
    chartDescription: String = "The graphs of the run. The same numbers are in the table."
) {
    val palette = MaterialTheme.metric
    val times = session.seriesTimesMs
    val runEvents = sessionEvents(session, threshold)

    // A long run is hard to read as a whole, so the user can look at the last
    // minutes of it. The window ends at the last recorded moment, so "the last
    // minute" means the last minute of the recording and not of the clock.
    var range by remember(session.id) { mutableStateOf(ChartRange.FULL) }
    val ranges = remember(session.durationMs) { ChartRange.availableFor(session.durationMs) }

    val charts = buildList {
        add(
            LinkedChart(
                title = "Frame rate",
                series = listOf(
                    ChartSeries(
                        label = "fps",
                        unit = "fps",
                        color = palette.fps,
                        points = chartPoints(session.fpsSeries, times).withinRange(range),
                        min = 0f,
                        target = threshold.toFloat()
                    )
                ),
                events = runEvents.eventsWithin(range),
                target = threshold.toFloat()
            )
        )
        add(
            LinkedChart(
                title = "Frame time",
                series = listOf(
                    ChartSeries(
                        label = "frame time",
                        unit = "ms",
                        color = palette.frameTime,
                        points = chartPoints(session.frameTimeSeries, times).withinRange(range),
                        min = 0f
                    )
                )
            )
        )
        if (session.cpuSeries.any { it > 0f }) {
            add(
                LinkedChart(
                    title = "CPU",
                    series = listOf(
                        ChartSeries(
                            label = "cpu",
                            unit = "%",
                            color = palette.cpu,
                            points = chartPoints(session.cpuSeries, times).withinRange(range),
                            min = 0f,
                            max = 100f
                        )
                    )
                )
            )
        }
        if (session.gpuPercentSeries.any { it > 0f }) {
            add(
                LinkedChart(
                    title = "GPU",
                    series = listOf(
                        ChartSeries(
                            label = "gpu",
                            unit = "%",
                            color = palette.gpu,
                            points = chartPoints(session.gpuPercentSeries, times).withinRange(range),
                            min = 0f,
                            max = 100f
                        )
                    )
                )
            )
        }
        val temperature = session.socTempSeries.ifEmpty { session.tempSeries }
        if (temperature.any { it > 0f }) {
            add(
                LinkedChart(
                    title = "Temperature",
                    series = listOf(
                        ChartSeries(
                            label = "temperature",
                            unit = "°C",
                            color = palette.temperature,
                            points = chartPoints(temperature, times).withinRange(range)
                        )
                    )
                )
            )
        }
        if (session.batterySeries.any { it > 0f }) {
            add(
                LinkedChart(
                    title = "Battery",
                    series = listOf(
                        ChartSeries(
                            label = "battery",
                            unit = "%",
                            color = palette.battery,
                            points = chartPoints(session.batterySeries, times).withinRange(range),
                            min = 0f,
                            max = 100f
                        )
                    )
                )
            )
        }
    }

    if (charts.isEmpty()) {
        Panel {
            Text(
                text = "This run has no samples to draw.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Panel {
        if (ranges.size > 1) {
            ChipRow(
                options = ranges.map { it.label },
                selected = setOf(range.label),
                onToggle = { label ->
                    val entry = ranges.firstOrNull { it.label == label } ?: return@ChipRow
                    range = entry
                }
            )
            Spacer(Modifier.height(Space.sm))
        }
        if (!hasRealTimes(times, session.fpsSeries)) {
            Text(
                text = "This run was recorded before the app kept the moment of every " +
                    "second. The graphs use one second per sample.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Space.sm)
            )
        }
        LinkedChartGroup(charts = charts, chartHeight = 140.dp, description = chartDescription)
    }
}

/**
 * The table of a run.
 *
 * One row per second, with every reading that was really taken. A second that
 * had no reading is left out and the gap is named above the table.
 */
@Composable
fun SessionTablePanel(session: SessionRecord, threshold: Int) {
    val times = session.seriesTimesMs
    val columns = buildList {
        add(TelemetryColumn("fps", "fps"))
        add(TelemetryColumn("frame", "ms"))
        if (session.cpuSeries.any { it > 0f }) add(TelemetryColumn("cpu", "%"))
        if (session.gpuPercentSeries.any { it > 0f }) add(TelemetryColumn("gpu", "%"))
        val temperature = session.socTempSeries.ifEmpty { session.tempSeries }
        if (temperature.any { it > 0f }) add(TelemetryColumn("temp", "°C"))
        if (session.batterySeries.any { it > 0f }) add(TelemetryColumn("batt", "%"))
    }

    val rows = buildList {
        val count = maxOf(
            session.fpsSeries.size,
            session.frameTimeSeries.size,
            session.cpuSeries.size
        )
        for (index in 0 until count) {
            val fps = session.fpsSeries.getOrNull(index)
            val frameTime = session.frameTimeSeries.getOrNull(index)
            if (fps == null && frameTime == null) continue
            val cells = buildList {
                add(fps?.let { String.format("%.0f", it) } ?: "--")
                add(frameTime?.let { String.format("%.1f", it) } ?: "--")
                if (session.cpuSeries.any { it > 0f }) {
                    add(session.cpuSeries.getOrNull(index)?.let { String.format("%.0f", it) } ?: "--")
                }
                if (session.gpuPercentSeries.any { it > 0f }) {
                    add(session.gpuPercentSeries.getOrNull(index)?.let { String.format("%.0f", it) } ?: "--")
                }
                val temperature = session.socTempSeries.ifEmpty { session.tempSeries }
                if (temperature.any { it > 0f }) {
                    add(temperature.getOrNull(index)?.let { String.format("%.0f", it) } ?: "--")
                }
                if (session.batterySeries.any { it > 0f }) {
                    add(session.batterySeries.getOrNull(index)?.let { String.format("%.0f", it) } ?: "--")
                }
            }
            val time = times.getOrNull(index) ?: index * 1000L
            add(
                TelemetryRow(
                    timeMs = time,
                    cells = cells,
                    marked = fps != null && fps < threshold * 0.75f
                )
            )
        }
    }

    Panel {
        InfoRow(
            label = "Seconds recorded",
            value = "${rows.size}",
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (session.gapSeconds > 0) {
            InfoRow(
                label = "Seconds without a reading",
                value = "${session.gapSeconds}",
                valueColor = MaterialTheme.status.warn
            )
        }
        if (session.segments.size > 1) {
            InfoRow(
                label = "Measured from",
                value = "${session.segments.size} parts",
                valueColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.sm))
        TelemetryDataTable(columns = columns, rows = rows)
    }
}

/**
 * The events that fall inside a range of the run.
 */
private fun List<ChartEvent>.eventsWithin(range: ChartRange): List<ChartEvent> {
    val window = range.windowMs ?: return this
    if (isEmpty()) return this
    val end = last().timeMs
    val start = (end - window).coerceAtLeast(0L)
    return filter { it.timeMs in start..end }
}

/**
 * The part of a series that a range shows.
 *
 * The window ends at the last recorded moment of the series, so the last
 * minute of a run is really the last minute of the recording.
 */
fun List<ChartPoint>.withinRange(range: ChartRange): List<ChartPoint> {
    val window = range.windowMs ?: return this
    if (size < 2) return this
    val end = last().timeMs
    val start = (end - window).coerceAtLeast(0L)
    return withinWindow(start, end)
}

/**
 * The moments of a run that are worth pointing at.
 *
 * The events come from the recording itself: a change of the frame rate cap, a
 * step of the thermal level and a second that fell far below the target.
 */
@Composable
fun SessionTimelinePanel(session: SessionRecord, threshold: Int) {
    val runEvents = sessionEvents(session, threshold)
    if (runEvents.isEmpty()) {
        Panel {
            Text(
                text = "Nothing worth pointing at in this run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Panel {
        Text(
            text = "What happened",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.sm))
        runEvents.forEach { event ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatElapsed(event.timeMs),
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp)
                )
                BoxDot(event.color)
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = event.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BoxDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * The moments worth pointing at in a run.
 *
 * The function is pure, so the moments can be checked without a screen.
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
