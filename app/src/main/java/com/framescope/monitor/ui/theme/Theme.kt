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

val MaterialTheme.status: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF56C8F0),
    onPrimary = Color(0xFF003547),
    primaryContainer = Color(0xFF0B3E55),
    onPrimaryContainer = Color(0xFFC4E9FF),
    secondary = Color(0xFF6FE0C0),
    onSecondary = Color(0xFF00382B),
    secondaryContainer = Color(0xFF0C4A3B),
    onSecondaryContainer = Color(0xFFB9F5E0),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF442200),
    tertiaryContainer = Color(0xFF5E3200),
    onTertiaryContainer = Color(0xFFFFDDBB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE4EAF2),
    surface = Color(0xFF0E131A),
    onSurface = Color(0xFFE4EAF2),
    surfaceVariant = Color(0xFF1B232E),
    onSurfaceVariant = Color(0xFF98A6B8),
    surfaceContainerLowest = Color(0xFF070A0E),
    surfaceContainerLow = Color(0xFF11171F),
    surfaceContainer = Color(0xFF151C25),
    surfaceContainerHigh = Color(0xFF1B232E),
    surfaceContainerHighest = Color(0xFF222B37),
    surfaceTint = Color(0xFF56C8F0),
    inverseSurface = Color(0xFFE4EAF2),
    inverseOnSurface = Color(0xFF10151C),
    outline = Color(0xFF38455A),
    outlineVariant = Color(0xFF263140),
    scrim = Color(0xFF000000)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF00678F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC8E6FF),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF006B57),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB8F0E0),
    onSecondaryContainer = Color(0xFF002018),
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
                LocalMotionEnabled provides motionEnabled,
                content = content
            )
        }
    )
}
