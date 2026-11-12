package com.framescope.monitor.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope

@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = LocalGlassColors.current
    val motionEnabled = LocalMotionEnabled.current
    Box(modifier.fillMaxSize()) {
        if (motionEnabled) {
            AnimatedAurora(colors)
        } else {
            StaticAurora(colors)
        }
        content()
    }
}

@Composable
private fun AnimatedAurora(colors: GlassColors) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val driftA = transition.animateFloat(
        initialValue = -0.12f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(16_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraA"
    )
    val driftB = transition.animateFloat(
        initialValue = 0.10f,
        targetValue = -0.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(19_000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraB"
    )
    Canvas(Modifier.fillMaxSize()) {
        drawAurora(colors, driftA.value, driftB.value)
    }
}

@Composable
private fun StaticAurora(colors: GlassColors) {
    Canvas(Modifier.fillMaxSize()) {
        drawAurora(colors, driftA = 0f, driftB = 0f)
    }
}

private fun DrawScope.drawAurora(colors: GlassColors, driftA: Float, driftB: Float) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(colors.backdropTop, colors.backdropBottom),
            endY = size.height
        )
    )
    val radius = size.maxDimension * 0.82f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors.backdropGlowA, colors.backdropGlowA.copy(alpha = 0f)),
            center = Offset(size.width * (0.18f + driftA), size.height * 0.20f),
            radius = radius
        ),
        radius = radius,
        center = Offset(size.width * (0.18f + driftA), size.height * 0.20f)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colors.backdropGlowB, colors.backdropGlowB.copy(alpha = 0f)),
            center = Offset(size.width * (0.88f + driftB), size.height * 0.78f),
            radius = radius
        ),
        radius = radius,
        center = Offset(size.width * (0.88f + driftB), size.height * 0.78f)
    )
}
