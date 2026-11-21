package com.framescope.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.framescope.monitor.ui.components.SpikeChart
import com.framescope.monitor.ui.components.StatCell
import com.framescope.monitor.ui.components.StatusPill
import com.framescope.monitor.ui.components.TelemetryChart
import com.framescope.monitor.ui.components.TelemetrySeries
import com.framescope.monitor.ui.components.TimeAxis
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.theme.MonoBlock
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

/** The steady part of a run, without the start/stop settling samples. */
private fun steadyFps(session: SessionRecord): List<Float> = session.fpsSeries.steadyWindow()
    .ifEmpty { session.fpsSeries.filter { it.finiteOrNull() != null && it > 0f } }

/** Share of the run at or above a rate, from real recorded samples. */
private fun shareAtLeast(series: List<Float>, rate: Int): Float? {
    if (series.isEmpty()) return null
    return series.count { it >= rate }.toFloat().times(100f)
        .div(series.size).finiteOrNull()?.coerceIn(0f, 100f)
}

/**
 * Scene's "≥45 FPS Smoothness". Old runs did not save it, so it is recomputed
 * from their original per-second samples instead of being guessed.
 */
private fun sceneSmoothness(session: SessionRecord): Float? =
    session.sceneSmoothnessPercent.finiteOrNull()?.coerceIn(0f, 100f)
        ?: shareAtLeast(steadyFps(session), 45)

