package com.framescope.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.Space
import java.util.Locale

/**
 * The readout of the selected moment.
 *
 * Every line shows the sample that was really recorded and the time of that
 * sample. When a metric has no reading at that moment, the line says so instead
 * of showing a number from somewhere else.
 */
@Composable
fun ChartTooltip(
    series: List<ChartSeries>,
    timeMs: Long,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = Space.sm, vertical = 6.dp)
    ) {
        Text(
            text = formatElapsed(timeMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        series.forEach { line ->
            val point = nearestPoint(line.points, timeMs)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(line.color)
                )
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 5.dp).width(86.dp),
                    overflow = TextOverflow.Ellipsis
                )
                if (point == null) {
                    Text(
                        text = "--",
                        style = MonoInline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = formatValue(point.value) + if (line.unit.isBlank()) "" else " ${line.unit}",
                        style = MonoInline,
                        color = line.color
                    )
                }
            }
        }
    }
}

/**
 * Several charts that follow one moment.
 *
 * Choosing a moment on one chart chooses it on all of them, and the readout above
 * shows the values of every chart at that moment. Each chart keeps its own unit
 * and its own scale: two metrics with different units are never drawn on one
 * pair of axes.
 */
data class LinkedChart(
    val title: String,
    val series: List<ChartSeries>,
    val events: List<ChartEvent> = emptyList(),
    /** A line at this value, e.g. the frame rate the game is aiming for. */
    val target: Float? = null
)

@Composable
fun LinkedChartGroup(
    charts: List<LinkedChart>,
    modifier: Modifier = Modifier,
    chartHeight: androidx.compose.ui.unit.Dp = 150.dp,
    /** What a screen reader should say about the graphs. */
    description: String? = null
) {
    var selectedTimeMs by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        val selected = selectedTimeMs
        if (selected != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "At ${formatElapsed(selected)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tap the graph again to clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        charts.forEach { chart ->
            Text(
                text = chart.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.sm, bottom = 2.dp)
            )
            InteractiveLineChart(
                series = chart.series,
                height = chartHeight,
                selectedTimeMs = selectedTimeMs,
                onSelectTime = { time ->
                    selectedTimeMs = if (time == selectedTimeMs) null else time
                },
                events = chart.events,
                description = description
            )
        }
    }
}

/** One column of the data table. */
data class TelemetryColumn(val label: String, val unit: String = "")

/** One row of the data table: the moment and the readings of that moment. */
data class TelemetryRow(
    val timeMs: Long,
    val cells: List<String>,
    /** True when the row holds something worth pointing at, e.g. a drop. */
    val marked: Boolean = false
)

/**
 * The recorded samples as a table.
 *
 * The table shows what was recorded and nothing else: a value that was not
 * measured is printed as "--" instead of being filled in. The header stays above
 * the rows while the list scrolls, so the columns can be read at any position.
 */
@Composable
fun TelemetryDataTable(
    columns: List<TelemetryColumn>,
    rows: List<TelemetryRow>,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) {
        Text(
            text = "No samples recorded",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = Space.sm, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(52.dp)
            )
            columns.forEach { column ->
                Text(
                    text = if (column.unit.isBlank()) {
                        column.label
                    } else {
                        "${column.label}\n${column.unit}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
            items(rows) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (row.marked) {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = Space.sm, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatElapsed(row.timeMs),
                        style = MonoInline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp)
                    )
                    row.cells.forEach { cell ->
                        Text(
                            text = cell,
                            style = MonoInline,
                            color = if (row.marked) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** A reading for the table: a number with its unit, or "--" when it was not measured. */
fun formatValue(value: Float?, decimals: Int = 1): String {
    if (value == null || !value.isFinite()) return "--"
    return String.format(Locale.US, "%.${decimals}f", value)
}
