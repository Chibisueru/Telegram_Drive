package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import androidx.compose.ui.graphics.Color

// Same palette as the Android app (seed #1F5FBF).
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A5EA8), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF565F71), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9), onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF705574), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFAD8FD), onTertiaryContainer = Color(0xFF28132E),
    background = Color(0xFFFAF9FF), onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFAF9FF), onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4), surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE3E2E9),
    outline = Color(0xFF74777F), outlineVariant = Color(0xFFC4C6D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF08458E), onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBEC6DC), onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759), onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDDBCE0), onTertiary = Color(0xFF3F2844),
    tertiaryContainer = Color(0xFF573E5C), onTertiaryContainer = Color(0xFFFAD8FD),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E), onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF0C0E13), surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474E),
)

/**
 * [themeMode]: 0 follows the desktop's dark/light setting, 1 light, 2 dark.
 * [systemAccent]: build the colors from the desktop's accent color (like Material You on
 * Android); falls back to the app's own blue when the desktop doesn't have one.
 */
@Composable
fun TgDriveTheme(themeMode: Int, systemAccent: Boolean = true, content: @Composable () -> Unit) {
    val look by SystemLookWatcher.look.collectAsState()
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> look.dark ?: isSystemInDarkTheme()
    }
    val accent = look.accent.takeIf { systemAccent }
    val scheme = remember(accent, dark) { accent?.let { schemeFrom(it, dark) } ?: if (dark) DarkColors else LightColors }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A full Material 3 color scheme generated from one seed color (the "tonal spot" style Android uses). */
fun schemeFrom(seed: Int, dark: Boolean): ColorScheme {
    val s = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    fun c(argb: Int) = Color(argb)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c(s.primary), onPrimary = c(s.onPrimary),
        primaryContainer = c(s.primaryContainer), onPrimaryContainer = c(s.onPrimaryContainer),
        inversePrimary = c(s.inversePrimary),
        secondary = c(s.secondary), onSecondary = c(s.onSecondary),
        secondaryContainer = c(s.secondaryContainer), onSecondaryContainer = c(s.onSecondaryContainer),
        tertiary = c(s.tertiary), onTertiary = c(s.onTertiary),
        tertiaryContainer = c(s.tertiaryContainer), onTertiaryContainer = c(s.onTertiaryContainer),
        background = c(s.background), onBackground = c(s.onBackground),
        surface = c(s.surface), onSurface = c(s.onSurface),
        surfaceVariant = c(s.surfaceVariant), onSurfaceVariant = c(s.onSurfaceVariant),
        surfaceTint = c(s.surfaceTint),
        inverseSurface = c(s.inverseSurface), inverseOnSurface = c(s.inverseOnSurface),
        error = c(s.error), onError = c(s.onError),
        errorContainer = c(s.errorContainer), onErrorContainer = c(s.onErrorContainer),
        outline = c(s.outline), outlineVariant = c(s.outlineVariant), scrim = c(s.scrim),
        surfaceBright = c(s.surfaceBright), surfaceDim = c(s.surfaceDim),
        surfaceContainer = c(s.surfaceContainer), surfaceContainerHigh = c(s.surfaceContainerHigh),
        surfaceContainerHighest = c(s.surfaceContainerHighest), surfaceContainerLow = c(s.surfaceContainerLow),
        surfaceContainerLowest = c(s.surfaceContainerLowest),
    )
}
