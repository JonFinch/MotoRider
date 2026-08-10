#!/usr/bin/env python3
"""WCAG contrast audit for the MotoRider colour scheme.

Reads Color.kt for the palette and Theme.kt for the light/dark scheme role
assignments, then checks every foreground/background pair the UI actually puts
together.
"""
import re, sys, pathlib

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
color_src = (ROOT / 'app/src/main/java/com/motorider/ui/theme/Color.kt').read_text()
theme_src = (ROOT / 'app/src/main/java/com/motorider/ui/theme/Theme.kt').read_text()

PAL = {}
for name, hexv in re.findall(r'^val (\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)', color_src, re.M):
    PAL[name] = hexv[2:]  # drop alpha

def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

def lum(hexv):
    r, g, b = (int(hexv[i:i+2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)

def scheme(block_name):
    m = re.search(block_name + r'\s*=\s*\w+ColorScheme\((.*?)\n\)', theme_src, re.S)
    out = {}
    for role, val in re.findall(r'(\w+)\s*=\s*(\w+)', m.group(1)):
        out[role] = val
    return out

LIGHT = scheme('private val LightScheme')
DARK = scheme('private val DarkScheme')

# Foreground/background pairs Material puts together, plus this app's own usages.
# Text pairs must clear 4.5:1.
TEXT_PAIRS = [
    ('onPrimary', 'primary'),
    ('onSecondary', 'secondary'),          # button label on the orange fill
    ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondaryContainer', 'secondaryContainer'),
    ('onErrorContainer', 'errorContainer'),
    ('onSurface', 'surface'),
    ('onSurfaceVariant', 'surfaceVariant'),
    ('onSurfaceVariant', 'surface'),
    ('onBackground', 'background'),
    ('onError', 'error'),
    ('primary', 'surface'),                # tinted icons and text on cards
    ('error', 'surface'),
]

# Non-text pairs are UI components and graphics: WCAG 1.4.11 asks 3:1.
# `secondary` lives here on purpose — the brand orange is a fill colour only
# (slider track, Generate button, compass needle), never text or a glyph.
NONTEXT_PAIRS = [
    ('secondary', 'surface'),
    ('outline', 'surface'),
]

AA, NONTEXT, AAA = 4.5, 3.0, 7.0

def audit(label, sch, extra, pairs=None, floor=None, kind='text'):
    if pairs is None: pairs = TEXT_PAIRS
    if floor is None: floor = AA
    print(f'\n=== {label} ===')
    worst = []
    for fg, bg in pairs:
        if fg not in sch or bg not in sch:
            continue
        f, b = sch[fg], sch[bg]
        if f not in PAL or b not in PAL:
            continue
        r = ratio(PAL[f], PAL[b])
        flag = 'FAIL' if r < floor else ('ok  ' if r < AAA else 'AAA ')
        if r < floor:
            worst.append((r, fg, bg, f, b))
        print(f'  {flag} {r:5.2f}:1  {fg:22s} on {bg:20s}  ({f} on {b}) [{kind} min {floor}]')
    for fg, bg, note in extra:
        if fg not in PAL or bg not in PAL:
            continue
        r = ratio(PAL[fg], PAL[bg])
        flag = 'FAIL' if r < AA else ('aa  ' if r < AAA else 'AAA ')
        if r < AA:
            worst.append((r, fg, bg, fg, bg))
        print(f'  {flag} {r:5.2f}:1  {fg:22s} on {bg:20s}  ({note})')
    return worst

# Fixed colours the code paints directly, regardless of theme.
# Banner fills carry a foreground derived by onBrandColor (black or white,
# whichever wins), so they are checked against both.
def check_banner():
    print('\n=== BANNER FILLS (foreground chosen by onBrandColor) ===')
    bad = []
    for name in ('BannerBlue', 'BannerOrange', 'BannerRed', 'MarkerStart', 'MarkerVia', 'MarkerEnd'):
        if name not in PAL: continue
        # Exactly what onBrandColor does: compute both, take the winner.
        on_white = ratio(PAL[name], 'FFFFFF')
        on_black = ratio(PAL[name], '000000')
        pick, achieved = ('black', on_black) if on_black >= on_white else ('white', on_white)
        flag = 'FAIL' if achieved < AA else ('ok  ' if achieved < AAA else 'AAA ')
        if achieved < AA: bad.append((achieved, pick, name, name, name))
        print(f'  {flag} {achieved:5.2f}:1  {pick:22s} on {name:20s}  (onBrandColor)')
    return bad

# The route polyline is drawn over map tiles, not over an app surface, and is not
# touched by the dark theme's INVERT_COLORS filter — only the tiles invert. So one
# pair of colours has to work against typical OSM land in both themes. Judged at the
# 3:1 WCAG asks of a non-text graphic; a line is not text.
LIGHT_TILE = 'EFEDE8'
DARK_TILE = '100F14'   # the same land colour once inverted


def check_route_lines():
    print('\n=== ROUTE LINE over map tiles (min 3.0, non-text) ===')
    bad = []
    for name in ('RouteRemaining', 'RouteTravelled'):
        if name not in PAL:
            continue
        for tile_name, tile in (('light tiles', LIGHT_TILE), ('dark tiles', DARK_TILE)):
            r = ratio(PAL[name], tile)
            flag = 'FAIL' if r < NONTEXT else ('ok  ' if r < AAA else 'AAA ')
            if r < NONTEXT:
                bad.append((r, name, tile_name, name, tile))
            print(f'  {flag} {r:5.2f}:1  {name:22s} on {tile_name:20s} [graphic min {NONTEXT}]')
    return bad


LIGHT_EXTRA = []
DARK_EXTRA = []

bad = (audit('LIGHT text', LIGHT, LIGHT_EXTRA)
       + audit('LIGHT non-text', LIGHT, [], NONTEXT_PAIRS, NONTEXT, 'graphic')
       + audit('DARK text', DARK, DARK_EXTRA)
       + audit('DARK non-text', DARK, [], NONTEXT_PAIRS, NONTEXT, 'graphic')
       + check_banner()
       + check_route_lines())

print(f'\n{len(bad)} pair(s) below their minimum')
for r, fg, bg, f, b in sorted(bad):
    print(f'  {r:5.2f}:1  {fg} on {bg}')
sys.exit(1 if bad else 0)
