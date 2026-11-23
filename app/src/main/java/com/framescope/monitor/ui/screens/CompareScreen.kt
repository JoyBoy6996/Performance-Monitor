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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.ChartSeries
import com.framescope.monitor.ui.components.EmptyState
import com.framescope.monitor.ui.components.InfoRow
import com.framescope.monitor.ui.components.LinkedChart
import com.framescope.monitor.ui.components.LinkedChartGroup
import com.framescope.monitor.ui.components.MiniStat
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.SectionHeader
import com.framescope.monitor.ui.components.chartPoints
import com.framescope.monitor.ui.components.formatElapsed
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.metric
import com.framescope.monitor.ui.theme.status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The colour of the first run.
 *
 * The colour says which run a line belongs to and nothing else. It is not the
 * colour of the metric: in a comparison the two lines of one chart are the two
 * runs, and every chart of the comparison uses the same pair.
 */
private val RunAColor = Color(0xFF56C8F0)

/** The colour of the second run. */
private val RunBColor = Color(0xFFE86FA8)

/**
 * One number that both runs have.
 *
 * A number that only one of the two runs has is not a difference, it is a
 * missing reading, and it is left out of the comparison.
 */
data class ComparisonRow(
    val label: String,
    val unit: String,
    val a: Float?,
    val b: Float?,
    /** True when a bigger number is the better one, null when it does not matter. */
    val higherIsBetter: Boolean?,
    /** How the numbers of this row are written. */
    val format: ComparisonFormat = ComparisonFormat.NUMBER
) {
    /** The difference between the two runs. */
    val delta: Float? get() = if (a != null && b != null) b - a else null
}

/**
 * The numbers that both runs have.
 *
 * Only the numbers that both runs really measured are compared. The rest is
 * said out loud as missing, never as a zero.
 */
fun compareRuns(a: SessionRecord, b: SessionRecord): List<ComparisonRow> {
    val rows = mutableListOf<ComparisonRow>()

    fun add(
        label: String,
        unit: String,
        first: Float?,
        second: Float?,
        higherIsBetter: Boolean?,
        format: ComparisonFormat = ComparisonFormat.NUMBER
    ) {
        if (first == null || second == null) return
        rows += ComparisonRow(label, unit, first, second, higherIsBetter, format)
    }

    add("Average frame rate", "fps", a.avgFps, b.avgFps, true)
    add("1% low", "fps", a.low1Percent, b.low1Percent, true)
    add("5% low", "fps", a.low5Percent, b.low5Percent, true)
    add("Lowest second", "fps", a.minFps, b.minFps, true)
    add("Highest second", "fps", a.maxFps, b.maxFps, true)
    add("Average frame time", "ms", a.avgFrameTimeMs, b.avgFrameTimeMs, false)
    add("Longest frame", "ms", a.maxFrameTimeMs, b.maxFrameTimeMs, false)
    add("Steadiness of the frames", "ms", a.jitterMs, b.jitterMs, false)
    add("Average GPU", "%", averageOf(a.gpuPercentSeries), averageOf(b.gpuPercentSeries), false)
    add("Jank", "%", jankPercent(a), jankPercent(b), false)
    add("Frames", "", a.totalFrames.toFloat(), b.totalFrames.toFloat(), true, ComparisonFormat.COUNT)
    add("Length", "", a.durationMs / 1000f, b.durationMs / 1000f, null, ComparisonFormat.DURATION)
    add("Average CPU", "%", a.avgCpu, b.avgCpu, false)
    add("Highest temperature", "°C", a.maxTempC, b.maxTempC, false)
    add("Average power", "W", a.avgPowerW, b.avgPowerW, false)
    add("Seconds above 45 fps", "%", a.sceneSmoothnessPercent, b.sceneSmoothnessPercent, true)
    return rows
}

/** How a number of the comparison is written. */
enum class ComparisonFormat {
    /** A number with one decimal and its unit. */
    NUMBER,

    /** A count, without a decimal. */
    COUNT,

    /** A length of time, as minutes and seconds. */
    DURATION
}

/**
 * The average of a series, when the series has readings.
 *
 * The average is only used when both runs have the series, so the two numbers
 * are calculated in the same way.
 */
private fun averageOf(series: List<Float>): Float? {
    val values = series.filter { it.isFinite() && it > 0f }
    if (values.isEmpty()) return null
    return values.average().toFloat()
}

/** The share of the frames of a run that were janky, in percent. */
fun jankPercent(session: SessionRecord): Float? =
    if (session.totalFrames > 0) session.jankFrames * 100f / session.totalFrames else null

/**
 * What the two runs say about each other.
 *
 * The words are built from the numbers of the runs and say which run was
 * better and by how much. When the runs are close, it says so.
 */
