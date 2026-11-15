package com.framescope.monitor.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The surface tokens.
 *
 * Every panel is a flat fill with a hairline around it. A shadow would add a second edge to
 * every card, and on a screen this dense that reads as noise, while the hairline already says
 * "this is a separate thing". The colours are derived from the colour scheme so they follow
 * Material You when it is on.
 */
@Immutable
data class SurfaceColors(
    /** The fill of a panel that sits on the background. */
    val fill: Color,
    /** The fill of a panel that sits inside another panel. */
    val fillNested: Color,
    /** A well: a recessed field that holds content, e.g. a terminal block. */
    val well: Color,
    /** The hairline that separates a surface from what is behind it. */
    val hairline: Color,
    /** A hairline that has to read a little stronger, e.g. the top edge of the navigation bar. */
    val hairlineStrong: Color,
    /** The app background, top edge. */
    val backdropTop: Color,
    /** The app background, bottom edge. */
    val backdropBottom: Color
)

internal fun surfaceColors(scheme: ColorScheme) = SurfaceColors(
    fill = scheme.surface,
    fillNested = scheme.surfaceContainerHigh,
    well = scheme.surfaceContainerLowest,
    hairline = scheme.outlineVariant,
    hairlineStrong = scheme.outline,
    backdropTop = scheme.background,
    backdropBottom = scheme.surfaceContainerLow
)

val LocalSurfaceColors = compositionLocalOf {
    SurfaceColors(
        fill = Color(0xFF0E131A),
        fillNested = Color(0xFF1B232E),
        well = Color(0xFF070A0E),
        hairline = Color(0xFF263140),
        hairlineStrong = Color(0xFF38455A),
        backdropTop = Color(0xFF0A0E13),
        backdropBottom = Color(0xFF11171F)
    )
}

/**
 * Paints a flat surface with a hairline around it.
 *
 * The fill and the border are the whole treatment: no shadow, no sheen, no gradient. Depth comes
 * from the surface steps in the colour scheme, not from effects on top of them.
 */
@Composable
fun Modifier.hairlineSurface(
    shape: Shape,
    fill: Color = LocalSurfaceColors.current.fill,
    hairline: Color = LocalSurfaceColors.current.hairline
): Modifier = this
    .background(color = fill, shape = shape)
    .border(width = 1.dp, color = hairline, shape = shape)
