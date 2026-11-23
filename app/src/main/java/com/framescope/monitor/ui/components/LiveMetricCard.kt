package com.framescope.monitor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.MetricAvailability
import com.framescope.monitor.ui.theme.MonoInline
import com.framescope.monitor.ui.theme.MonoValue
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status

/**
 * One live metric on the dashboard.
 *
 * The number is the loud part, the label is quiet, and the little graph below
 * shows how the number moved. A metric that could not be read says why, and
 * its graph is left out instead of being drawn flat.
 */
@Composable
fun LiveMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    trend: List<ChartPoint> = emptyList(),
    availability: MetricAvailability? = null,
    footnote: String? = null
) {
    val missing = availability != null && availability != MetricAvailability.AVAILABLE
    Panel(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MonoValue,
                color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                maxLines = 1
            )
            if (!unit.isNullOrEmpty()) {
                Text(
                    text = unit,
                    style = MonoInline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)
                )
            }
        }
        if (missing) {
            Text(
                text = availabilityLabel(availability!!),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1
            )
        } else if (!footnote.isNullOrBlank()) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!missing && trend.size >= 2) {
            Spacer(Modifier.height(Space.sm))
            Sparkline(
                values = trend.map { it.value },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                color = accent
            )
        }
    }
}

/**
 * A short line of text with a name and a value.
 *
 * Used where a card would be too much: the state of a thing, a note about the
 * source of a reading, a hint about what to do next.
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text = value,
            style = MonoInline,
            color = valueColor,
            maxLines = 1
        )
    }
}

/** A row of two numbers side by side, used inside the cards. */
@Composable
fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = MonoInline, color = color, maxLines = 1)
            if (!unit.isNullOrEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 1.dp)
                )
            }
        }
    }
}

/** The colour a frame rate reading gets, relative to the target. */
@Composable
fun frameRateColor(fps: Float, target: Int): Color {
    val palette = MaterialTheme.status
    return when {
        fps <= 0f -> palette.idle
        fps >= target -> palette.good
        fps >= target * 0.75f -> palette.warn
        else -> palette.critical
    }
}

/** The wording of a missing reading, shared by the cards and the cells. */
fun availabilityLabel(availability: MetricAvailability): String = when (availability) {
    MetricAvailability.SHIZUKU_REQUIRED -> "needs Shizuku"
    MetricAvailability.PERMISSION_REQUIRED -> "needs permission"
    MetricAvailability.UNSUPPORTED -> "not reported"
    MetricAvailability.STALE -> "out of date"
    MetricAvailability.WARMING_UP -> "starting up"
    MetricAvailability.ERROR -> "read failed"
    MetricAvailability.AVAILABLE -> ""
}
