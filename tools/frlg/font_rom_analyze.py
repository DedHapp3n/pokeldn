"""Step A: locate the normal-latin font data in the BPGD ROM.

1. Parse the four sFont*LatinGlyphWidths arrays from the source (text.c).
2. Find each 512-byte sequence in the ROM (fingerprint match).
3. Find the sFontNormalLatinGlyphs base: scan 16KB windows near the found
   width arrays; a window is the glyph base iff decoding all 512 glyphs
   with the width table stays self-consistent (widths plausible 0..16 and
   all four 16-u16 halves decode without error, plus most glyphs non-empty).
"""
import re

SRC = r'E:\VS\repos\pokeldn\build\pret-pokefirered\src\text.c'
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000

txt = open(SRC, encoding='utf-8', errors='ignore').read()
rom = open(ROMF, 'rb').read()

def arr(name):
    i = txt.find('static const u8 %s[]' % name)
    j = txt.find('{', i)
    k = txt.find('};', j)
    body = txt[j + 1:k]
    # cut comment lines
    lines = [l for l in body.splitlines() if not l.strip().startswith('//')]
    body = '\n'.join(lines)
    return bytes(int(x, 0) for x in re.findall(r'0[xX][0-9A-Fa-f]+|\d+', body))

widths = {}
for name in ('sFontNormalLatinGlyphWidths', 'sFontNormalCopy1LatinGlyphWidths',
             'sFontSmallLatinGlyphWidths'):
    w = arr(name)
    hits = []
    i = rom.find(w)
    while i >= 0 and len(hits) < 4:
        hits.append(i)
        i = rom.find(w, i + 1)
    widths[name] = hits
    print(name, 'len', len(w), 'rom hits (address):', [hex(h + BASE) for h in hits])

# sanity: widths values plausible
wn = arr('sFontNormalLatinGlyphWidths')
print('width range normal:', min(wn), max(wn), 'glyph count:', len(wn))