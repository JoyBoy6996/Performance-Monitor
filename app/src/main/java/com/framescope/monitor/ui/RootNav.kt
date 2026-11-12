package com.framescope.monitor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

enum class AppTab(val label: String, val icon: ImageVector) {
    MONITOR("Monitor", Icons.Rounded.Speed),
    OVERLAY("Overlay", Icons.Rounded.Layers),
    SESSIONS("Sessions", Icons.Rounded.Timeline),
    TOOLS("Tools", Icons.Rounded.Terminal),
    SETUP("Setup", Icons.Rounded.Tune)
}

@Composable
fun FrameScopeRoot(
    container: AppContainer,
    widthSizeClass: WindowWidthSizeClass,
    onRequestOverlayPermission: () -> Unit,
    onRequestScreenRecord: () -> Unit = {}
) {
    var tab by rememberSaveable { mutableStateOf(AppTab.MONITOR) }
    var openSession by remember { mutableStateOf<SessionRecord?>(null) }
    val compact = widthSizeClass == WindowWidthSizeClass.Compact
    // In compact mode the navigation bar owns the gesture inset, in expanded mode the
    // content has to keep clear of it itself.
    val navInset = remember { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() }
    val bottomPadding = if (compact) 24.dp else 24.dp + navInset

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize()) {
            if (!compact) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AppTab.entries.forEach { entry ->
                            NavigationRailItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(entry.label) }
                            )
                        }
                    }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f)) {
                    val session = openSession
                    if (session != null) {
                        SessionDetailScreen(
                            session = session,
                            threshold = container.settings.fpsThreshold.flow.collectAsStateValue(),
                            onBack = { openSession = null },
                            bottomPadding = bottomPadding
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
                            bottomPadding = bottomPadding,
                            onOpenSettings = { tab = AppTab.SETUP },
                            onOpenSession = { openSession = it }
                        )
                    }
                    }
                }

                if (compact) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        AppTab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon, contentDescription = null) },
                                label = { Text(entry.label) }
                            )
                        }
                    }
                }
            }
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
