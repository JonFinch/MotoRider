package com.motorider.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LightScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = BrandWhite,
    primaryContainer = BrandBlueLight,
    onPrimaryContainer = BrandBlueDark,
    secondary = AccentOrange,
    onSecondary = BrandWhite,
    secondaryContainer = AccentOrangeLight,
    onSecondaryContainer = AccentOrangeDark,
    surface = SurfaceWhite,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    background = SurfaceWhite,
    onBackground = OnSurfaceLight,
    outline = OutlineLight,
    outlineVariant = Gray300,
    error = ErrorRed,
    onError = OnError
)

private val DarkScheme = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = BrandBlueDark,
    primaryContainer = BrandBlue,
    onPrimaryContainer = BrandBlueLight,
    secondary = AccentOrangeLight,
    onSecondary = AccentOrangeDark,
    secondaryContainer = AccentOrange,
    onSecondaryContainer = AccentOrangeLight,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = Gray800,
    error = ErrorRed,
    onError = OnError
)

// Whether the resolved (not raw-system) theme decision is dark - accounts for the
// user's explicit Light/Dark/System override, not just isSystemInDarkTheme().
// Composables that need to pick between a light-mode and dark-mode color variant
// (e.g. waypoint field backgrounds) should read this rather than re-querying the
// system setting directly.
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun MotoRiderTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = Typography,
            content = content
        )
    }
}
