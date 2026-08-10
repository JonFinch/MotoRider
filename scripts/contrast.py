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
ROLE_PAIRS = [
    ('onPrimary', 'primary'),
    ('onSecondary', 'secondary'),
    ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondaryContainer', 'secondaryContainer'),
    ('onSurface', 'surface'),
    ('onSurfaceVariant', 'surfaceVariant'),
    ('onSurfaceVariant', 'surface'),   # secondary text on plain cards
    ('onBackground', 'background'),
    ('onError', 'error'),
    ('outline', 'surface'),
    ('primary', 'surface'),            # tinted icons, links, progress on cards
    ('secondary', 'surface'),
    ('error', 'surface'),
]

AA, AA_LARGE, AAA = 4.5, 3.0, 7.0

def audit(label, sch, extra):
    print(f'\n=== {label} ===')
    worst = []
    for fg, bg in ROLE_PAIRS:
        if fg not in sch or bg not in sch:
            continue
        f, b = sch[fg], sch[bg]
        if f not in PAL or b not in PAL:
            continue
        r = ratio(PAL[f], PAL[b])
        flag = 'FAIL' if r < AA else ('aa  ' if r < AAA else 'AAA ')
        if r < AA:
            worst.append((r, fg, bg, f, b))
        print(f'  {flag} {r:5.2f}:1  {fg:22s} on {bg:20s}  ({f} on {b})')
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
        best = max(ratio(PAL[name], 'FFFFFF'), ratio(PAL[name], '000000'))
        pick = 'white' if ratio(PAL[name], 'FFFFFF') >= ratio(PAL[name], '000000') else 'black'
        flag = 'FAIL' if best < AA else ('aa  ' if best < AAA else 'AAA ')
        if best < AA: bad.append((best, pick, name, name, name))
        print(f'  {flag} {best:5.2f}:1  {pick:22s} on {name:20s}  (auto-picked)')
    return bad

LIGHT_EXTRA = []
DARK_EXTRA = []

bad = audit('LIGHT', LIGHT, LIGHT_EXTRA) + audit('DARK', DARK, DARK_EXTRA) + check_banner()

print(f'\n{len(bad)} pair(s) below AA 4.5:1')
for r, fg, bg, f, b in sorted(bad):
    print(f'  {r:5.2f}:1  {fg} on {bg}')
sys.exit(1 if bad else 0)
