package com.framescope.monitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.ui.components.FrostedHeading
import com.framescope.monitor.ui.theme.Space

/**
 * The shape of every screen of the app.
 *
 * The heading floats on top of the content: the content scrolls under the
 * frosted panel instead of being pushed down by it, so the panel always shows
 * what is underneath it and the list keeps its full height.
 *
 * The space of the heading is measured, so the content starts below the panel
 * whatever the size of the text is. A phone with big fonts gets a taller panel
 * and the content moves down with it.
 */
@Composable
fun FrameScopeScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    bottomPadding: Dp = 24.dp,
    listState: LazyListState = rememberLazyListState(),
    /**
     * The button that goes back, on the left of the title.
     *
     * A screen that is opened from another one has its way back on the left,
     * where the thumb expects it and where every other app puts it.
     */
    navigationIcon: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    var headingHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.gutter,
                end = Space.gutter,
                // The heading floats over the list, so the list starts below it.
                top = headingHeight + Space.md,
                bottom = bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
            content = content
        )

        FrostedHeading(
            title = title,
            subtitle = subtitle,
            navigationIcon = navigationIcon,
            actions = actions,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { size ->
                    val height = with(density) { size.height.toDp() }
                    if (height != headingHeight) headingHeight = height
                }
        )
    }
}
