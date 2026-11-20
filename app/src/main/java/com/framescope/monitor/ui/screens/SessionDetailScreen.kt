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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.core.steadyWindow
import com.framescope.monitor.core.thermalStatusLabel
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.MetricLine
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.StatCell
import com.framescope.monitor.ui.components.StatusPill
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One formatter for every number on this screen: bad values become "--", never NaN. */
private fun fmt(value: Float?, decimals: Int, unit: String = ""): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number) + unit
}

private fun List<Float>.averageOrNull(): Float? =
    if (isEmpty()) null else average().toFloat().finiteOrNull()

/** Short legend for the cap line, using only changes the sampler actually confirmed. */
private fun capLegend(session: SessionRecord): String? {
    val changes = session.capChanges
    if (changes.isEmpty()) return null
    val first = changes.first()
    val last = changes.last()
    val path = if (changes.size == 1) {
        fmt(last.toFps.toFloat(), 0)
    } else {
        "${fmt(first.toFps.toFloat(), 0)} → ${fmt(last.toFps.toFloat(), 0)}"
    }
    return "Gold line: measured cap $path fps" +
        if (last.cause == "heat") " · heat" else ""
}

/**
 * The full breakdown of one recorded run: what the frame rate did, where it
 * dropped, how long the frames were, and what the device was doing at the time.
 */
