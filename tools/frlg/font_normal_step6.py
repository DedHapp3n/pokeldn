# Concrete cross-reference: which width table does BPGD's GetGlyphWidth_Normal reference?
# We have 4 identical width tables and 4 GetGlyphWidth-family functions in BPGD code.
# Map each function -> width, then match the BPGD GetGlyphWidth_Normal by code-shape vs upstream.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')
def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

# The 4 width candidate values
WIDTHS = [0x081FAFAC, 0x082071AC, 0x082174C4, 0x082277DC]
wvals = [c.to_bytes(4,'little') for c in WIDTHS]

# Find every literal-pool 4-byte word holding a width value, and the ldr that owns it.
# An ldr rX,[pc,#imm] at L owns pool ((L+4)&~3)+imm. We search backwards from each pool word.
def owners_of(pool_abs):
    out = []
    for L in range(pool_abs-0x118, pool_abs, 2):
        if L < BASE: continue
        b = hw(L)
        if (b & 0xF800) != 0x4800: continue
        imm = (b & 0xFF)*4
        if ((L+4) & ~3) + imm == pool_abs:
            out.append((L, (b>>8)&7))
    return out

found = []
for c, bval in zip(WIDTHS, wvals):
    o = rom.find(bval)
    while o >= 0:
        absa = o + BASE
        for L, rd in owners_of(absa):
            found.append((absa, L, rd, c))
        o = rom.find(bval, o+1)

print('width literal -> owning ldr:')
for absa, L, rd, c in found:
    print('  pool=%08X ldr@%08X r%d width=%08X' % (absa, L, rd, c))
open(r'E:\VS\repos\pokeldn\build\font_width_refs.txt','w').write(
    '\n'.join('pool=%08X ldr@%08X r%d width=%08X' % x for x in found)+'\n')