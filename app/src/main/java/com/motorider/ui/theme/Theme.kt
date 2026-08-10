package com.motorider.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Each scheme picks the tone of a hue that suits its own surface — see the ramps in
 * `Color.kt`. The pairing rule throughout is that an `onX` role must clear 4.5:1
 * against its `X`, and a role used as a foreground (primary as a tinted icon, say)
 * must clear 4.5:1 against `surface` too. `scripts/contrast.py` enforces both.
 */
private val LightScheme = lightColorScheme(
    primary = BlueTone40,
    onPrimary = BrandWhite,
    primaryContainer = BlueTone90,
    onPrimaryContainer = BlueTone10,
    // Fill only — see OrangeVivid. Nothing draws text or icons in this colour.
    secondary = OrangeVivid,
    onSecondary = OnOrangeVivid,
    secondaryContainer = OrangeTone90,
    onSecondaryContainer = OrangeTone10,
    surface = SurfaceWhite,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    background = SurfaceWhite,
    onBackground = OnSurfaceLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    error = RedTone40,
    onError = BrandWhite,
    errorContainer = RedTone90,
    onErrorContainer = RedTone10
)

private val DarkScheme = darkColorScheme(
    // Light tones on top, dark tones underneath — the mirror of the light scheme.
    // Getting this backwards is what produced dark-blue text on a blue button.
    primary = BlueTone80,
    onPrimary = BlueTone20,
    primaryContainer = BlueTone30,
    onPrimaryContainer = BlueTone90,
    secondary = OrangeTone80,
    onSecondary = OrangeTone20,
    secondaryContainer = OrangeTone30,
    onSecondaryContainer = OrangeTone90,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    error = RedTone80,
    onError = RedTone20,
    errorContainer = RedTone30,
    onErrorContainer = RedTone90
)

// Whether the resolved (not raw-system) theme decision is dark - accounts for the
// user's explicit Light/Dark/System override, not just isSystemInDarkTheme().
// Composables that need to pick between a light-mode and dark-mode variant should
// read this rather than re-querying the system setting directly.
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
