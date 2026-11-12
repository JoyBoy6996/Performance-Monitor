package com.framescope.monitor.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GlassColors(
    val fill: Color,
    val fillNested: Color,
    val hairline: Color,
    val hairlineHighlight: Color,
    val shadow: Color,
    val textScrim: Color,
    val backdropTop: Color,
    val backdropBottom: Color,
    val backdropGlowA: Color,
    val backdropGlowB: Color
)

internal fun glassColors(dark: Boolean, surface: Color) = if (dark) {
    GlassColors(
        fill = surface.copy(alpha = 0.55f),
        fillNested = surface.copy(alpha = 0.78f),
        hairline = Color.White.copy(alpha = 0.14f),
        hairlineHighlight = Color.White.copy(alpha = 0.28f),
        shadow = Color.Black.copy(alpha = 0.45f),
        textScrim = Color.Black.copy(alpha = 0.22f),
        backdropTop = Color(0xFF0A0E13),
        backdropBottom = Color(0xFF101A26),
        backdropGlowA = Color(0xFF56C8F0).copy(alpha = 0.26f),
        backdropGlowB = Color(0xFF6FE0C0).copy(alpha = 0.18f)
    )
} else {
    GlassColors(
        fill = surface.copy(alpha = 0.62f),
        fillNested = surface.copy(alpha = 0.88f),
        hairline = Color.White.copy(alpha = 0.55f),
        hairlineHighlight = Color.White.copy(alpha = 0.85f),
        shadow = Color.Black.copy(alpha = 0.12f),
        textScrim = Color.White.copy(alpha = 0.35f),
        backdropTop = Color(0xFFEEF3FA),
        backdropBottom = Color(0xFFDDE6F2),
        backdropGlowA = Color(0xFF00678F).copy(alpha = 0.14f),
        backdropGlowB = Color(0xFF006B57).copy(alpha = 0.10f)
    )
}

val LocalGlassColors = compositionLocalOf {
    glassColors(dark = true, surface = Color(0xFF0E131A))
}

/**
 * Draws the translucent, layered fallback glass treatment. The gradient and radial sheen keep
 * the surface legible and dimensional on API levels without platform backdrop blur.
 */
@Composable
fun Modifier.glass(
    shape: Shape = MaterialTheme.shapes.large,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    hairline: Color = Color.White.copy(alpha = 0.16f),
    blurRadius: Dp = 28.dp,
    elevation: Dp = 10.dp,
    topHairlineOnly: Boolean = false
): Modifier {
    val colors = LocalGlassColors.current
    return this
        // Keep translucent surface painting separate from its crisp child content.
        .graphicsLayer { alpha = 0.999f }
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = colors.shadow,
            spotColor = colors.shadow
        )
        .clip(shape)
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                brush = Brush.verticalGradient(
                    colors = listOf(tint, tint.copy(alpha = tint.alpha * 0.88f)),
                    endY = size.height
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.055f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset.Zero,
                    radius = size.maxDimension * 1.15f
                )
            )
            if (topHairlineOnly) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(colors.hairlineHighlight, hairline, colors.hairlineHighlight)
                    ),
                    start = androidx.compose.ui.geometry.Offset(0f, 0.5f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0.5f),
                    strokeWidth = 1.dp.toPx()
                )
            } else {
                drawOutline(
                    outline = outline,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            colors.hairlineHighlight,
                            hairline,
                            hairline.copy(alpha = 0f)
                        ),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height)
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
}