fun compareVerdict(a: SessionRecord, b: SessionRecord, threshold: Int): String {
    val difference = b.avgFps - a.avgFps
    val sentence = when {
        kotlin.math.abs(difference) < 1f ->
            "Both runs held about the same frame rate (${a.avgFps.toInt()} fps)."
        difference > 0 -> "Run B was ${difference.toInt()} fps faster on average than run A."
        else -> "Run A was ${(-difference).toInt()} fps faster on average than run B."
    }

    val aHeld = a.avgFps >= threshold
    val bHeld = b.avgFps >= threshold
    val target = when {
        aHeld && bHeld -> "Both runs held the target of $threshold fps."
        aHeld -> "Only run A held the target of $threshold fps."
        bHeld -> "Only run B held the target of $threshold fps."
        else -> "Neither run held the target of $threshold fps."
    }

    val heat = when {
        a.maxTempC != null && b.maxTempC != null && kotlin.math.abs(a.maxTempC - b.maxTempC) >= 2f ->
            "The hotter run was ${maxOf(a.maxTempC, b.maxTempC).toInt()}°C."
        else -> ""
    }

    return listOf(sentence, target, heat).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * The comparison of two recorded runs.
 *
 * The two runs are drawn on the same clock: the axis is the time since the
 * start of the run, so the first minute of one run is next to the first
 * minute of the other.
 */
@Composable
fun CompareScreen(
    container: AppContainer,
    bottomPadding: Dp = 24.dp,
    onBack: (() -> Unit)? = null
) {
    val sessions by container.store.sessions.collectAsState()
    val threshold = container.settings.fpsThreshold.flow.collectAsState().value
    val formatter = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    var firstId by remember { mutableStateOf<String?>(null) }
    var secondId by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<Int?>(null) }

    // The two newest runs are compared when the screen is opened.
    val first = sessions.firstOrNull { it.id == firstId } ?: sessions.getOrNull(0)
    val second = sessions.firstOrNull { it.id == secondId } ?: sessions.getOrNull(1)

    FrameScopeScreen(
        title = "Compare",
        subtitle = if (sessions.size < 2) {
            "Two recorded runs are needed to compare"
        } else {
            "Two runs, side by side"
        },
        bottomPadding = bottomPadding,
        actions = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to the sessions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        if (sessions.size < 2) {
            item {
                EmptyState(
                    icon = Icons.Rounded.CompareArrows,
                    title = "Not enough runs yet",
                    message = "Record at least two runs on the Monitor tab and they can be " +
                        "compared here."
                )
            }
            return@FrameScopeScreen
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RunPickerCard(
                    label = "Run A",
                    session = first,
                    color = RunAColor,
                    dateFormat = formatter,
                    modifier = Modifier.weight(1f),
                    onClick = { picking = 0 }
                )
                IconButton(onClick = {
                    val a = first
                    val b = second
                    firstId = b?.id
                    secondId = a?.id
                }) {
                    Icon(
                        imageVector = Icons.Rounded.SwapVert,
                        contentDescription = "Swap the two runs",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RunPickerCard(
                    label = "Run B",
                    session = second,
                    color = RunBColor,
                    dateFormat = formatter,
                    modifier = Modifier.weight(1f),
                    onClick = { picking = 1 }
                )
            }
        }

        val a = first
        val b = second
        if (a == null || b == null) {
            item {
                Panel {
                    Text(
                        text = "Choose the second run to start the comparison.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@FrameScopeScreen
        }

        item { SectionHeader("What the runs say") }

        item {
            Panel(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = compareVerdict(a, b, threshold),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        item { SectionHeader("Differences") }

        item { ComparisonTable(a = a, b = b) }

        item { SectionHeader("The runs on the same clock") }

        item {
            Panel {
                Text(
                    text = "Both runs start at 0:00 on this axis. A shorter run ends " +
                        "earlier; its line is not stretched to fill the axis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.sm)
                )
                ComparisonLegend(a = a, b = b)
                Spacer(Modifier.height(Space.sm))
                LinkedChartGroup(charts = comparisonCharts(a, b, threshold), chartHeight = 140.dp)
            }
        }
    }

    val pick = picking
    if (pick != null) {
        SessionPickerDialog(
            sessions = sessions,
            dateFormat = formatter,
            onPick = { session ->
                if (pick == 0) firstId = session.id else secondId = session.id
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

/** The card that opens the list of the runs. */
@Composable
private fun RunPickerCard(
    label: String,
    session: SessionRecord?,
    color: Color,
    dateFormat: SimpleDateFormat,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Panel(modifier = modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(Space.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.xs))
        Text(
            text = session?.appLabel?.ifBlank { session.appPackage } ?: "Choose a run",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (session != null) {
            Text(
                text = "${dateFormat.format(Date(session.startedAt))} · " +
                    formatElapsed(session.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The numbers of the two runs, next to each other. */
@Composable
private fun ComparisonTable(a: SessionRecord, b: SessionRecord) {
    val rows = compareRuns(a, b)
    if (rows.isEmpty()) {
        Panel {
            Text(
                text = "These two runs have no number that both of them measured.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Panel {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1.5f)
            )
            Text(
                text = "A",
                style = MaterialTheme.typography.labelSmall,
                color = RunAColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "B",
                style = MaterialTheme.typography.labelSmall,
                color = RunBColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "B − A",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(Space.xs))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.5f)
                )
                Text(
                    text = formatComparison(row.a, row.unit, row.format),
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatComparison(row.b, row.unit, row.format),
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatDelta(row),
                    style = MonoInline,
                    color = deltaColor(row),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** The colour of the difference, when one of the two numbers is the better one. */
@Composable
private fun deltaColor(row: ComparisonRow): Color {
    val delta = row.delta ?: return MaterialTheme.colorScheme.onSurfaceVariant
    if (row.higherIsBetter == null || kotlin.math.abs(delta) < 0.05f) {
        return MaterialTheme.colorScheme.onSurfaceVariant
    }
    val better = if (row.higherIsBetter) delta > 0 else delta < 0
    return if (better) MaterialTheme.status.good else MaterialTheme.status.warn
}

/** The difference between the two runs, with its sign. */
private fun formatDelta(row: ComparisonRow): String {
    val delta = row.delta ?: return "--"
    if (kotlin.math.abs(delta) < 0.05f) return "0"
    val number = kotlin.math.abs(delta)
    val text = when (row.format) {
        ComparisonFormat.COUNT -> String.format(Locale.US, "%.0f", number)
        ComparisonFormat.DURATION -> formatElapsed((number * 1000f).toLong())
        ComparisonFormat.NUMBER -> String.format(Locale.US, "%.1f", number)
    }
    return (if (delta > 0) "+" else "-") + text
}

/** One number of a run, with its unit. */
private fun formatComparison(value: Float?, unit: String, format: ComparisonFormat): String {
    val number = value ?: return "--"
    return when (format) {
        ComparisonFormat.COUNT -> String.format(Locale.US, "%.0f", number)
        ComparisonFormat.DURATION -> formatElapsed((number * 1000f).toLong())
        ComparisonFormat.NUMBER -> {
            val text = String.format(Locale.US, "%.1f", number)
            if (unit.isBlank()) text else "$text $unit"
        }
    }
}

/** The legend of the two runs. */
@Composable
private fun ComparisonLegend(a: SessionRecord, b: SessionRecord) {
    val palette = MaterialTheme.metric
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        LegendEntry(color = RunAColor, text = "A · ${a.appLabel.ifBlank { a.appPackage }}")
        LegendEntry(color = RunBColor, text = "B · ${b.appLabel.ifBlank { b.appPackage }}")
    }
}

/** One entry of the legend. */
@Composable
private fun LegendEntry(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The charts of the comparison.
 *
 * A chart is only drawn for a metric that both runs measured, and both lines
 * of a chart have the same unit, so one scale is honest.
 */
@Composable
private fun comparisonCharts(
    a: SessionRecord,
    b: SessionRecord,
    threshold: Int
): List<LinkedChart> {
    val palette = MaterialTheme.metric
    val charts = mutableListOf<LinkedChart>()

    fun pair(
        title: String,
        unit: String,
        first: List<Float>,
        second: List<Float>,
        min: Float? = null,
        max: Float? = null,
        target: Float? = null
    ) {
        val firstPoints = chartPoints(first, a.seriesTimesMs)
        val secondPoints = chartPoints(second, b.seriesTimesMs)
        if (firstPoints.size < 2 || secondPoints.size < 2) return
        charts += LinkedChart(
            title = title,
            series = listOf(
                ChartSeries("A", unit, RunAColor, firstPoints, min = min, max = max),
                ChartSeries("B", unit, RunBColor, secondPoints, min = min, max = max)
            ),
            target = target
        )
    }

    pair("Frame rate", "fps", a.fpsSeries, b.fpsSeries, min = 0f, target = threshold.toFloat())
    pair("Frame time", "ms", a.frameTimeSeries, b.frameTimeSeries, min = 0f)
    pair("CPU", "%", a.cpuSeries, b.cpuSeries, min = 0f, max = 100f)
    pair("GPU", "%", a.gpuPercentSeries, b.gpuPercentSeries, min = 0f, max = 100f)
    pair(
        "Temperature",
        "°C",
        a.socTempSeries.ifEmpty { a.tempSeries },
        b.socTempSeries.ifEmpty { b.tempSeries }
    )
    pair("Battery", "%", a.batterySeries, b.batterySeries, min = 0f, max = 100f)
    return charts
}

/**
 * The list of the runs to choose from.
 */
@Composable
private fun SessionPickerDialog(
    sessions: List<SessionRecord>,
    dateFormat: SimpleDateFormat,
    onPick: (SessionRecord) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a run") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(session) }
                            .padding(vertical = Space.sm)
                    ) {
                        Text(
                            text = session.appLabel.ifBlank {
                                session.appPackage.ifBlank { "Unknown app" }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${dateFormat.format(Date(session.startedAt))} · " +
                                "${formatElapsed(session.durationMs)} · " +
                                "${session.avgFps.toInt()} fps",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
