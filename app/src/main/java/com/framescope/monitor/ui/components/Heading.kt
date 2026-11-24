package com.framescope.monitor.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.framescope.monitor.ui.theme.BackdropWash
import com.framescope.monitor.ui.theme.Space

/**
 * The heading of a screen, on a frosted panel.
 *
 * The panel sits over the content: the content scrolls under it and shows
 * through a little, the way frosted glass shows what is behind it. The panel
 * itself is a translucent surface with a hairline around it and rounded
 * corners, so the edge of the heading is a soft line and not a hard cut.
 *
 * On Android 12 and later the background behind the panel is really blurred
 * with a render effect. On older phones the same translucent panel is used and
 * the blur is left out, because the system cannot draw it there.
 *
 * The heading and the icons are never part of the blur: they are drawn on top
 * of the panel, so they stay sharp and readable over any content.
 */
@Composable
fun FrostedHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    cornerRadius: Dp = 24.dp,
    /**
     * The button that goes back, on the left of the title.
     *
     * The way back belongs on the left of a heading, where every other app
     * puts it, and not among the actions on the right.
     */
    navigationIcon: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)
    val surface = MaterialTheme.colorScheme.surface
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Box(modifier.fillMaxWidth()) {
        // The blurred background. It is the app's own backdrop, so the panel
        // sits in the same light as the rest of the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(
                        radius = 26.dp,
                        edgeTreatment = BlurredEdgeTreatment(shape)
                    )
                    .alpha(0.55f)
            ) {
                BackdropWash(Modifier.fillMaxSize())
            }
        }

        // The translucent surface and its hairline.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(surface.copy(alpha = 0.86f), shape)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, hairline.copy(alpha = 0.5f), shape)
        )

        // The heading itself, drawn last so nothing blurs it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Space.gutter, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            navigationIcon()
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            actions()
        }
    }
}