/** How much of the run actually held the chosen target. */
private fun targetHold(session: SessionRecord, threshold: Int): Float? =
    shareAtLeast(steadyFps(session), threshold)

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
    onUpdateNote: (String) -> Unit = {},
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
        item { SectionHeader("Test setup") }

        item {
            Panel {
                MetricLine(
                    label = "Device",
                    value = session.device.model ?: "--",
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Platform",
                    value = session.device.platform ?: "--",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricLine(
                    label = "Resolution",
                    value = session.device.resolution ?: "--",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricLine(
                    label = "OS",
                    value = session.device.osVersion ?: "--",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricLine(
                    label = "Target used",
                    value = "$threshold fps",
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { SectionHeader("Match stats") }

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
                        label = "5% low",
                        value = fmt(session.low5Percent, 1),
                        unit = "fps",
                        accent = MaterialTheme.status.warn,
                        modifier = Modifier.weight(1f).padding(end = Space.sm)
                    )
                    StatCell(
                        label = "Variance",
                        value = fmt(session.fpsVariance ?: fpsVariance(session.fpsSeries), 1),
                        accent = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                    )
                    StatCell(
                        label = "≥45 fps smooth",
                        value = fmt(sceneSmoothness(session), 1),
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
                    label = "Jank-free frames",
                    value = fmt(session.smoothness, 1, "%"),
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricLine(
                    label = "Held $threshold fps target",
                    value = fmt(targetHold(session, threshold), 1, "%"),
                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricLine(
                    label = "1% low",
                    value = fmt(session.low1Percent, 1, " fps"),
                    accent = MaterialTheme.status.warn
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
                        label = "Max temperature",
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
                        (targetHold(session, threshold) ?: 0f) >= 90f -> "Mostly held target"
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

        item { SectionHeader("Graph details") }

        item { ThermalWarningCard(session) }
        item { DeviceProfileCard(session) }
        item { SessionNoteCard(session, onUpdateNote) }
        item { AppMetricsCard(session) }
        item { FpsTemperatureCard(session, threshold) }
        item { FrameTimeCard(session) }
        item { CpuUsageCard(session) }
        item { CpuFrequencyCard(session) }
        item { PowerCapacityCard(session) }
        item { CpuTemperatureCard(session) }
        item { ShellLogsCard(session.shellLogs) }
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

/** Turns a stored nullable cluster line into a chart-safe list. */
private fun chartValues(values: List<Float?>): List<Float> =
    values.mapNotNull { it?.finiteOrNull() }

private fun positiveValues(values: List<Float>): List<Float> =
    values.mapNotNull { it.finiteOrNull()?.takeIf { value -> value > 0f } }

private fun plausibleValues(values: List<Float>): List<Float> =
    values.mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }

private fun maxCount(vararg lists: List<*>): Int = lists.maxOfOrNull { it.size } ?: 0

/** A small pad keeps a flat line from sitting on the frame of the chart. */
private fun paddedBounds(values: List<Float>): Pair<Float, Float> {
    val low = values.minOrNull() ?: 0f
    val high = values.maxOrNull() ?: 1f
    val span = (high - low).takeIf { it > 0f } ?: 1f
    return (low - span * 0.12f) to (high + span * 0.12f)
}

@Composable
private fun ThermalWarningCard(session: SessionRecord) {
    val peak = listOfNotNull(session.maxTempC, session.tempSeries.maxOrNull())
        .mapNotNull { it.plausibleTempC(MAX_HOTSPOT_TEMP_C) }
        .maxOrNull()
    val level = session.peakThermalStatus
    val hot = (level != null && level > 0) || session.throttledSeconds > 0
    if (!hot) {
        Panel {
            Text(
                text = "Thermal: no mitigation reported",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Peak temperature ${fmt(peak, 1, "°C")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    Panel(containerColor = Color(0xFF2B1A10)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "SOC temperature too high",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFF97316)
                )
                Text(
                    text = "MAX ${fmt(peak, 1, "°C")}",
                    style = MonoInline,
                    color = Color(0xFFF97316)
                )
            }
            StatusPill(
                text = "${thermalStatusLabel(level)} · ${session.throttledSeconds}s",
                color = Color(0xFFF97316)
            )
        }
    }
}

@Composable
private fun DeviceProfileCard(session: SessionRecord) {
    val entries = listOf(
        "Platform" to (session.device.platform ?: "--"),
        "Host" to (session.device.model ?: "--"),
        "OS" to (session.device.osVersion ?: "--"),
        "Profile" to thermalStatusLabel(session.peakThermalStatus)
    )
    Panel {
        Row(Modifier.fillMaxWidth()) {
            entries.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = value,
                        style = MonoInline,
                        color = if (value == "--") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionNoteCard(session: SessionRecord, onUpdateNote: (String) -> Unit) {
    var note by remember(session.id) { mutableStateOf(session.note) }
    var editing by remember(session.id) { mutableStateOf(false) }
    Panel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Session note",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = note.ifBlank { "Note not set, click here to enter" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else MaterialTheme.colorScheme.onSurface
                )
            }
            Text("Edit", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Session note") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2_000) },
                    label = { Text("Preset, cooler, ambient temperature...") },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateNote(note.trim())
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AppMetricsCard(session: SessionRecord) {
    val temps = plausibleValues(session.tempSeries)
    val avgTemp = temps.averageOrNull()
    val maxBatteryTemp = temps.maxOrNull()
    val jitterPct = session.jitterMs?.let { jitter ->
        session.avgFrameTimeMs.takeIf { it > 0f }?.let { jitter.div(it).times(100f) }
    }
    val timestamp = remember(session.id) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }.format(Date(session.startedAt))
    Panel {
        Row(Modifier.fillMaxWidth()) {
            StatCell("MAX", fmt(session.maxFps, 1), "FPS", MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            StatCell("MIN", fmt(session.minFps, 1), "FPS", MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            StatCell("AVG", fmt(session.avgFps, 1), "FPS", MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
            StatCell("JITTER", fmt(jitterPct, 1), "%", MaterialTheme.status.warn,
                modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth()) {
            StatCell("5% LOW", fmt(session.low5Percent, 1), "FPS", MaterialTheme.status.warn,
                modifier = Modifier.weight(1f))
            StatCell("AVG", fmt(avgTemp, 1), "SOC(°C)", MaterialTheme.status.critical,
                modifier = Modifier.weight(1f))
            StatCell("MAX", fmt(maxBatteryTemp, 1), "Battery(°C)", MaterialTheme.status.critical,
                modifier = Modifier.weight(1f))
            StatCell("AVG", fmt(session.avgPowerW, 2), "Power(W)", MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Space.md))
        Text(
            text = "avg: ${formatDuration(session.durationMs)} · $timestamp",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FpsTemperatureCard(session: SessionRecord, threshold: Int) {
    val fps = session.fpsSeries
    val temps = plausibleValues(session.tempSeries)
    val cpu = session.cpuSeries
    val gpu = positiveValues(session.gpuPercentSeries)
    val fpsBounds = paddedBounds(fps.ifEmpty { listOf(threshold.toFloat()) })
    val tempBounds = paddedBounds(temps)
    val lines = listOf(
        TelemetrySeries("FPS", fps, MaterialTheme.colorScheme.onSurface, "fps",
            fpsBounds.first, fpsBounds.second),
        TelemetrySeries("TEMP(°C)", temps, Color(0xFFF97316), "°C",
            tempBounds.first, tempBounds.second),
        TelemetrySeries("CPU(%)", cpu, Color(0xFFA78BFA), "%", 0f, 100f),
        TelemetrySeries("GPU(%)", gpu, Color(0xFF60A5FA), "%", 0f, 100f)
    )
    Panel {
        Text("FPS & Temperature(°C)", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        TelemetryChart(lines)
        TimeAxis(session.durationMs, maxCount(fps, temps, cpu, gpu))
        Text(
            text = "Each line uses its own range shown above; GPU appears only when the device reports load.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FrameTimeCard(session: SessionRecord) {
    Panel {
        Text("Frame Time(ms)", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        SpikeChart(session.frameTimeSeries, Color(0xFF7DD3FC))
        TimeAxis(session.durationMs, session.frameTimeSeries.size)
    }
}

@Composable
private fun CpuUsageCard(session: SessionRecord) {
    val groups = session.cpuGroupNames.mapIndexed { index, name ->
        TelemetrySeries(
            label = name,
            values = chartValues(session.cpuGroupSeries.getOrElse(index) { emptyList() }),
            color = groupColor(index),
            unit = "%",
            min = 0f,
            max = 100f
        )
    }
    val lines = listOf(
        TelemetrySeries("Total", session.cpuSeries, Color(0xFFA78BFA), "%", 0f, 100f)
    ) + groups
    Panel {
        Text("CPU usage(%)", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        TelemetryChart(lines)
        TimeAxis(session.durationMs, maxOf(
            session.cpuSeries.size,
            session.cpuGroupSeries.maxOfOrNull { it.size } ?: 0
        ))
        if (groups.isEmpty()) {
            Text("Per-cluster CPU load is not reported on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CpuFrequencyCard(session: SessionRecord) {
    val total = positiveValues(session.cpuFreqSeries)
    val ceiling = (total.maxOrNull() ?: 0f).coerceAtLeast(4_400f) * 1.05f
    val groups = session.cpuGroupNames.mapIndexed { index, name ->
        TelemetrySeries(
            label = name,
            values = positiveValues(chartValues(
                session.cpuGroupFreqSeries.getOrElse(index) { emptyList() }
            )),
            color = groupColor(index),
            unit = "MHz",
            min = 0f,
            max = ceiling
        )
    }
    val lines = listOf(
        TelemetrySeries("Fastest core", total, Color(0xFFA78BFA), "MHz", 0f, ceiling)
    ) + groups
    Panel {
        Text("CPU frequency(MHz)", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        TelemetryChart(lines)
        TimeAxis(session.durationMs, maxOf(
            total.size,
            session.cpuGroupFreqSeries.maxOfOrNull { it.size } ?: 0
        ))
        if (groups.isEmpty()) {
            Text("Per-cluster clocks are not reported on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PowerCapacityCard(session: SessionRecord) {
    val power = positiveValues(session.powerSeries)
    val battery = session.batterySeries
    val ceiling = (power.maxOrNull() ?: 1f) * 1.15f
    val lines = listOf(
        TelemetrySeries("Power(W)", power, Color(0xFF60A5FA), "W", 0f, ceiling),
        TelemetrySeries("Capacity(%)", battery, Color(0xFFCBD5E1), "%", 0f, 100f)
    )
    Panel {
        Text("Power(W) & Capacity %", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        TelemetryChart(lines)
        TimeAxis(session.durationMs, maxCount(power, battery))
        Text(
            text = "MAX ${fmt(power.maxOrNull(), 2, "W")}  " +
                "MIN ${fmt(power.minOrNull(), 2, "W")}  " +
                "AVG ${fmt(power.averageOrNull(), 2, "W")}",
            style = MonoInline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Space.sm)
        )
    }
}

@Composable
private fun CpuTemperatureCard(session: SessionRecord) {
    val temps = plausibleValues(session.cpuTempSeries)
    val bounds = paddedBounds(temps)
    Panel {
        Text("CPU Temperature(°C)", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.sm))
        if (temps.size < 2) {
            Text("CPU/SoC sensor: -- (this device does not name one)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TelemetryChart(
                listOf(TelemetrySeries("CPU temp", temps, Color(0xFF93C5FD), "°C",
                    bounds.first, bounds.second))
            )
            TimeAxis(session.durationMs, temps.size)
            Text(
                text = "MAX ${fmt(temps.maxOrNull(), 1, "°C")}  " +
                    "MIN ${fmt(temps.minOrNull(), 1, "°C")}  " +
                    "AVG ${fmt(temps.averageOrNull(), 1, "°C")}",
                style = MonoInline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm)
            )
        }
    }
}

@Composable
private fun ShellLogsCard(logs: String) {
    var expanded by remember { mutableStateOf(false) }
    Panel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Shell Logs", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Text(if (expanded) "Hide" else "Expand",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Spacer(Modifier.height(Space.sm))
            Text(
                text = logs.ifBlank { "No shell output was captured for this run." },
                style = MonoBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun groupColor(index: Int): Color = listOf(
    Color(0xFFA78BFA),
    Color(0xFF22D3EE),
    Color(0xFF2DD4BF),
    Color(0xFFFB923C),
    Color(0xFFF472B6),
    Color(0xFFA3E635)
)[index % 6]

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
