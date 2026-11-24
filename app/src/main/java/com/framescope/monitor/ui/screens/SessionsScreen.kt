package com.framescope.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.MAX_HOTSPOT_TEMP_C
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.core.finiteOrNull
import com.framescope.monitor.core.plausibleTempC
import com.framescope.monitor.ui.FrameScopeScreen
import com.framescope.monitor.ui.components.ChipRow
import com.framescope.monitor.ui.components.EmptyState
import com.framescope.monitor.ui.components.Panel
import com.framescope.monitor.ui.components.Sparkline
import com.framescope.monitor.ui.components.StatCell
import com.framescope.monitor.ui.components.fpsStateColor
import com.framescope.monitor.ui.theme.Space
import com.framescope.monitor.ui.theme.status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The name of the chip that shows every app. */
private const val ALL_APPS = "All"

/** One formatter for every number here: bad values become "--", never NaN. */
private fun fmt(value: Float?, decimals: Int): String {
    val number = value.finiteOrNull() ?: return "--"
    return String.format(Locale.US, "%.${decimals}f", number)
}

@Composable
fun SessionsScreen(
    container: AppContainer,
    onOpenSession: (SessionRecord) -> Unit = {},
    onOpenCompare: () -> Unit = {},
    bottomPadding: Dp = 24.dp
) {
    val sessions by container.store.sessions.collectAsState()
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val threshold = container.settings.fpsThreshold.flow.collectAsState().value
    var shareTarget by remember { mutableStateOf<SessionRecord?>(null) }
    var search by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionRecord?>(null) }

    // The list is searched by the name of the app and can be narrowed down to
    // one app. The search is over the name and the package, because the label
    // of an app is not always known.
    val visible = remember(sessions, search, appFilter) {
        sessions.filter { session ->
            val needle = search.trim()
            val matchesText = needle.isEmpty() ||
                session.appLabel.contains(needle, ignoreCase = true) ||
                session.appPackage.contains(needle, ignoreCase = true)
            val matchesApp = appFilter == null ||
                session.appLabel == appFilter ||
                session.appPackage == appFilter
            matchesText && matchesApp
        }
    }
    val appNames = remember(sessions) {
        sessions.map { it.appLabel.ifBlank { it.appPackage.ifBlank { "Unknown app" } } }
            .distinct()
            .sorted()
    }

    Box(Modifier.fillMaxSize()) {
    FrameScopeScreen(
        title = "Sessions",
        subtitle = if (sessions.isEmpty()) {
            "Recorded runs are stored on this device"
        } else {
            "${sessions.size} recorded ${if (sessions.size == 1) "run" else "runs"}"
        },
        bottomPadding = bottomPadding,
        actions = {
            if (sessions.size >= 2) {
                TextButton(onClick = onOpenCompare) {
                    Text("Compare")
                }
            }
        }
    ) {
        if (sessions.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Timeline,
                    title = "No sessions yet",
                    message = "Record a run from the Monitor tab, then come back for the breakdown."
                )
            }
            return@FrameScopeScreen
        }

        item { SummaryPanel(sessions) }

        if (sessions.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search by app") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear the search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }

            if (appNames.size > 1) {
                item {
                    ChipRow(
                        options = listOf(ALL_APPS) + appNames,
                        selected = setOfNotNull(appFilter ?: ALL_APPS),
                        onToggle = { name ->
                            appFilter = if (name == ALL_APPS) null else name
                        }
                    )
                }
            }
        }

        if (sessions.isNotEmpty() && visible.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "Nothing matches",
                    message = "No recorded run of this app matches the search."
                )
            }
        }

        visible.forEach { session ->
            item(key = session.id) {
                SwipeToDeleteSession(
                    onDelete = { deleteTarget = session }
                ) {
                    SessionCard(
                        session = session,
                        threshold = threshold,
                        formattedDate = formatter.format(Date(session.startedAt)),
                        onOpen = { onOpenSession(session) },
                        onShare = { shareTarget = session },
                        onExportPdf = {
                            SessionShare.exportPdfToDownloads(context, session, threshold)
                        },
                        onDelete = { deleteTarget = session }
                    )
                }
            }
        }
    }

    shareTarget?.let { target ->
        SessionShareDialog(
            session = target,
            threshold = threshold,
            onDismiss = { shareTarget = null }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this run?") },
            text = {
                Text(
                    "The run of ${target.appLabel.ifBlank { target.appPackage.ifBlank { "this app" } }} " +
                        "is removed from this device. It cannot be brought back."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.store.delete(target.id)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.status.critical)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Keep") }
            }
        )
    }
    }
}

