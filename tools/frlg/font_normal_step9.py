# Step 9: disassemble the 6 GetGlyphWidth functions found in step 8 (table @ 0x081EA500).
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')
def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

WIDTHS = {0x081FAFAC: 'W1', 0x082071AC: 'W2', 0x082174C4: 'W3', 0x082277DC: 'W4'}

FUNCS = [
    ('Small',       0x08006450),
    ('NormalCopy1', 0x08006518),
    ('Normal',      0x08006678),
    ('NormalCopy2', 0x0800674C),
    ('Male',        0x080068AC),
    ('Female',      0x08006A18),
]

def disasm(start, n):
    lines = []
    a = start
    for _ in range(n // 2):
        b = hw(a)
        if (b & 0xF800) == 0xF000 and a+2 < start+n and (hw(a+2) & 0xF800) == 0xF800:
            off = ((b & 0x7FF) << 12) | ((hw(a+2) & 0x7FF) << 1)
            if off & (1 << 22): off -= 1 << 23
            lines.append('0x%08X bl   0x%08X' % (a, (a+4+off) & 0xFFFFFFFF)); a += 4; continue
        if (b & 0xF800) == 0x4800:
            rd = (b >> 8) & 7; imm = (b & 0xFF) * 4
            pool = ((a + 4) & ~3) + imm
            val = w32(pool)
            lines.append('0x%08X ldr  r%d,[pc,#%02X] -> %08X %s' % (a, rd, imm, val, WIDTHS.get(val,''))); a += 2; continue
        if (b & 0xFF87) == 0x4700:
            lines.append('0x%08X bx   r%d' % (a, (b>>8)&7))
        elif (b & 0xFF80) in (0xB400, 0xB500):
            lines.append('0x%08X push {%02X}%s' % (a, b & 0x7F, ',lr' if b & 0x10 else ''))
        elif (b & 0xFF00) == 0xBD00:
            lines.append('0x%08X pop  {%02X}%s' % (a, b & 0x7F, ',pc' if b & 0x10 else ''))
        elif (b & 0xFF00) == 0xE300:
            lines.append('0x%08X mov  r%d, r%d' % (a, b & 0xF, (b>>8)&0xF))
        elif (b & 0xFF00) == 0x2000:
            lines.append('0x%08X movs r%d, #%X' % (a, b & 0xF, (b>>8)&0xFF))
        else:
            lines.append('0x%08X .iw  %04X' % (a, b))
        a += 2
    return '\n'.join(lines)

out = []
for name, start in FUNCS:
    out.append('=== %s @ 0x%08X ===' % (name, start))
    out.append(disasm(start, 0x30))
    out.append('')
res = '\n'.join(out)
open(r'E:\VS\repos\pokeldn\build\font_gwfuncs_disasm.txt', 'w', encoding='utf-8').write(res + '\n')
# console: header + ldr/push/bx/bl lines only
keep = []
for l in res.splitlines():
    if l.startswith('===') or ('.iw' not in l):
        keep.append(l)
print('\n'.join(keep))