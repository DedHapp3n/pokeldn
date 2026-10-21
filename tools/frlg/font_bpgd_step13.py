# Step 13 (b): locate the REAL German DecompressGlyph_Normal via the verified FONT_NORMAL path.
# 1) literal-pool words holding verified addrs -> owning THUMB ldr sites.
# 2) disassemble enclosing functions, resolve bl targets + pc literals.
# 3) DecompressGlyphTile = call target shared with other DecompressGlyph_* funcs.
# 4) verify latin glyph base via decode with width table @0x082071AC.
import re, sys
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
SRC  = r'E:\VS\repos\pokeldn\build\pret-pokefirered\src\text.c'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()
out = open(r'E:\VS\repos\pokeldn\build\font_bpgd_step13.txt', 'w', encoding='utf-8')
def P(*a):
    print(*a); out.write((' '.join(str(x) for x in a) + '\n'))

def hw(a): return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')
def w32(a): return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')
def is_prologue(h): return (h & 0xF600) in (0xB400, 0xB500)
def is_return(h):   return (h & 0xFF00) == 0xBD00 or (h & 0xFF87) == 0x4700

def owning_ldr(pool_abs):
    res = []
    for L in range(pool_abs - 0x118, pool_abs, 2):
        if L < BASE: continue
        b = hw(L)
        if (b & 0xF800) != 0x4800: continue
        if ((L + 4) & ~3) + (b & 0xFF) * 4 == pool_abs:
            res.append((L, (b >> 8) & 7))
    return res

def func_start(at):
    a = at
    while a > BASE and not is_prologue(hw(a)):
        a -= 2
    return a

def bl_target(a):
    b1, b2 = hw(a), hw(a + 2)
    if (b1 & 0xF800) == 0xF000 and (b2 & 0xF800) == 0xF800:
        off = ((b1 & 0x7FF) << 12) | ((b2 & 0x7FF) << 1)
        if off & (1 << 22): off -= 1 << 23
        return (a + 4 + off) & 0xFFFFFFFF
    return None

def disasm_range(start, stop):
    lines, a = [], start
    while a < stop:
        b = hw(a)
        tgt = bl_target(a)
        if tgt is not None:
            lines.append('0x%08X bl   0x%08X%s' % (a, tgt, ' ;THUMB-BIT' if tgt & 1 else ''))
            a += 4; continue
        if (b & 0xF800) == 0x4800:
            rd, imm = (b >> 8) & 7, (b & 0xFF) * 4
            pool = ((a + 4) & ~3) + imm
            v = w32(pool)
            tag = ''
            if v in (0x082071AC, 0x0820F3AC, 0x081FAFAC, 0x081EA500): tag = ' ==VERIFIED=='
            elif 0x08200000 <= v < 0x08240000: tag = ' ;DATA'
            elif 0x08000000 <= v < 0x08200000: tag = ' ;CODE?'
            lines.append('0x%08X ldr  r%d,[pc,#%02X] -> %08X%s' % (a, rd, imm, v, tag))
        elif (b & 0xFF87) == 0x4700:
            lines.append('0x%08X bx   r%d' % (a, (b >> 8) & 7))
        elif (b & 0xFF80) in (0xB400, 0xB500):
            lines.append('0x%08X push {%02X}%s' % (a, b & 0x7F, ',lr' if b & 0x10 else ''))
        elif (b & 0xFF00) == 0xBD00:
            lines.append('0x%08X pop  {%02X}%s' % (a, b & 0x7F, ',pc' if b & 0x10 else ''))
        else:
            lines.append('0x%08X .iw  %04X' % (a, b))
        a += 2
    return lines

# ---- 1) reference map -------------------------------------------------
refs = {}
for name, val in (('LAT_W', 0x082071AC), ('JP_W', 0x0820F3AC),
                  ('NW1', 0x081FAFAC), ('GLYFW', 0x081EA500)):
    v = val.to_bytes(4, 'little')
    o = rom.find(v)
    sites = []
    while o >= 0:
        sites.append(o)
        if len(sites) > 200: break
        o = rom.find(v, o + 1)
    for s in sites:
        for L, rd in owning_ldr(s + BASE):
            refs.setdefault(L, []).append((name, s + BASE))
            P('ref %-4s val=%08X lit@%08X ldr@%08X r%d' % (name, val, s + BASE, L, rd))
P('total ldr sites: %d' % len(refs))

# ---- 2) enclosing functions -------------------------------------------
cands = set()
for L in refs:
    s = func_start(L)
    if 0x08004000 <= s < 0x08200000:
        cands.add(s)
P('candidate func starts: %s' % sorted(hex(c) for c in cands))

VER = ('082071AC', '0820F3AC', '081FAFAC', '081EA500')
best = None
for c in sorted(cands):
    end = c + 0x200
    a = c + 2
    while a < end and not (is_prologue(hw(a)) or is_return(hw(a))):
        a += 2
    end = min(a, c + 0x200)
    lines = disasm_range(c, end)
    vals = set(re.findall(r'ldr.*-> (\w+)', '\n'.join(lines)))
    targs = set(t for t in (bl_target(x) for x in range(c, end, 2)) if t)
    hit = sorted(v for v in vals if v in VER)
    score = sum(v in vals for v in VER) + len(targs)
    P('\n### cand 0x%08X end 0x%08X ver=%s bl=%s score=%d' % (
        c, end, hit, sorted('0x%08X' % t for t in targs), score))
    if score > (best[0] if best else 0):
        best = (score, c, end, lines)
        out.write('\n'.join(lines) + '\n')
if best:
    P('\nBEST func: 0x%08X..0x%08X' % (best[1], best[2]))
out.close()
print('\nwrote build\\font_bpgd_step13.txt')