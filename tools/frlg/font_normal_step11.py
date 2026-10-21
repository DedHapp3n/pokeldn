# Step 11: confirm 0x081EA500 is the sGlyphWidthFuncs actually referenced by code.
# Search the ROM for the 4-byte little-endian value of each candidate table base and
# report which ones appear (a real table base will be loaded via a literal pool).
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

CANDS = [0x081EA500, 0x0846BE5C, 0x08477420]
for c in CANDS:
    val = c.to_bytes(4, 'little')
    hits = []
    o = rom.find(val)
    while o >= 0 and len(hits) < 30:
        hits.append(o)
        o = rom.find(val, o + 1)
    print('table base %08X : %d occurrence(s) at %s' % (
        c, len(hits), [hex(h + BASE) for h in hits[:12]]))