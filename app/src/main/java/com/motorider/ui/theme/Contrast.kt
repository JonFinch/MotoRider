package com.motorider.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Black or white, whichever is readable on [background].
 *
 * For the few surfaces painted a **fixed** colour rather than a scheme role — the
 * turn banner, whose colour carries the urgency, and the map markers, which sit on
 * map tiles. Everything else takes its foreground from `MaterialTheme.colorScheme`,
 * where the pairings are already checked (see `scripts/contrast.py`).
 *
 * The threshold is 0.35 rather than the usual midpoint because a mid-orange sits at
 * about 0.41 relative luminance, right where a naive 0.5 split gets it wrong: white
 * on it is roughly 2.3:1, black roughly 7:1.
 */
fun onBrandColor(background: Color): Color =
    if (background.luminance() > BRAND_CONTRAST_THRESHOLD) Color.Black else Color.White

private const val BRAND_CONTRAST_THRESHOLD = 0.35f