/**
 * A run that can be pushed away to delete it.
 *
 * The push only asks; the run is deleted after the user says yes, because a
 * recorded run cannot be brought back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSession(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            // The card stays where it is: the delete needs a word from the user.
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Space.lg)
                    .background(
                        MaterialTheme.status.critical.copy(alpha = 0.18f),
                        MaterialTheme.shapes.medium
                    ),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.status.critical
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.status.critical
                    )
                }
            }
        }
    ) {
        content()
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
                value = best?.let { fmt(it.avgFps, 0) } ?: "--",
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
    onExportPdf: () -> Unit,
    onDelete: () -> Unit
) {
    val stateColor = fpsStateColor(session.avgFps, threshold)
    var menuOpen by remember { mutableStateOf(false) }

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            AppIcon(session.appPackage)
            Spacer(Modifier.width(Space.sm))
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
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
            // The actions live behind one button: a delete that sits next to a
            // share is one tap away from being the wrong one.
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More actions for this run",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Export PDF") },
                        onClick = {
                            menuOpen = false
                            onExportPdf()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            onShare()
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = MaterialTheme.status.critical)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.xs))
        val fpsPoints = session.fpsSeries.filter { it.finiteOrNull() != null }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            if (fpsPoints.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
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
                    values = fpsPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    color = stateColor,
                    target = threshold.toFloat()
                )
            }
        }

        Spacer(Modifier.height(Space.sm))

        // One row of the four numbers that matter. The run used to carry two
        // rows of six cells between them, and the second row repeated the
        // family of the first one.
        Row(Modifier.fillMaxWidth()) {
            StatCell(
                label = "Avg",
                value = fmt(session.avgFps, 0),
                unit = "fps",
                accent = stateColor,
                modifier = Modifier.weight(1f).padding(end = Space.xs)
            )
            StatCell(
                label = "Min / max",
                value = "${fmt(session.minFps, 0)} / ${fmt(session.maxFps, 0)}",
                accent = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = Space.xs)
            )
            StatCell(
                label = "1% low",
                value = fmt(session.low1Percent, 0),
                unit = "fps",
                accent = MaterialTheme.status.warn,
                modifier = Modifier.weight(1f).padding(horizontal = Space.xs)
            )
            StatCell(
                label = "Temp / power",
                value = sessionTempAndPower(session),
                accent = MaterialTheme.status.warn,
                modifier = Modifier.weight(1f).padding(start = Space.xs)
            )
        }

        Spacer(Modifier.height(Space.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Space.xs))

        // Everything else about the run on one quiet line.
        Text(
            text = sessionFooter(session, threshold),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The temperature and the power of a run, side by side.
 *
 * A run without them says so, and never carries a number that was not
 * measured.
 */
internal fun sessionTempAndPower(session: SessionRecord): String {
    val temp = session.maxTempC.plausibleTempC(MAX_HOTSPOT_TEMP_C)?.let { "${it.toInt()}°" }
    val power = session.avgPowerW?.let { String.format(Locale.US, "%.1fW", it) }
    return listOfNotNull(temp, power).joinToString(" / ").ifBlank { "--" }
}

/**
 * The rest of the run on one line.
 *
 * The card is scanned, not read: the numbers that matter are in the row above
 * and the rest belongs on a single quiet line under it.
 */
internal fun sessionFooter(session: SessionRecord, threshold: Int): String {
    val duration = String.format(
        Locale.US,
        "%d:%02d",
        session.durationMs / 60000,
        (session.durationMs / 1000) % 60
    )
    return listOf(
        "Duration $duration",
        "${session.totalFrames} frames",
        "Variance ${fmt(session.fpsVariance, 1)}",
        "Smooth ${fmt(session.sceneSmoothnessPercent, 0)}%",
        if (session.avgFps >= threshold) "Held target" else "Missed target"
    ).joinToString(" · ")
}
