# Step 8: locate the sGlyphWidthFuncs table in the BPGD (German) ROM.
# Layout: 7 x { u32 fontId; u32 funcPtr; }, fontId sequence 0,1,2,3,4,5,6.
# FONT_NORMAL = index 2 -> its funcPtr is the German GetGlyphWidth_Normal.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')
def is_rom_ptr(v):
    return 0x08000000 <= v <= 0x081FFFFF

FONTS = [0, 1, 2, 3, 4, 5, 6]
NAMES = ['Small', 'NormalCopy1', 'Normal', 'NormalCopy2', 'Male', 'Female', 'Braille']

def table_at(h):
    # h = base of the 7-entry table
    vals = []
    for i in range(7):
        if w32(h + 8*i) != FONTS[i]:
            return None
        f = w32(h + 8*i + 4)
        if not is_rom_ptr(f):
            return None
        vals.append(f)
    return vals

# anchor on fontId==2 (FONT_NORMAL) bytes, validate the table around it
hits = []
o = rom.find(b'\x02\x00\x00\x00')
while o >= 0:
    p = o + BASE          # p = base of the FONT_NORMAL entry (fontId at p, func at p+4)
    base = p - 16         # table base = FONT_NORMAL entry - 2*8
    if base >= BASE:
        v = table_at(base)
        if v:
            hits.append((base, v))
    o = rom.find(b'\x02\x00\x00\x00', o + 1)

print('candidate table bases:', [hex(h) for h, _ in hits])
for h, v in hits:
    print('--- table @ %08X' % h)
    for i in range(7):
        print('  %-12s fontId=%d func=0x%08X' % (NAMES[i], FONTS[i], v[i]))

open(r'E:\VS\repos\pokeldn\build\font_gwfunc_table.txt', 'w').write(
    '\n'.join('table @ %08X: %s' % (h,
        ' '.join('%s=0x%08X' % (NAMES[i], v[i]) for i in range(7)))
    for h, v in hits) + '\n')