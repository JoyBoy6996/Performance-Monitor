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
import androidx.compose.material.icons.rounded.ArrowBack
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
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.steadyWindow
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
                    imageVector = Icons.Rounded.ArrowBack,
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
                        value = String.format("%.1f", session.avgFps),
                        unit = "fps",
                        accent = stateColor,
                        modifier = Modifier.weight(1f).padding(end = Space.sm)
                    )
                    StatCell(
                        label = "Peak",
                        value = String.format("%.0f", session.maxFps),
                        unit = "fps",
                        accent = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                    )
                    StatCell(
                        label = "Lowest",
                        value = String.format("%.0f", session.minFps),
                        unit = "fps",
                        accent = MaterialTheme.status.critical,
                        modifier = Modifier.weight(1f).padding(start = Space.sm)
                    )
                }
                Row(Modifier.padding(top = Space.md)) {
                    StatCell(
                        label = "1% low",
                        value = String.format("%.1f", session.low1Percent),
                        unit = "fps",
                        accent = MaterialTheme.status.warn,
                        modifier = Modifier.weight(1f).padding(end = Space.sm)
                    )
                    StatCell(
                        label = "5% low",
                        value = String.format("%.1f", session.low5Percent),
                        unit = "fps",
                        accent = MaterialTheme.status.warn,
                        modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
                    )
                    StatCell(
                        label = "Smooth",
                        value = String.format("%.0f", session.smoothness),
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
                    value = "${String.format("%.2f", session.avgFrameTimeMs)} ms",
                    accent = MaterialTheme.colorScheme.onSurface
                )
                MetricLine(
                    label = "Longest frame",
                    value = "${String.format("%.1f", session.longestFrameMs)} ms",
                    accent = if (session.longestFrameMs > 33f) {
                        MaterialTheme.status.critical
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                session.maxTempC?.let {
                    MetricLine(
                        label = "Peak temperature",
                        value = "${String.format("%.1f", it)}°C",
                        accent = MaterialTheme.status.warn
                    )
                }
                session.avgCpu?.let {
                    MetricLine(
                        label = "Average CPU",
                        value = "${String.format("%.1f", it)}%",
                        accent = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val battery = session.batterySeries
                if (battery.isNotEmpty()) {
                    MetricLine(
                        label = "Battery",
                        value = "${String.format("%.0f", battery.first())}% → " +
                            "${String.format("%.0f", battery.last())}%",
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
                targetLabel = "$threshold fps target"
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
                color = MaterialTheme.status.critical
            )
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

        item { SectionHeader("Frame drops") }

        item { DropsPanel(session, threshold) }
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
    invert: Boolean = false
) {
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
                text = values.lastOrNull()?.let {
                    "${String.format("%.1f", it)} $unit"
                } ?: "--",
                style = MonoInline,
                color = color
            )
        }

        Spacer(Modifier.height(Space.sm))

        if (values.size < 2) {
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
                values = values,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(top = Space.sm),
                color = color,
                target = target,
                trackColor = MaterialTheme.status.track,
                gridColor = MaterialTheme.status.grid
            )
        }

        Spacer(Modifier.height(Space.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SeriesStat("Min", values.min(), unit)
            SeriesStat("Average", values.average().toFloat(), unit)
            SeriesStat("Max", values.max(), unit)
            if (target != null) {
                SeriesStat("Target", target, "fps")
            }
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
private fun SeriesStat(label: String, value: Float, unit: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${String.format("%.1f", value)} $unit",
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
                        append(String.format("%.0f", fps))
                        append(" fps")
                        if (frameTime != null) {
                            append(" · ")
                            append(String.format("%.1f", frameTime))
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