@Composable
fun SessionDetailScreen(
    session: SessionRecord,
    threshold: Int,
    onBack: () -> Unit,
    bottomPadding: Dp = 24.dp
) {
    val formatter = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val stateColor = fpsStateColor(session.avgFps, threshold)
    val appName = session.appLabel.ifBlank {
        session.appPackage.ifBlank { "Unknown app" }
    }

    FrameScopeScreen(
        title = appName,
        subtitle = "${formatter.format(Date(session.startedAt))} · ${formatDuration(session.durationMs)}",
        bottomPadding = bottomPadding,
        actions = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        item { SectionHeader("Overview") }

        item {
            Panel {
                Row {
                    StatCell(
                        label = "Average",
                        value = fmt(session.avgFps, 1),
                        unit = "fps",
                        accent = stateColor,
                        modifier = Modifier.weight(1f).padding(end = Space.sm)
                    )
                    StatCell(
                        label = "Peak",
                        value = fmt(session.maxFps, 0),
                        unit = "fps",
                        accent = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                    )
                    StatCell(
                        label = "Lowest",
                        value = fmt(session.minFps, 0),
                        unit = "fps",
                        accent = MaterialTheme.status.critical,
                        modifier = Modifier.weight(1f).padding(start = Space.sm)
                    )
                }
                Row(Modifier.padding(top = Space.md)) {
                    StatCell(
                        label = "1% low",
                        value = fmt(session.low1Percent, 1),
                        unit = "fps",
                        accent = MaterialTheme.status.warn,
                        modifier = Modifier.weight(1f).padding(end = Space.sm)
                    )
                    StatCell(
                        label = "5% low",
                        value = fmt(session.low5Percent, 1),
                        unit = "fps",
                        accent = MaterialTheme.status.warn,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                    )
                    StatCell(
                        label = "Smooth",
                        value = fmt(session.smoothness, 0),
                        unit = "%",
                        accent = MaterialTheme.status.good,
                        modifier = Modifier.weight(1f).padding(start = Space.sm)
                    )
                }
                Spacer(Modifier.height(Space.md))
                MetricLine(
                    label = "Frames",
                    value = session.totalFrames.toString(),
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Jank frames",
                    value = session.jankFrames.toString(),
                    accent = if (session.jankFrames > 0) {
                        MaterialTheme.status.warn
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                MetricLine(
                    label = "Average frame time",
                    value = fmt(session.avgFrameTimeMs, 2, " ms"),
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Longest frame",
                    value = fmt(session.longestFrameMs, 1, " ms"),
                    accent = if (session.longestFrameMs > 33f) {
                        MaterialTheme.status.critical
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                MetricLine(
                    label = "FPS range",
                    value = "${fmt(session.minFps, 0)}–${fmt(session.maxFps, 0)} fps",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val capNow = session.capSeries.lastOrNull()?.takeIf { it > 0f }
                MetricLine(
                    label = "Detected FPS cap",
                    value = if (capNow == null) "--" else {
                        fmt(capNow, 0, " fps") +
                            if (session.thermalCapSeconds > 0) " · heat" else ""
                    },
                    accent = if (session.thermalCapSeconds > 0) {
                        MaterialTheme.status.warn
                    } else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.capChanges.isNotEmpty()) {
                    MetricLine(
                        label = "Cap changes",
                        value = session.capChanges.size.toString(),
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val jitter = session.jitterMs ?: frameTimeJitterMs(session.frameTimeSeries)
                jitter?.let {
                    MetricLine(
                        label = "Jitter",
                        value = fmt(it, 2, " ms"),
                        accent = if (it > 3f) MaterialTheme.status.warn
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val variance = session.fpsVariance ?: fpsVariance(session.fpsSeries)
                variance?.let {
                    MetricLine(
                        label = "FPS variance",
                        value = fmt(it, 2),
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                session.avgPowerW?.let {
                    MetricLine(
                        label = "Average power",
                        value = fmt(it, 2, " W"),
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val peakTemp = session.maxTempC.plausibleTempC(MAX_HOTSPOT_TEMP_C)
                peakTemp?.let {
                    MetricLine(
                        label = "Peak temperature",
                        value = fmt(it, 1, "°C"),
                        accent = MaterialTheme.status.warn
                    )
                }
                val finalTemp = session.tempSeries.lastOrNull().plausibleTempC(MAX_HOTSPOT_TEMP_C)
                if (peakTemp != null && finalTemp != null && peakTemp - finalTemp >= 0.1f) {
                    MetricLine(
                        label = "Cooled from peak",
                        value = fmt(peakTemp - finalTemp, 1, "°C"),
                        accent = MaterialTheme.status.good
                    )
                }
                session.peakThermalStatus?.let { status ->
                    MetricLine(
                        label = "Thermal state",
                        value = thermalStatusLabel(status),
                        accent = if (status > 0) {
                            MaterialTheme.status.warn
                        } else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (session.throttledSeconds > 0) {
                    MetricLine(
                        label = "Throttled time",
                        value = formatDuration(session.throttledSeconds * 1000L),
                        accent = MaterialTheme.status.warn
                    )
                }
                session.avgCpu?.let {
                    MetricLine(
                        label = "Average CPU",
                        value = fmt(it, 1, "%"),
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val battery = session.batterySeries
                if (battery.isNotEmpty()) {
                    MetricLine(
                        label = "Battery",
                        value = "${fmt(battery.first(), 0, "%")} → " +
                            fmt(battery.last(), 0, "%"),
                        accent = MaterialTheme.status.good
                    )
                }
                Spacer(Modifier.height(Space.sm))
                StatusPill(
                    text = when {
                        session.avgFps >= threshold -> "Held target"
                        session.smoothness >= 90f -> "Mostly held target"
                        else -> "Missed target"
                    },
                    color = stateColor
                )
            }
        }

        item { SectionHeader("Frame rate") }

        item {
            SeriesPanel(
                title = "Frames per second",
                values = session.fpsSeries,
                unit = "fps",
                color = stateColor,
                target = threshold.toFloat(),
                targetLabel = "$threshold fps target",
                reference = session.capSeries,
                referenceLabel = capLegend(session)
            )
        }

        item { SectionHeader("Frame time") }

        item {
            SeriesPanel(
                title = "Milliseconds per frame",
                values = session.frameTimeSeries,
                unit = "ms",
                color = MaterialTheme.status.warn,
                invert = true
            )
        }

        item { SectionHeader("Device during the run") }

        item {
            SeriesPanel(
                title = "CPU load",
                values = session.cpuSeries,
                unit = "%",
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            SeriesPanel(
                title = "Temperature",
                values = session.tempSeries,
                unit = "°C",
                color = MaterialTheme.status.critical,
                filter = { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) != null }
            )
        }

        if (session.thermalStatusSeries.isNotEmpty()) {
            item {
                SeriesPanel(
                    title = "Thermal state",
                    values = session.thermalStatusSeries,
                    unit = "level",
                    color = MaterialTheme.status.warn,
                    targetLabel = "0 = none · 1 = light · 2 = moderate · 3+ = severe"
                )
            }
        }

        if (session.batterySeries.isNotEmpty()) {
            item {
                SeriesPanel(
                    title = "Battery",
                    values = session.batterySeries,
                    unit = "%",
                    color = MaterialTheme.status.good
                )
            }
        }

        if (session.capChanges.isNotEmpty()) {
            item { SectionHeader("FPS cap changes") }

            item { CapChangesPanel(session) }
        }

        item { SectionHeader("Linked timeline") }

        item { LinkedTimelinePanel(session, threshold) }

        item { SectionHeader("Frame drops") }

        item { DropsPanel(session, threshold) }
    }
}

/** One second with every reading that belongs to it. */
private data class TimelineMoment(
    val second: Int,
    val fps: Float,
    val tempC: Float,
    val cpu: Float,
    val level: Int?,
    val capFps: Float? = null
)

/** Carries the last real reading across placeholder gaps without shifting the axis. */
private fun List<Float>.sanitizeTemps(): List<Float> {
    var lastReal = 0f
    return map { value ->
        if (value.plausibleTempC(MAX_HOTSPOT_TEMP_C) != null) {
            lastReal = value
            value
        } else lastReal
    }
}

/** Mean change between consecutive frame times, in milliseconds. */
private fun frameTimeJitterMs(series: List<Float>): Float? {
    val valid = series.filter { it.finiteOrNull() != null && it > 0f }
    if (valid.size < 2) return null
    var total = 0.0
    for (i in 1 until valid.size) {
        total += kotlin.math.abs(valid[i] - valid[i - 1]).toDouble()
    }
    return (total / (valid.size - 1)).toFloat()
}

/** Spread of the per-second frame rate, in fps². Older sessions compute it here. */
private fun fpsVariance(series: List<Float>): Float? {
    val valid = series.filter { it.finiteOrNull() != null && it > 0f }
    if (valid.size < 2) return null
    val mean = valid.average()
    var total = 0.0
    for (value in valid) {
        val delta = value - mean
        total += delta * delta
    }
    return (total / valid.size).toFloat()
}

/**
 * The readings share one second-by-second axis, so a drop can be matched with
 * the temperature, CPU load and thermal state at that exact moment.
 */
@Composable
private fun LinkedTimelinePanel(session: SessionRecord, threshold: Int) {
    val fps = session.fpsSeries
    val temp = session.tempSeries.sanitizeTemps()
    val cpu = session.cpuSeries
    val thermal = session.thermalStatusSeries
    val cap = session.capSeries
    val count = minOf(fps.size, temp.size, cpu.size)
    val fpsColor = fpsStateColor(session.avgFps, threshold)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Panel {
        Text(
            text = "Same second, side by side",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.sm))

        if (count < 2) {
            Text(
                text = "No samples recorded",
                style = MaterialTheme.typography.bodyMedium,
                color = muted
            )
            return@Panel
        }

        @Composable
        fun chart(label: String, values: List<Float>, color: Color) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = muted)
            Sparkline(
                values = values.take(count),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                color = color,
                trackColor = MaterialTheme.status.track,
                gridColor = MaterialTheme.status.grid
            )
            Spacer(Modifier.height(Space.xs))
        }

        chart("FPS", fps, fpsColor)
        if (cap.size >= count && cap.take(count).any { it > 0f }) {
            chart("FPS cap (measured)", cap, MaterialTheme.status.warn)
        }
        chart("Temperature (°C)", temp, MaterialTheme.status.critical)
        chart("CPU (%)", cpu, MaterialTheme.colorScheme.primary)
        if (thermal.size >= count) {
            chart("Thermal level (0-3+)", thermal, MaterialTheme.status.warn)
        }

        Spacer(Modifier.height(Space.sm))

        val moments = (0 until count).map { i ->
            TimelineMoment(
                second = i,
                fps = fps[i],
                tempC = temp[i],
                cpu = cpu[i],
                level = thermal.getOrNull(i)?.toInt(),
                capFps = cap.getOrNull(i)?.takeIf { it > 0f }
            )
        }.sortedBy { it.fps }.take(8).sortedBy { it.second }

        Text(
            text = "Worst moments",
            style = MaterialTheme.typography.labelSmall,
            color = muted
        )
        moments.forEach { moment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${moment.second + 1}s",
                    style = MonoInline,
                    color = muted
                )
                Text(
                    text = buildString {
                        append(fmt(moment.fps, 0)).append(" fps · ")
                        append(fmt(moment.tempC, 1)).append("°C · ")
                        append(fmt(moment.cpu, 0)).append("% CPU · ")
                        append(
                            moment.level?.let { thermalStatusLabel(it) } ?: "--"
                        )
                        if (moment.capFps != null) {
                            append(" · cap ").append(fmt(moment.capFps, 0))
                        }
                    },
                    style = MonoInline,
                    color = if (moment.fps < threshold) {
                        MaterialTheme.status.warn
                    } else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/** The moments the game's own rate changed, including changes back up after 90 → 120. */
@Composable
private fun CapChangesPanel(session: SessionRecord) {
    Panel {
        Text(
            text = "Confirmed from presented frames",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.sm))
        session.capChanges.forEach { change ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "at ${change.second + 1}s",
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = buildString {
                        append(change.fromFps?.let { fmt(it.toFloat(), 0) } ?: "--")
                        append(" → ")
                        append(fmt(change.toFps.toFloat(), 0))
                        append(" fps")
                        if (change.cause == "heat") append(" · heat")
                        if (change.thermalStatus != null) {
                            append(" · ")
                            append(thermalStatusLabel(change.thermalStatus))
                        }
                    },
                    style = MonoInline,
                    color = if (change.cause == "heat") {
                        MaterialTheme.status.warn
                    } else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * A chart with its numbers, so the curve is never the only thing on screen.
 */
@Composable
private fun SeriesPanel(
    title: String,
    values: List<Float>,
    unit: String,
    color: Color,
    target: Float? = null,
    targetLabel: String? = null,
    invert: Boolean = false,
    /** Drops placeholder readings so they cannot ruin the scale or the stats. */
    filter: (Float) -> Boolean = { true },
    /** The observed cap, drawn as a step line behind the real frame-rate curve. */
    reference: List<Float>? = null,
    referenceLabel: String? = null
) {
    val shown = values.filter { it.finiteOrNull() != null && filter(it) }
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = shown.lastOrNull()?.let { fmt(it, 1, " $unit") } ?: "--",
                style = MonoInline,
                color = color
            )
        }

        Spacer(Modifier.height(Space.sm))

        if (shown.size < 2) {
            Text(
                text = "No samples recorded",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Panel
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            Sparkline(
                values = shown,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = Space.sm),
                color = color,
                target = target,
                trackColor = MaterialTheme.status.track,
                gridColor = MaterialTheme.status.grid,
                reference = reference
            )
        }

        Spacer(Modifier.height(Space.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SeriesStat("Min", shown.minOrNull(), unit)
            SeriesStat("Average", shown.averageOrNull(), unit)
            SeriesStat("Max", shown.maxOrNull(), unit)
            if (target != null) {
                SeriesStat("Target", target, "fps")
            }
        }

        if (referenceLabel != null) {
            Text(
                text = referenceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.status.warn,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        if (targetLabel != null) {
            Text(
                text = targetLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        if (invert) {
            Text(
                text = "Higher means more jitter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

@Composable
private fun SeriesStat(label: String, value: Float?, unit: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = fmt(value, 1, " $unit"),
            style = MonoInline,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The seconds where the frame rate fell below the target, worst first. */
@Composable
private fun DropsPanel(session: SessionRecord, threshold: Int) {
    // Seconds before the run reached its top rate are a chosen rate, not a
    // drop, so they are left out of the list.
    val series = session.fpsSeries
    val steady = series.steadyWindow()
    val offset = series.size - steady.size
    val drops = steady
        .mapIndexed { index, fps -> (index + offset) to fps }
        .filter { it.second < threshold }
        .sortedBy { it.second }

    if (drops.isEmpty()) {
        Panel {
            Text(
                text = "No frame rate drops below $threshold fps in this run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Panel {
        Text(
            text = "${drops.size} of ${steady.size} seconds below $threshold fps",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.sm))
        drops.take(10).forEach { (second, fps) ->
            val frameTime = session.frameTimeSeries.getOrNull(second)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "at ${second + 1}s",
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = buildString {
                        append(fmt(fps, 0))
                        append(" fps")
                        if (frameTime.finiteOrNull() != null) {
                            append(" · ")
                            append(fmt(frameTime, 1))
                            append(" ms")
                        }
                    },
                    style = MonoInline,
                    color = if (fps < threshold * 0.75f) {
                        MaterialTheme.status.critical
                    } else {
                        MaterialTheme.status.warn
                    }
                )
            }
        }
        if (drops.size > 10) {
            Text(
                text = "… and ${drops.size - 10} more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
