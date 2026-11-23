package com.framescope.monitor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.framescope.monitor.core.ThemeMode

/**
 * Semantic colours.
 *
 * These are deliberately kept out of [androidx.compose.material3.ColorScheme]: a green frame
 * rate means "healthy", not "brand", and the brand colour stays free for interactive elements.
 * Every value has a light and a dark variant that was picked against its own surface, so the
 * palette stays legible in both themes and with Material You enabled.
 */
@Immutable
data class StatusColors(
    val good: Color,
    val onGood: Color,
    val goodContainer: Color,
    val onGoodContainer: Color,
    val warn: Color,
    val onWarn: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val critical: Color,
    val onCritical: Color,
    val criticalContainer: Color,
    val onCriticalContainer: Color,
    val idle: Color,
    /** Background of meters, tracks and disabled fills. */
    val track: Color,
    /** Hairlines inside charts. */
    val grid: Color,
    /** Emphasised hairlines, e.g. a threshold marker. */
    val gridStrong: Color
)

private val DarkStatus = StatusColors(
    good = Color(0xFF4ADE9B),
    onGood = Color(0xFF003824),
    goodContainer = Color(0xFF0E3D2E),
    onGoodContainer = Color(0xFFA7F3CE),
    warn = Color(0xFFFFC44D),
    onWarn = Color(0xFF3A2600),
    warnContainer = Color(0xFF4A3300),
    onWarnContainer = Color(0xFFFFDF9E),
    critical = Color(0xFFFF7A7A),
    onCritical = Color(0xFF4A0007),
    criticalContainer = Color(0xFF5C0F14),
    onCriticalContainer = Color(0xFFFFD9D9),
    idle = Color(0xFF8FA0B8),
    track = Color(0xFF232C3A),
    grid = Color(0xFF263140),
    gridStrong = Color(0xFF3C4A5E)
)

private val LightStatus = StatusColors(
    good = Color(0xFF0B8A5B),
    onGood = Color(0xFFFFFFFF),
    goodContainer = Color(0xFFC9F3DF),
    onGoodContainer = Color(0xFF04311F),
    warn = Color(0xFF8A5B00),
    onWarn = Color(0xFFFFFFFF),
    warnContainer = Color(0xFFFFE6B0),
    onWarnContainer = Color(0xFF3A2600),
    critical = Color(0xFFC0392F),
    onCritical = Color(0xFFFFFFFF),
    criticalContainer = Color(0xFFFFDAD6),
    onCriticalContainer = Color(0xFF410002),
    idle = Color(0xFF6B7789),
    track = Color(0xFFE2E7EF),
    grid = Color(0xFFE6EAF1),
    gridStrong = Color(0xFFC3CDDA)
)

/**
 * The colours of the metrics themselves.
 *
 * A frame rate is green, a frame time is blue, the CPU is violet. These are not
 * "good" and "bad": they tell which line is which when several of them share a
 * chart, and they stay the same in every theme so a reading never changes its
 * meaning because the phone switched to light mode.
 */
@Immutable
data class MetricColors(
    val fps: Color,
    val frameTime: Color,
    val cpu: Color,
    val gpu: Color,
    val temperature: Color,
    val ram: Color,
    val power: Color,
    val battery: Color
)

private val DarkMetricColors = MetricColors(
    fps = Color(0xFF55D6A1),
    frameTime = Color(0xFF62A9FF),
    cpu = Color(0xFFB18CFF),
    gpu = Color(0xFF55B9D8),
    temperature = Color(0xFFFFAA63),
    ram = Color(0xFF7A8CFF),
    power = Color(0xFFE86FA8),
    battery = Color(0xFF9FD3C7)
)

private val LightMetricColors = MetricColors(
    fps = Color(0xFF0E8F63),
    frameTime = Color(0xFF2C6FD1),
    cpu = Color(0xFF7443C7),
    gpu = Color(0xFF1B7A99),
    temperature = Color(0xFFB85F10),
    ram = Color(0xFF4457C4),
    power = Color(0xFFB93C77),
    battery = Color(0xFF3F7F72)
)

/**
 * Whether repeating, decorative motion is allowed.
 *
 * It is switched off when the system asks for animations to be removed, which keeps the
 * breathing indicators from going on forever for people who turned them off on purpose.
 */
val LocalMotionEnabled = compositionLocalOf { true }

/**
 * Access the semantic palette from any composable: `MaterialTheme.status.good`.
 */
val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

/** The colours of the metrics, reachable the same way as the other palettes. */
val LocalMetricColors = staticCompositionLocalOf { DarkMetricColors }

val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

val MaterialTheme.metric: MetricColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMetricColors.current

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF56C8F0),
    onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF0B3E55),
    onPrimaryContainer = Color(0xFFC4E9FF),
    // The secondary role is a quiet slate blue. It used to be green, which put green on every
    // tonal button, slider track and navigation indicator and made a healthy frame rate
    // indistinguishable from the chrome around it.
    secondary = Color(0xFF8FB3C9),
    onSecondary = Color(0xFF0E2029),
    secondaryContainer = Color(0xFF16303D),
    onSecondaryContainer = Color(0xFFBBD9E8),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF442200),
    tertiaryContainer = Color(0xFF5E3200),
    onTertiaryContainer = Color(0xFFFFDDBB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFF1F4FA),
    surface = Color(0xFF121620),
    onSurface = Color(0xFFF1F4FA),
    surfaceVariant = Color(0xFF181E29),
    onSurfaceVariant = Color(0xFF929BAA),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF121620),
    surfaceContainer = Color(0xFF181E29),
    surfaceContainerHigh = Color(0xFF202735),
    surfaceContainerHighest = Color(0xFF293241),
    surfaceTint = Color(0xFF56C8F0),
    inverseSurface = Color(0xFFF1F4FA),
    inverseOnSurface = Color(0xFF10151C),
    outline = Color(0xFF3A4657),
    outlineVariant = Color(0xFF293241),
    scrim = Color(0xFF000000)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00678F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E6FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF4A6577),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE9F1),
    onSecondaryContainer = Color(0xFF10242F),
    tertiary = Color(0xFF8A5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB8),
    onTertiaryContainer = Color(0xFF2C1700),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF10151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10151C),
    surfaceVariant = Color(0xFFE8EDF4),
    onSurfaceVariant = Color(0xFF4A5666),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F6FA),
    surfaceContainer = Color(0xFFEEF2F7),
    surfaceContainerHigh = Color(0xFFE8EDF4),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    surfaceTint = Color(0xFF00678F),
    inverseSurface = Color(0xFF2B333D),
    inverseOnSurface = Color(0xFFEDF1F7),
    outline = Color(0xFFC3CDDA),
    outlineVariant = Color(0xFFDCE3EC),
    scrim = Color(0xFF000000)
)

@Composable
fun FrameScopeTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    motionEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = FrameScopeTypography,
        shapes = FrameScopeShapes,
        content = {
            // Semantic colours survive a wallpaper change: the meaning of "healthy" cannot
            // depend on the wallpaper the user picked, so only light/dark is swapped.
            androidx.compose.runtime.CompositionLocalProvider(
                LocalStatusColors provides if (dark) DarkStatus else LightStatus,
                LocalMetricColors provides if (dark) DarkMetricColors else LightMetricColors,
                LocalSurfaceColors provides surfaceColors(colors),
                LocalMotionEnabled provides motionEnabled,
                content = content
            )
        }
    )
}
