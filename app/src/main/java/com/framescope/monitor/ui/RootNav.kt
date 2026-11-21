package com.framescope.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.ui.screens.DashboardScreen
import com.framescope.monitor.ui.screens.OverlayScreen
import com.framescope.monitor.ui.screens.SessionDetailScreen
import com.framescope.monitor.ui.screens.SessionsScreen
import com.framescope.monitor.ui.screens.SettingsScreen
import com.framescope.monitor.ui.screens.ToolsScreen
import com.framescope.monitor.ui.theme.AppBackdrop
import com.framescope.monitor.ui.theme.LocalSurfaceColors
import com.framescope.monitor.ui.theme.Space

enum class AppTab(val label: String, val icon: ImageVector) {
    MONITOR("Monitor", Icons.Rounded.Speed),
    OVERLAY("Overlay", Icons.Rounded.Layers),
    SESSIONS("Sessions", Icons.Rounded.Timeline),
    TOOLS("Tools", Icons.Rounded.Terminal),
    SETUP("Setup", Icons.Rounded.Tune)
}

/**
 * The shell of the app: the content on top, the navigation bar along the bottom.
 *
 * The navigation sits at the bottom in every window size. A side rail only makes sense on a
 * screen that is wider than it is tall, and this app is opened on a phone that is being played on,
 * where the bottom edge is where the thumb already is.
 */
@Composable
fun FrameScopeRoot(
    container: AppContainer,
    widthSizeClass: WindowWidthSizeClass,
    onRequestOverlayPermission: () -> Unit,
    onRequestScreenRecord: () -> Unit = {}
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.MONITOR) }
    var openSession by remember { mutableStateOf<SessionRecord?>(null) }

    // A session is a page inside the app, not a new Activity. Without this the
    // hardware Back key falls through and finishes the whole app from the detail page.
    BackHandler(enabled = openSession != null) {
        openSession = null
    }

    // On a wide screen the content stays a readable column instead of stretching edge to edge.
    val contentModifier = if (widthSizeClass == WindowWidthSizeClass.Compact) {
        Modifier.fillMaxHeight()
    } else {
        Modifier.fillMaxHeight().widthIn(max = 720.dp)
    }

    AppBackdrop(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(modifier = contentModifier) {
                    val session = openSession
                    if (session != null) {
                        SessionDetailScreen(
                            session = session,
                            threshold = container.settings.fpsThreshold.flow.collectAsStateValue(),
                            onBack = { openSession = null },
                            onUpdateNote = { note -> container.store.updateNote(session.id, note) },
                            bottomPadding = Space.xl
                        )
                    } else {
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith
                                    fadeOut(animationSpec = tween(160))
                            },
                            label = "tab"
                        ) { current ->
                            TabContent(
                                tab = current,
                                container = container,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onRequestScreenRecord = onRequestScreenRecord,
                                bottomPadding = Space.xl,
                                onOpenSettings = { tab = AppTab.SETUP },
                                onOpenSession = { openSession = it }
                            )
                        }
                    }
                }
            }

            FrameScopeNavigationBar(
                tab = tab,
                onSelect = { tab = it }
            )
        }
    }
}

/**
 * The navigation bar.
 *
 * A flat surface with a single hairline along the top edge. The bar owns the system inset, so
 * the icons stay above the gesture bar without the content having to know about it.
 */
@Composable
private fun FrameScopeNavigationBar(
    tab: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalSurfaceColors.current
    NavigationBar(
        modifier = modifier.drawBehind {
            drawLine(
                color = colors.hairlineStrong,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx()
            )
        },
        containerColor = colors.fill,
        tonalElevation = 0.dp
    ) {
        AppTab.entries.forEach { entry ->
            NavigationBarItem(
                selected = tab == entry,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(entry.label) },
                // The indicator is a neutral step. A coloured one would put a second signal
                // colour on screen next to the frame rate reading.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun TabContent(
    tab: AppTab,
    container: AppContainer,
    onRequestOverlayPermission: () -> Unit,
    onRequestScreenRecord: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenSettings: () -> Unit,
    onOpenSession: (SessionRecord) -> Unit
) {
    when (tab) {
        AppTab.MONITOR -> DashboardScreen(
            container = container,
            onOpenSettings = onOpenSettings,
            bottomPadding = bottomPadding,
            onRequestScreenRecord = onRequestScreenRecord
        )
        AppTab.OVERLAY -> OverlayScreen(
            container = container,
            onRequestOverlayPermission = onRequestOverlayPermission,
            bottomPadding = bottomPadding
        )
        AppTab.SESSIONS -> SessionsScreen(
            container = container,
            onOpenSession = onOpenSession,
            bottomPadding = bottomPadding
        )
        AppTab.TOOLS -> ToolsScreen(container = container, bottomPadding = bottomPadding)
        AppTab.SETUP -> SettingsScreen(container = container, bottomPadding = bottomPadding)
    }
}
