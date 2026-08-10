package com.motorider.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * MotoRider's palette.
 *
 * The legibility bar here is higher than for a typical app, and deliberately so:
 * this is read on a handlebar-mounted phone, in direct sun or at night, at a
 * glance, sometimes through a visor. Every foreground/background pair the UI puts
 * together clears WCAG AA (4.5:1), and body text mostly clears AAA (7:1).
 * `scripts/contrast.py` checks this and fails the audit if a pair regresses.
 *
 * The brand hues each need **two** tones, which is what the previous palette got
 * wrong. A blue dark enough to read on white is too dark to read on the night
 * surface, and vice versa; a single `BrandBlue` used for both gave 5.75:1 in the
 * light theme and 2.97:1 in the dark one. So each hue has a ramp, and the schemes
 * in `Theme.kt` pick the tone that suits their surface.
 *
 * Tone numbers follow Material 3's convention — roughly "percent lightness", where
 * 10 is near-black and 90 is near-white.
 */

// ─── Blue ───────────────────────────────────────────────────────────────────

val BlueTone10 = Color(0xFF001C38)
val BlueTone20 = Color(0xFF00315E)
val BlueTone30 = Color(0xFF00468C)
/** The brand blue. Dark enough to read as text on white, and to carry white on it. */
val BlueTone40 = Color(0xFF1565C0)
/** The night-side blue: light enough to read on the dark surface. */
val BlueTone80 = Color(0xFF8ECBFF)
val BlueTone90 = Color(0xFFD6E7FF)

// ─── Orange ─────────────────────────────────────────────────────────────────

val OrangeTone10 = Color(0xFF331200)
val OrangeTone20 = Color(0xFF4F1D00)
val OrangeTone30 = Color(0xFF7A3A00)
/**
 * The vivid brand orange, kept vivid on purpose — and therefore **fill only**.
 *
 * At 3.79:1 on white it clears the 3:1 that WCAG asks of a non-text component (a
 * button, a slider track, the compass needle) and falls well short of the 4.5:1
 * text needs. So `secondary` is never a text or icon colour in the light theme:
 * anything that wants to read as orange uses `secondaryContainer` and puts
 * [OrangeTone10] on it. The old palette used this hue as a text colour and got
 * 2.29:1, which is how the Quick Ride readout ended up the least readable thing on
 * a screen used in direct sun.
 */
val OrangeVivid = Color(0xFFE65100)
/** Black, not white, on [OrangeVivid]: 5.54:1 against 3.79:1. */
val OnOrangeVivid = Color(0xFF000000)
val OrangeTone80 = Color(0xFFFFB870)
val OrangeTone90 = Color(0xFFFFDCC2)

// ─── Red ────────────────────────────────────────────────────────────────────

val RedTone10 = Color(0xFF410E0B)
val RedTone20 = Color(0xFF5F1410)
val RedTone30 = Color(0xFF93000A)
val RedTone40 = Color(0xFFB3261E)
val RedTone80 = Color(0xFFFF9A90)
val RedTone90 = Color(0xFFFFDAD6)

// ─── Neutrals ───────────────────────────────────────────────────────────────

/**
 * Pure white in daylight. Not softened to an off-white: in direct sun the brightest
 * possible background is what makes text readable, and glare from the screen is a
 * far smaller problem than glare from the sky.
 */
val SurfaceWhite = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF1A1C1E)
val SurfaceVariantLight = Color(0xFFEDF0F4)
val OnSurfaceVariantLight = Color(0xFF42474E)
val OutlineLight = Color(0xFF6F757C)
val OutlineVariantLight = Color(0xFFCDD2D9)

/**
 * Very dark, but not black. Pure black would give maximum contrast on paper and be
 * worse in practice: white text on it blooms badly at night (halation), and on OLED
 * panels black-to-dark transitions smear as the map pans.
 */
val SurfaceDark = Color(0xFF1A1C1E)
/** Slightly off-white for the same reason — full-brightness white glares at night. */
val OnSurfaceDark = Color(0xFFE4E6EA)
val SurfaceVariantDark = Color(0xFF2A2D31)
val OnSurfaceVariantDark = Color(0xFFC2C7CE)
val OutlineDark = Color(0xFF8C9198)
val OutlineVariantDark = Color(0xFF43474C)

val BrandWhite = Color(0xFFFFFFFF)

// ─── Fixed fills ────────────────────────────────────────────────────────────

/**
 * Colours painted as a **fill** with their foreground derived by [onBrandColor],
 * rather than as a foreground on a themed surface.
 *
 * These stay the same in both themes on purpose. The turn banner's colour *is* the
 * message — blue is "in good time", orange "getting close", red "now" — and a
 * rider learns that mapping. Recolouring it by theme would break it. The map
 * markers likewise sit on map tiles, not on an app surface.
 */
val BannerBlue = Color(0xFF1565C0)
val BannerOrange = Color(0xFFE65100)
val BannerRed = Color(0xFFC62828)

/**
 * The route line, and the part of it already ridden.
 *
 * Both are fixed rather than theme-derived because the polyline is drawn *over* the
 * map tiles and is not affected by the dark theme's `INVERT_COLORS` filter — only
 * the tiles invert. One pair therefore has to read against near-white land in the
 * light theme and near-black in the dark one, which rules out anything at either
 * extreme of the lightness range.
 *
 * The travelled colour is deliberately duller and darker: what is left to ride is
 * the thing a rider needs to pick out at a glance, and the road behind them should
 * recede without disappearing.
 */
val RouteRemaining = Color(0xFFAA00FF)
val RouteTravelled = Color(0xFF6F6478)

/** Start / via / destination markers. Each is drawn with a white ring for separation. */
val MarkerStart = Color(0xFF1565C0)
val MarkerVia = Color(0xFFE65100)
val MarkerEnd = Color(0xFFC62828)
