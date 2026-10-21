# Verify the four width candidates against the BPGD ROM; list all raw occurrences of the
# 512-byte width sequence and of the 4-byte address values.
import re
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def seq_from(fn):
    txt = open(fn, encoding='utf-8').read()
    i = txt.find('count=')
    body = txt[txt.find('\n', i) + 1:]
    return bytes(int(x, 16) for x in re.findall(r'[0-9A-F]{2}', body))

w = seq_from(r'E:\VS\repos\pokeldn\build\widths_sFontNormalLatinGlyphWidths.txt')
print('width seq len', len(w))
KNOWN = [0x081FAFAC, 0x082071AC, 0x082174C4, 0x082277DC]
for C in KNOWN:
    at = rom[C-BASE:C-BASE+512]
    print('%08X: 512B match=%s first8=%s' % (C, at == w, at[:8].hex()))

hits = []
o = rom.find(w)
while o >= 0:
    hits.append(o)
    o = rom.find(w, o + 1)
    if len(hits) > 20:
        break
print('data hits:', [hex(h + BASE) for h in hits])

for C in KNOWN:
    val = C.to_bytes(4, 'little')
    h2 = []
    o = rom.find(val)
    while o >= 0 and len(h2) < 40:
        h2.append(o)
        o = rom.find(val, o + 1)
    print('addr-value %08X occurrences: %s' % (C, [hex(h + BASE) for h in h2]))