package com.motorider.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Black or white, whichever is readable on [background].
 *
 * Needed because several surfaces in this app are painted a **fixed brand colour**
 * rather than a role from the colour scheme — the turn banner (blue / orange / red
 * by urgency), the Find route button, the Quick Ride generate button. Their
 * foreground therefore cannot come from `onPrimary`: in the dark scheme that is
 * `BrandBlueDark`, which produced dark-blue text on a red banner and on a blue
 * button, at night, on a handlebar-mounted phone.
 *
 * The threshold is 0.35 rather than the usual 0.5 because [AccentOrange] sits at
 * about 0.41 relative luminance, right where the naive midpoint gets it wrong:
 * white on it is roughly 2.3:1, black roughly 9:1.
 */
fun onBrandColor(background: Color): Color =
    if (background.luminance() > BRAND_CONTRAST_THRESHOLD) Color.Black else Color.White

private const val BRAND_CONTRAST_THRESHOLD = 0.35f

/**
 * Button colours for a button painted a fixed brand colour.
 *
 * Use this instead of `ButtonDefaults.buttonColors(containerColor = …)`. That
 * overload replaces only the container and leaves the content colour at its
 * default of `onPrimary`, which is the bug described in [onBrandColor] — and it
 * fails silently, because it looks right in the light theme.
 */
@Composable
fun brandButtonColors(container: Color): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = onBrandColor(container)
    )
