# Step 7: dump the THUMB code region that owns the width-literal pools (step 6), with
# function-boundary annotations, to identify the 4 GetGlyphWidth-family functions and
# which width each references.
import sys
sys.path.insert(0, r'E:\VS\repos\pokeldn\pokeldn')
from frlg.rom import thumb

ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')
def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

WIDTHS = {0x081FAFAC: 'W1', 0x082071AC: 'W2', 0x082174C4: 'W3', 0x082277DC: 'W4'}

START, STOP = 0x08006420, 0x08006A80
lines = []
for a in range(START, STOP, 2):
    h = hw(a)
    tag = ''
    if thumb.is_prologue(h):
        tag = '  ; <== PROLOGUE (func start?)'
    elif thumb.is_return(h):
        tag = '  ; <== return'
    # ldr rX,[pc,#imm]
    if (h & thumb.LDR_PC_MASK) == thumb.LDR_PC:
        pool = ((a + 4) & ~3) + (h & 0xFF) * 4
        pv = w32(pool)
        wtag = WIDTHS.get(pv, '')
        tag = '  ; ldr r%d [pc,#%d] -> %08X %s' % ((h >> 8) & 7, (h & 0xFF) * 4, pv, wtag)
    lines.append('0x%08X  %04X%s' % (a, h, tag))

out = '\n'.join(lines)
open(r'E:\VS\repos\pokeldn\build\font_width_funcs_dump.txt', 'w', encoding='utf-8').write(out + '\n')
# console: only annotated lines
print('\n'.join(l for l in lines if ';' in l))