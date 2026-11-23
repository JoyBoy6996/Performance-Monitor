package com.framescope.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.framescope.monitor.AppContainer
import com.framescope.monitor.core.SessionRecord
import com.framescope.monitor.ui.screens.CompareScreen
import com.framescope.monitor.ui.screens.DashboardScreen
import com.framescope.monitor.ui.screens.OverlayScreen
import com.framescope.monitor.ui.screens.SessionDetailScreen
import com.framescope.monitor.ui.screens.SessionsScreen
import com.framescope.monitor.ui.screens.SettingsScreen
import com.framescope.monitor.ui.screens.ToolsScreen
import com.framescope.monitor.ui.theme.AppBackdrop
import com.framescope.monitor.ui.theme.LocalMotionEnabled
import com.framescope.monitor.ui.theme.LocalSurfaceColors
import com.framescope.monitor.ui.theme.Space

/**
 * The places the app can be in.
 *
 * Four of them. The overlay is one of them because the numbers on top of the
 * game are the reason the app exists, and the tools are a page inside the
 * settings: they are set up once and then left alone.
 */
enum class AppTab(val label: String, val icon: ImageVector) {
    MONITOR("Monitor", Icons.Rounded.Speed),
    SESSIONS("Sessions", Icons.Rounded.Timeline),
    OVERLAY("Overlay", Icons.Rounded.Layers),
    SETTINGS("Settings", Icons.Rounded.Tune)
}

/**
 * A page that is open inside a tab.
 *
 * A tab says where the user is; a page says what is open there. Choosing
 * another tab closes the page, because a tab is always the stronger of the
 * two: a session that is open must never hide the place the user just chose.
 */
sealed interface OpenPage {
    /** The report of one recorded run. */
    data class Session(val session: SessionRecord) : OpenPage

    /** The comparison of two recorded runs. */
    data object Compare : OpenPage

    /** The facts of the device and a privileged shell. */
    data object Tools : OpenPage
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
    val nav = rememberSaveable(saver = NavState.Saver) { NavState() }

    // A page inside the app, not a new Activity. Without this the hardware
    // Back key falls through and finishes the whole app from the report of a
    // run. The page closes first and the tab stays where it was.
    BackHandler(enabled = nav.hasOpenPage) {
        nav.closePage()
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
                    val page = nav.openPage
                    if (page != null) {
                        when (page) {
                            is OpenPage.Session -> SessionDetailScreen(
                                session = page.session,
                                threshold = container.settings.fpsThreshold.flow.collectAsStateValue(),
                                onBack = { nav.closePage() },
                                onUpdateNote = { note ->
                                    container.store.updateNote(page.session.id, note)
                                },
                                bottomPadding = Space.xl
                            )

                            OpenPage.Compare -> CompareScreen(
                                container = container,
                                bottomPadding = Space.xl,
                                onBack = { nav.closePage() }
                            )

                            OpenPage.Tools -> ToolsScreen(
                                container = container,
                                bottomPadding = Space.xl,
                                onBack = { nav.closePage() }
                            )
                        }
                    } else {
                        val motion = LocalMotionEnabled.current
                        AnimatedContent(
                            targetState = nav.tab,
                            transitionSpec = {
                                // The animation follows the system: when the phone
                                // is set to no animation, the screen changes at once.
                                if (motion) {
                                    fadeIn(animationSpec = tween(220)) togetherWith
                                        fadeOut(animationSpec = tween(160))
                                } else {
                                    EnterTransition.None togetherWith ExitTransition.None
                                }
                            },
                            label = "tab"
                        ) { current ->
                            TabContent(
                                tab = current,
                                container = container,
                                onRequestOverlayPermission = onRequestOverlayPermission,
                                onRequestScreenRecord = onRequestScreenRecord,
                                bottomPadding = Space.xl,
                                onOpenSettings = { nav.selectTab(AppTab.SETTINGS) },
                                onOpenSession = { nav.openSession(it) },
                                onOpenCompare = { nav.openCompare() },
                                onOpenTools = { nav.openTools() }
                            )
                        }
                    }
                }
            }

            FrameScopeNavigationBar(
                tab = nav.tab,
                onSelect = { selected ->
                    // The bar is always the stronger of the two. Choosing another
                    // place closes what was open inside the old one, so a report
                    // of a run can never stay on top of the screen the user
                    // just asked for.
                    nav.selectTab(selected)
                }
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
    onOpenSession: (SessionRecord) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenTools: () -> Unit
) {
    when (tab) {
        AppTab.MONITOR -> DashboardScreen(
            container = container,
            onOpenSettings = onOpenSettings,
            bottomPadding = bottomPadding,
            onRequestScreenRecord = onRequestScreenRecord
        )
        AppTab.SESSIONS -> SessionsScreen(
            container = container,
            onOpenSession = onOpenSession,
            onOpenCompare = onOpenCompare,
            bottomPadding = bottomPadding
        )
        AppTab.OVERLAY -> OverlayScreen(
            container = container,
            onRequestOverlayPermission = onRequestOverlayPermission,
            bottomPadding = bottomPadding
        )
        AppTab.SETTINGS -> SettingsScreen(
            container = container,
            bottomPadding = bottomPadding,
            onOpenTools = onOpenTools
        )
    }
}
