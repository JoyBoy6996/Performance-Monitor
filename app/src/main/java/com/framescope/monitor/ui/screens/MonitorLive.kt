package com.framescope.monitor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.FpsState
import com.framescope.monitor.core.LiveHistory
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.core.SystemMetric
import com.framescope.monitor.core.SystemMetrics
import com.framescope.monitor.core.deviceTemperatureC
import com.framescope.monitor.core.thermalStatusLabel
import com.framescope.monitor.ui.components.ChartEvent
import com.framescope.monitor.ui.components.InteractiveLineChart
import com.framescope.monitor.ui.components.formatElapsed
import com.framescope.monitor.ui.components.formatValue
import com.framescope.monitor.ui.components.ChartPoint
import com.framescope.monitor.ui.components.ChartSeries
import com.framescope.monitor.ui.components.ChipRow
import com.framescope.monitor.ui.components.InfoRow
import com.framescope.monitor.ui.components.LiveMetricCard
import com.framescope.monitor.ui.components.MiniStat
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.asChartPoints
import com.framescope.monitor.ui.components.frameRateColor
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.metric
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status

/**
 * The metrics the dashboard can show.
 *
 * The user chooses which of them are on the screen. The key is what is stored,
 * so a card that disappears from the app does not take the choice with it.
 */
enum class DashboardMetric(val key: String, val label: String) {
    FPS("fps", "Frame rate"),
    FRAME_TIME("frame", "Frame time"),
    CPU("cpu", "CPU"),
    GPU("gpu", "GPU"),
    TEMPERATURE("temp", "Temperature"),
    RAM("ram", "Memory"),
    POWER("power", "Power");

    companion object {
        /** The cards the dashboard offers. */
        val cards: List<DashboardMetric> = listOf(FPS, CPU, GPU, TEMPERATURE, RAM, POWER)

        /** The graphs the dashboard offers. */
        val graphs: List<DashboardMetric> = listOf(FPS, FRAME_TIME, CPU, GPU, TEMPERATURE, RAM)

        /** Reads a stored list of keys, ignoring the ones the app no longer has. */
        fun fromKeys(keys: Set<String>, available: List<DashboardMetric>): List<DashboardMetric> =
            available.filter { it.key in keys }
    }
}

/**
 * The live cards of the dashboard.
 *
 * Every card shows the number, the unit and how the number moved. A metric that
 * could not be read says why instead of showing a number that was not measured.
 */
