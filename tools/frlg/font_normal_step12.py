# Step 12: determine which of the 3 sGlyphWidthFuncs-like tables is the real dispatcher.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')
def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

WIDTHS = {0x081FAFAC: 'W1', 0x082071AC: 'W2', 0x082174C4: 'W3', 0x082277DC: 'W4',
          0x081EEDAC: 'SMALL', 0x0820F3AC: 'JP_N', 0x0821F6C4: 'JP_M', 0x0822F9DC: 'JP_F'}

def is_prologue(a):
    b = hw(a)
    return (b & 0xF600) == 0xB400 or (b & 0xF600) == 0xB500

def func_start(at):
    # scan backward (aligned) for a prologue
    a = at
    while a > BASE and not is_prologue(a):
        a -= 2
    return a

def disasm(start, n):
    lines = []
    a = start
    for _ in range(n // 2):
        b = hw(a)
        if (b & 0xF800) == 0x4800:
            rd = (b >> 8) & 7; imm = (b & 0xFF) * 4
            pool = ((a + 4) & ~3) + imm
            val = w32(pool)
            lines.append('0x%08X ldr  r%d,[pc,#%02X] -> %08X %s' % (a, rd, imm, val, WIDTHS.get(val, '')))
        elif (b & 0xFF80) in (0xB400, 0xB500):
            lines.append('0x%08X push {%02X}%s' % (a, b & 0x7F, ',lr' if b & 0x10 else ''))
        elif (b & 0xFF00) == 0xBD00:
            lines.append('0x%08X pop  {%02X}%s' % (a, b & 0x7F, ',pc' if b & 0x10 else ''))
        elif (b & 0xFF87) == 0x4700:
            lines.append('0x%08X bx   r%d' % (a, (b & 0x78) >> 3))
        elif (b & 0xFF00) == 0xE300:
            lines.append('0x%08X mov  r%d, r%d' % (a, b & 0xF, (b >> 8) & 0xF))
        elif (b & 0xFF00) == 0xA000:
            lines.append('0x%08X add  r%d, r%d, #%d' % (a, b & 0xF, (b >> 8) & 0xF, (b >> 4) & 0xF))
        else:
            lines.append('0x%08X .iw  %04X' % (a, b))
        a += 2
    return lines

out = []
# 1) candidate 1 dispatcher: find function start containing 0x08005E3C
s1 = func_start(0x08005E3C)
out.append('=== candidate1 dispatcher func start %08X (refs 0x081EA500) ===' % s1)
out += disasm(s1, 0x40)
out.append('')
# 2) candidate 2 Normal func @ 0x08149CAD -> start 0x08149CAC
out.append('=== candidate2 Normal func 0x08149CAC ===')
out += disasm(0x08149CAC, 0x28)
out.append('')
# 3) candidate 3 Normal func @ 0x08154C29 -> start 0x08154C28
out.append('=== candidate3 Normal func 0x08154C28 ===')
out += disasm(0x08154C28, 0x28)
res = '\n'.join(out)
open(r'E:\VS\repos\pokeldn\build\font_gw3tables.txt', 'w', encoding='utf-8').write(res + '\n')
keep = [l for l in res.splitlines() if l.startswith('===') or '.iw' not in l]
print('\n'.join(keep))