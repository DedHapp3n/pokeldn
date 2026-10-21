# Find real BPGD function addresses from the confirmed gFontInfos entry FONT_NORMAL=0x08005369
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

# entry stride 0x38; dump FONT_NORMAL entry with a little context
start, end = 0x08005360, 0x08005369 + 0x38
print('dump 0x%08X..0x%08X' % (start, end))
for a in range(start, end, 4):
    v = w32(a)
    tag = ''
    if 0x08000000 <= v <= 0x081FFFFF:
        tag = '  <-- ROM PTR'
    print('0x%08X  0x%08X%s' % (a, v, tag))