package com.framescope.monitor.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * The app background.
 *
 * One flat vertical gradient from the background colour to the next surface step down. There is
 * no colour wash, no blob and no drifting animation behind the content: on a measuring tool the
 * background has to stay still, because anything that moves reads as a live reading and there is
 * nothing live about the backdrop.
 */
@Composable
fun AppBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        BackdropWash(Modifier.fillMaxSize())
        content()
    }
}

/**
 * The gradient of the app background on its own.
 *
 * The heading of a screen uses it as well, so the frosted panel in front of the
 * heading sits in the same light as the rest of the screen.
 */
@Composable
fun BackdropWash(modifier: Modifier = Modifier) {
    val colors = LocalSurfaceColors.current
    Canvas(modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(colors.backdropTop, colors.backdropBottom),
                endY = size.height
            )
        )
    }
}
