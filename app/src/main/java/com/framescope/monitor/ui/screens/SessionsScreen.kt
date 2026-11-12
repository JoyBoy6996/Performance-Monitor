package com.framescope.monitor.ui.screens

import android.content.Intent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.EmptyState
import com.framescope.monitor.ui.components.MetricLine
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.StatCell
import com.framescope.monitor.ui.components.StatusPill
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(
    container: AppContainer,
    onOpenSession: (SessionRecord) -> Unit = {},
    bottomPadding: Dp = 24.dp
) {
    val context = LocalContext.current
    val sessions by container.store.sessions.collectAsState()
    val formatter = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val threshold = container.settings.fpsThreshold.flow.collectAsState().value

    FrameScopeScreen(
        title = "Sessions",
        subtitle = if (sessions.isEmpty()) {
            "Recorded runs are stored on this device"
        } else {
            "${sessions.size} recorded ${if (sessions.size == 1) "run" else "runs"}"
        },
        bottomPadding = bottomPadding
    ) {
        if (sessions.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Timeline,
                    title = "No sessions yet",
                    message = "Start a recording from the Monitor tab, then come back here for the full breakdown."
                )
            }
            return@FrameScopeScreen
        }

        item { SummaryPanel(sessions) }

        sessions.forEach { session ->
            item(key = session.id) {
                SessionCard(
                    session = session,
                    threshold = threshold,
                    formattedDate = formatter.format(Date(session.startedAt)),
                    onOpen = { onOpenSession(session) },
                    onShare = {
                        val csv = container.store.toCsv(session)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "FrameScope session")
                            putExtra(Intent.EXTRA_TEXT, csv)
                        }
                        context.startActivity(Intent.createChooser(send, "Export session"))
                    },
                    onDelete = { container.store.delete(session.id) }
                )
            }
        }
    }
}

@Composable
private fun SummaryPanel(sessions: List<SessionRecord>) {
    val totalMs = sessions.sumOf { it.durationMs }
    val best = sessions.maxByOrNull { it.avgFps }
    Panel {
        Row {
            StatCell(
                label = "Runs",
                value = sessions.size.toString(),
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = Space.sm)
            )
            StatCell(
                label = "Recorded",
                value = String.format("%d:%02d", totalMs / 60000, (totalMs / 1000) % 60),
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
            )
            StatCell(
                label = "Best average",
                value = best?.let { String.format("%.0f", it.avgFps) } ?: "--",
                unit = "fps",
                accent = MaterialTheme.status.good,
                modifier = Modifier.weight(1f).padding(start = Space.sm)
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionRecord,
    threshold: Int,
    formattedDate: String,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val stateColor = fpsStateColor(session.avgFps, threshold)
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
            ) {
                Text(
                    text = session.appLabel.ifBlank {
                        session.appPackage.ifBlank { "Unknown app" }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Export session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(Space.sm))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (session.fpsSeries.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .padding(Space.md),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No frame samples in this run",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Sparkline(
                    values = session.fpsSeries,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    color = stateColor,
                    target = threshold.toFloat()
                )
            }
        }

        Spacer(Modifier.height(Space.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.sm))

        Row {
            StatCell(
                label = "Average",
                value = String.format("%.1f", session.avgFps),
                unit = "fps",
                accent = stateColor,
                modifier = Modifier.weight(1f).padding(end = Space.sm)
            )
            StatCell(
                label = "Max / min",
                value = "${session.maxFps.toInt()} / ${session.minFps.toInt()}",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
            )
            StatCell(
                label = "Smooth",
                value = session.smoothness.toInt().toString(),
                unit = "%",
                accent = MaterialTheme.status.good,
                modifier = Modifier.weight(1f).padding(start = Space.sm)
            )
        }
        Row(Modifier.padding(top = Space.md)) {
            StatCell(
                label = "1% low",
                value = session.low1Percent.toInt().toString(),
                accent = MaterialTheme.status.warn,
                modifier = Modifier.weight(1f).padding(end = Space.sm)
            )
            StatCell(
                label = "5% low",
                value = session.low5Percent.toInt().toString(),
                accent = MaterialTheme.status.warn,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm)
            )
            StatCell(
                label = "Jank",
                value = session.jankFrames.toString(),
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = Space.sm)
            )
        }

        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        MetricLine(
            label = "Duration",
            value = String.format(
                "%d:%02d",
                session.durationMs / 60000,
                (session.durationMs / 1000) % 60
            ),
            accent = MaterialTheme.colorScheme.onSurface
        )
        MetricLine(
            label = "Frames",
            value = session.totalFrames.toString(),
            accent = MaterialTheme.colorScheme.onSurfaceVariant
        )
        session.maxTempC?.let {
            MetricLine(
                label = "Peak temperature",
                value = "${it.toInt()}°C",
                accent = MaterialTheme.status.warn
            )
        }
        session.avgCpu?.let {
            MetricLine(
                label = "Average CPU",
                value = "${it.toInt()}%",
                accent = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Space.sm))
        StatusPill(
            text = if (session.avgFps >= threshold) "Held target" else "Missed target",
            color = stateColor
        )
    }
}
