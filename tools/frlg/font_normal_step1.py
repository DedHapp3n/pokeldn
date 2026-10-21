# Dump raw bytes + proper THUMB decode for the two functions.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

DGN, GWN, END = 0x08009F38, 0x0800A078, 0x0800A0A4

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')

def disasm(start, stop, label):
    lines = []
    a = start
    lines.append('=== %s 0x%08X..0x%08X ===' % (label, start, stop))
    while a < stop:
        b = hw(a)
        if (b & 0xF800) == 0xF000 and a+2 < stop and (hw(a+2) & 0xF800) == 0xF800:
            off = ((b & 0x7FF) << 12) | ((hw(a+2) & 0x7FF) << 1)
            if off & (1 << 22): off -= 1 << 23
            tgt = (a + 4 + off) & 0xFFFFFFFF
            lines.append('0x%08X bl   0x%08X' % (a, tgt)); a += 4; continue
        if (b & 0xF800) == 0x4800:
            rd = (b >> 8) & 7; imm = (b & 0xFF) * 4
            pool = ((a + 4) & ~3) + imm
            try:
                val = int.from_bytes(rom[pool-BASE:pool-BASE+4], 'little')
            except Exception:
                val = None
            lines.append('0x%08X ldr  r%d, [pc,#%02X]  pool=0x%08X val=0x%08X'
                         % (a, rd, imm, pool, val if val is not None else -1)); a += 2; continue
        if (b & 0xFF87) == 0x4700:
            lines.append('0x%08X bx   r%d' % (a, (b>>8)&7))
        elif (b & 0xFF80) == 0xB400 or (b & 0xFF80) == 0xB500:
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

KNOWN = [0x081FAFAC, 0x082071AC, 0x082174C4, 0x082277DC]
out = disasm(DGN, GWN, 'DecompressGlyph_Normal')
out += '\n\n' + disasm(GWN, END, 'GetGlyphWidth_Normal')
out += '\n\nKNOWN WIDTHS: ' + ', '.join(hex(x) for x in KNOWN) + '\n'
open(r'E:\VS\repos\pokeldn\build\font_disasm_clean.txt', 'w').write(out)
print(out)