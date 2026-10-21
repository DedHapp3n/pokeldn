# Find which code references the four identical sFontNormalLatinGlyphWidths candidates.
# Strategy: for each candidate value C, find every 4-byte little-endian occurrence of C in the
# ROM; keep occurrences that are a valid literal pool of a nearby THUMB ldr rX,[pc,#imm].
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

KNOWN = [0x081FAFAC, 0x082071AC, 0x082174C4, 0x082277DC]

def hw(a):
    return int.from_bytes(rom[a-BASE:a-BASE+2], 'little')

def find_pool_owners(o):
    # o = absolute ROM offset (file addr of a 4-byte literal). Find ldr instructions L with
    # pool = ((L+4) & ~3) + imm == o, imm = (hw(L) & 0xFF)*4, 0 <= imm < 0x100.
    owners = []
    for L in range(o - 0x114, o, 2):
        if L < BASE or L + 1 >= len(rom) + BASE:
            continue
        b = hw(L)
        if (b & 0xF800) != 0x4800:
            continue
        imm = (b & 0xFF) * 4
        pool = ((L + 4) & ~3) + imm
        if pool == o:
            owners.append((L, (b >> 8) & 7))
    return owners

results = []
for C in KNOWN:
    val = C.to_bytes(4, 'little')
    hits = []
    o = rom.find(val)
    while o >= 0 and len(hits) < 64:
        hits.append(o)
        o = rom.find(val, o + 1)
    for o in hits:
        owners = find_pool_owners(o)
        if owners:
            results.append((C, o, owners))
            print('CAND %08X  literal at %08X (file off %06X, align %d) owners: %s'
                  % (C, o + BASE, o, o % 4, ['%08X r%d' % (l + BASE, r) for l, r in owners]))

if not results:
    print('NO pool-owner hits found for any candidate')
open(r'E:\VS\repos\pokeldn\build\font_refs.txt', 'w').write(
    '\n'.join('%08X lit %08X owners %s' % (c, o + BASE, [hex(l + BASE) for l, r in ow])
              for c, o, ow in results) + '\n')