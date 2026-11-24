package com.framescope.monitor.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.ui.FrameScopeScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full breakdown of one recorded run.
 *
 * The run is shown as the Scene cards, from top to bottom: the same eleven
 * cards the PDF carries, in the same order. A second summary of the same
 * numbers used to sit above them - a summary card, a charts/table switch and
 * a "what happened" timeline. It said the same thing twice in a different
 * shape and pushed the cards below the fold, so it was removed.
 */
@Composable
fun SessionDetailScreen(
    session: SessionRecord,
    threshold: Int,
    onBack: () -> Unit,
    onUpdateNote: (String) -> Unit = {},
    bottomPadding: Dp = 24.dp
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    val appName = session.appLabel.ifBlank {
        session.appPackage.ifBlank { "Unknown app" }
    }
    var shareOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        FrameScopeScreen(
            title = appName,
            subtitle = "${formatter.format(Date(session.startedAt))} · " +
                formatDuration(session.durationMs),
            bottomPadding = bottomPadding,
            // The way back belongs on the left, where every other app puts it.
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                TextButton(onClick = {
                    SessionShare.exportPdfToDownloads(context, session, threshold)
                }) {
                    Text("PDF")
                }
                IconButton(onClick = { shareOpen = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share session",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        ) {
            item {
                SceneSessionCards(
                    session = session,
                    threshold = threshold,
                    onUpdateNote = onUpdateNote
                )
            }
        }

        if (shareOpen) {
            SessionShareDialog(
                session = session,
                threshold = threshold,
                onDismiss = { shareOpen = false }
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
