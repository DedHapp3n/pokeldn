# Dump the pointer-table region around the width-address occurrences.
ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
rom = open(ROMF, 'rb').read()

def w32(a):
    return int.from_bytes(rom[a-BASE:a-BASE+4], 'little')

for a in range(0x080064F0, 0x08006A60, 4):
    v = w32(a)
    tag = ''
    if 0x08000000 <= v < 0x082FFFFF:
        tag = ' <ROM>'
    print('0x%08X  %08X%s' % (a, v, tag))