# Step 10: verify the width tables referenced by GetGlyphWidth_Normal @ 0x08006678:
#  - Latin  = W2 (0x082071AC): must equal the known sFontNormalLatinGlyphWidths 512B seq.
#  - Japanese (0x0820F3AC): must be a plausible width table (values 0..16), distinct from Latin.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

# known Latin width sequence: "count=512" header, then one line of 512 hex bytes
txt = open(r'E:\VS\repos\pokeldn\build\widths_sFontNormalLatinGlyphWidths.txt', encoding='utf-8').read()
line = [l for l in txt.splitlines() if l and l != 'count=512'][0]
latin = bytes(int(t, 16) for t in line.split())
print('latin seq len', len(latin))

W2 = 0x082071AC
JP = 0x0820F3AC
l_rom = rom[W2-BASE:W2-BASE+len(latin)]
print('W2 == latin seq :', l_rom == latin)

jp = rom[JP-BASE:JP-BASE+64]
vals = list(jp)
print('JP first 64 values min/max:', min(vals), max(vals))
print('JP == latin first 64 :', jp == latin[:64])
print('JP distinct from W2  :', jp != rom[W2-BASE:W2-BASE+64])

# Also confirm the table base 0x081EA500 entry for FONT_NORMAL points to 0x08006679
base = 0x081EA500
print('sGlyphWidthFuncs[FONT_NORMAL].func = 0x%08X' % w32(base + 2*8 + 4))