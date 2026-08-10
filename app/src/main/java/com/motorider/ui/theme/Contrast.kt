package com.motorider.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Black or white on [background], whichever actually wins.
 *
 * For the few surfaces painted a **fixed** colour rather than a scheme role — the
 * turn banner, whose colour carries the urgency, and the map markers, which sit on
 * map tiles. Everything else takes its foreground from `MaterialTheme.colorScheme`.
 *
 * Both ratios are computed rather than compared against a hand-picked lightness
 * threshold. A threshold is guesswork, and the guess was wrong: at 0.35 this
 * returned white for a mid-orange banner, which is 3.08:1 where black would have
 * been 6.82:1. The real crossover is a luminance of about 0.179 — the point where
 * `1.05 / (L + 0.05)` meets `(L + 0.05) / 0.05` — and deriving it costs two
 * divisions.
 */
fun onBrandColor(background: Color): Color {
    val l = background.luminance()
    val contrastWithWhite = 1.05f / (l + 0.05f)
    val contrastWithBlack = (l + 0.05f) / 0.05f
    return if (contrastWithBlack >= contrastWithWhite) Color.Black else Color.White
}