@Composable
fun LiveMetricCards(
    fps: FpsState,
    system: SystemMetrics,
    history: LiveHistory,
    threshold: Int,
    metrics: List<DashboardMetric>,
    modifier: Modifier = Modifier
) {
    if (metrics.isEmpty()) {
        Panel(modifier = modifier) {
            Text(
                text = "No cards chosen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        metrics.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                pair.forEach { entry ->
                    LiveMetricCardFor(
                        metric = entry,
                        fps = fps,
                        system = system,
                        history = history,
                        threshold = threshold,
                        modifier = Modifier.weight(1f)
                    )
                }
                // A row of one card leaves the other half empty rather than
                // stretching the card across the screen.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LiveMetricCardFor(
    metric: DashboardMetric,
    fps: FpsState,
    system: SystemMetrics,
    history: LiveHistory,
    threshold: Int,
    modifier: Modifier = Modifier
) {
    when (metric) {
        DashboardMetric.FPS -> LiveMetricCard(
            label = "Frame rate",
            value = if (fps.valid) String.format("%.0f", fps.fps) else "--",
            unit = "fps",
            accent = frameRateColor(fps.fps, threshold),
            trend = history.fps.asChartPoints(),
            availability = fps.availability,
            // The frame time belongs to the frame rate: it is the same reading
            // seen from the other side.
            footnote = if (fps.valid) {
                "${String.format("%.1f", fps.frameTimeMs)} ms a frame"
            } else {
                null
            },
            modifier = modifier
        )

        DashboardMetric.CPU -> LiveMetricCard(
            label = "CPU",
            value = system.cpuPercent?.let { String.format("%.0f", it) } ?: "--",
            unit = "%",
            accent = MaterialTheme.metric.cpu,
            trend = history.cpuPercent.asChartPoints(),
            availability = system.availabilityOf(SystemMetric.CPU_PERCENT),
            footnote = system.cpuFreqMHz?.let { "$it MHz" },
            modifier = modifier
        )

        DashboardMetric.GPU -> LiveMetricCard(
            label = "GPU",
            value = system.gpuPercent?.let { String.format("%.0f", it) } ?: "--",
            unit = "%",
            accent = MaterialTheme.metric.gpu,
            trend = history.gpuPercent.asChartPoints(),
            availability = system.availabilityOf(SystemMetric.GPU_PERCENT),
            footnote = system.gpuName,
            modifier = modifier
        )

        DashboardMetric.TEMPERATURE -> {
            val temperature = system.deviceTemperatureC()
            LiveMetricCard(
                label = "Temperature",
                value = temperature?.let { String.format("%.1f", it) } ?: "--",
                unit = "°C",
                accent = MaterialTheme.metric.temperature,
                trend = history.temperatureC.asChartPoints(),
                availability = temperatureAvailability(system),
                footnote = thermalStatusLabel(system.thermalStatus),
                modifier = modifier
            )
        }

        DashboardMetric.RAM -> LiveMetricCard(
            label = "Memory",
            value = system.ramPercent?.let { "$it" } ?: "--",
            unit = "%",
            accent = MaterialTheme.metric.ram,
            availability = system.availabilityOf(SystemMetric.RAM_PERCENT),
            footnote = system.ramUsedGb?.let { used ->
                system.ramTotalGb?.let { total -> "${used} / ${total} GB" } ?: "$used GB"
            },
            modifier = modifier
        )

        DashboardMetric.POWER -> LiveMetricCard(
            label = "Power",
            value = system.powerWatts?.let { String.format("%.2f", it) } ?: "--",
            unit = "W",
            accent = MaterialTheme.metric.power,
            availability = system.availabilityOf(SystemMetric.POWER_WATTS),
            footnote = system.batteryLevel?.let { "$it% battery" },
            modifier = modifier
        )

        DashboardMetric.FRAME_TIME -> LiveMetricCard(
            label = "Frame time",
            value = if (fps.valid) String.format("%.1f", fps.frameTimeMs) else "--",
            unit = "ms",
            accent = MaterialTheme.metric.frameTime,
            trend = history.frameTimeMs.asChartPoints(),
            availability = fps.availability,
            modifier = modifier
        )
    }
}

/**
 * One live graph with the value it shows right now.
 *
 * Tapping it opens the same graph with a finger on it.
 */
@Composable
private fun LiveGraphRow(
    title: String,
    value: String,
    unit: String,
    accent: Color,
    points: List<ChartPoint>,
    target: Float?,
    onOpen: () -> Unit
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
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, style = MonoInline, color = accent)
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        if (points.size >= 2) {
            Sparkline(
                values = points.map { it.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                color = accent,
                target = target
            )
        } else {
            Text(
                text = "Waiting for the first readings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Space.sm)
            )
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            text = "Tap to read the graph closely",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.clickable(onClick = onOpen)
        )
    }
}

/**
 * The graphs of the dashboard.
 *
 * Each graph keeps its own unit and its own scale, and every graph is drawn on
 * the real moments of its own readings.
 */
@Composable
fun LiveGraphs(
    fps: FpsState,
    system: SystemMetrics,
    history: LiveHistory,
    threshold: Int,
    metrics: List<DashboardMetric>,
    modifier: Modifier = Modifier
) {
    var opened by remember { mutableStateOf<DashboardMetric?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        metrics.forEach { entry ->
            val points = pointsOf(entry, history)
            if (points.size < 2) return@forEach
            LiveGraphRow(
                title = entry.label,
                value = valueOf(entry, fps, system),
                unit = unitOf(entry),
                accent = accentOf(entry, fps, threshold),
                points = points,
                target = if (entry == DashboardMetric.FPS) threshold.toFloat() else null,
                onOpen = { opened = entry }
            )
        }
    }

    val current = opened
    if (current != null) {
        LiveGraphDialog(
            title = current.label,
            series = seriesOf(current, fps, system, history, threshold),
            events = emptyList(),
            onDismiss = { opened = null }
        )
    }
}

/**
 * The graph of one metric, with the finger on it.
 *
 * The chart shows the real readings and the readout names the sample that was
 * picked, so a number under the finger is never a number that was not measured.
 */
@Composable
fun LiveGraphDialog(
    title: String,
    series: List<ChartSeries>,
    events: List<ChartEvent>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            var selectedTimeMs by remember { mutableStateOf<Long?>(null) }
            Column {
                Text(
                    text = "Drag along the graph to read a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.sm)
                )
                val selected = selectedTimeMs
                if (selected != null) {
                    val readout = series.flatMap { it.points }
                        .filter { it.timeMs <= selected }
                        .maxByOrNull { it.timeMs }
                    Text(
                        text = if (readout == null) {
                            "At ${formatElapsed(selected)}"
                        } else {
                            "At ${formatElapsed(readout.timeMs)}: " +
                                series.map { "${it.label} ${formatValue(readout.value)} ${it.unit}" }
                                    .joinToString("  ")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.sm)
                    )
                }
                InteractiveLineChart(
                    series = series,
                    height = 190.dp,
                    selectedTimeMs = selectedTimeMs,
                    onSelectTime = { time ->
                        selectedTimeMs = if (time == selectedTimeMs) null else time
                    },
                    events = events
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * The state of the temperature reading.
 *
 * The device can report the temperature with the battery or with the thermal
 * service, and only one of them needs a privileged shell. The card therefore
 * asks the sensor that really gave the number.
 */
private fun temperatureAvailability(system: SystemMetrics): MetricAvailability =
    if (system.batteryTempC != null && system.batteryTempC > 0f) {
        system.availabilityOf(SystemMetric.BATTERY_TEMP_C)
    } else {
        system.availabilityOf(SystemMetric.THERMAL_MAX_C)
    }

/** The readings of one metric, as chart points. */
private fun pointsOf(metric: DashboardMetric, history: LiveHistory): List<ChartPoint> =
    when (metric) {
        DashboardMetric.FPS -> history.fps.asChartPoints()
        DashboardMetric.FRAME_TIME -> history.frameTimeMs.asChartPoints()
        DashboardMetric.CPU -> history.cpuPercent.asChartPoints()
        DashboardMetric.GPU -> history.gpuPercent.asChartPoints()
        DashboardMetric.TEMPERATURE -> history.temperatureC.asChartPoints()
        DashboardMetric.RAM -> history.ramPercent.asChartPoints()
        DashboardMetric.POWER -> emptyList()
    }

/** The series of one metric, for the chart that can be touched. */
@Composable
private fun seriesOf(
    metric: DashboardMetric,
    fps: FpsState,
    system: SystemMetrics,
    history: LiveHistory,
    threshold: Int
): List<ChartSeries> {
    val points = pointsOf(metric, history)
    return listOf(
        ChartSeries(
            label = metric.label,
            unit = unitOf(metric),
            color = accentOf(metric, fps, threshold),
            points = points,
            target = if (metric == DashboardMetric.FPS) threshold.toFloat() else null
        )
    )
}

/** The unit of a metric. */
private fun unitOf(metric: DashboardMetric): String = when (metric) {
    DashboardMetric.FPS -> "fps"
    DashboardMetric.FRAME_TIME -> "ms"
    DashboardMetric.CPU -> "%"
    DashboardMetric.GPU -> "%"
    DashboardMetric.TEMPERATURE -> "°C"
    DashboardMetric.RAM -> "%"
    DashboardMetric.POWER -> "W"
}

/** The colour of a metric. */
@Composable
private fun accentOf(metric: DashboardMetric, fps: FpsState, threshold: Int): Color = when (metric) {
    DashboardMetric.FPS -> frameRateColor(fps.fps, threshold)
    DashboardMetric.FRAME_TIME -> MaterialTheme.metric.frameTime
    DashboardMetric.CPU -> MaterialTheme.metric.cpu
    DashboardMetric.GPU -> MaterialTheme.metric.gpu
    DashboardMetric.TEMPERATURE -> MaterialTheme.metric.temperature
    DashboardMetric.RAM -> MaterialTheme.metric.ram
    DashboardMetric.POWER -> MaterialTheme.metric.power
}

/** The value of a metric right now. */
private fun valueOf(metric: DashboardMetric, fps: FpsState, system: SystemMetrics): String =
    when (metric) {
        DashboardMetric.FPS -> if (fps.valid) String.format("%.0f", fps.fps) else "--"
        DashboardMetric.FRAME_TIME -> if (fps.valid) String.format("%.1f", fps.frameTimeMs) else "--"
        DashboardMetric.CPU -> system.cpuPercent?.let { String.format("%.0f", it) } ?: "--"
        DashboardMetric.GPU -> system.gpuPercent?.let { String.format("%.0f", it) } ?: "--"
        DashboardMetric.TEMPERATURE -> system.deviceTemperatureC()?.let { String.format("%.1f", it) } ?: "--"
        DashboardMetric.RAM -> system.ramPercent?.toString() ?: "--"
        DashboardMetric.POWER -> system.powerWatts?.let { String.format("%.2f", it) } ?: "--"
    }

/**
 * The choice of the user: which cards and which graphs are on the dashboard.
 *
 * The choice is kept in the settings of the app, so it survives a restart and
 * comes back with a backup.
 */
@Composable
fun DashboardCustomisation(
    cards: Set<String>,
    graphs: Set<String>,
    onCardsChange: (Set<String>) -> Unit,
    onGraphsChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader("Cards")
        ChipRow(
            options = DashboardMetric.cards.map { it.label },
            selected = DashboardMetric.cards.filter { it.key in cards }.map { it.label }.toSet(),
            onToggle = { label ->
                val entry = DashboardMetric.cards.firstOrNull { it.label == label } ?: return@ChipRow
                onCardsChange(
                    if (entry.key in cards) cards - entry.key else cards + entry.key
                )
            }
        )
        SectionHeader("Graphs")
        ChipRow(
            options = DashboardMetric.graphs.map { it.label },
            selected = DashboardMetric.graphs.filter { it.key in graphs }.map { it.label }.toSet(),
            onToggle = { label ->
                val entry = DashboardMetric.graphs.firstOrNull { it.label == label } ?: return@ChipRow
                onGraphsChange(
                    if (entry.key in graphs) graphs - entry.key else graphs + entry.key
                )
            }
        )
    }
}

/** The state of the frame rate in words. */
@Composable
fun frameRateStatus(fps: FpsState, threshold: Int): String = when {
    !fps.valid -> "No signal"
    fps.fps >= threshold -> "Stable"
    fps.fps >= threshold * 0.75f -> "Dropping"
    else -> "Unstable"
}

/** The colour of the state of the frame rate. */
@Composable
fun frameRateStatusColor(fps: FpsState, threshold: Int): Color = when {
    !fps.valid -> MaterialTheme.status.idle
    fps.fps >= threshold -> MaterialTheme.status.good
    fps.fps >= threshold * 0.75f -> MaterialTheme.status.warn
    else -> MaterialTheme.status.critical
}
