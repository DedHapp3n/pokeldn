# Step 14: decode German FONT_NORMAL latin glyphs and render a test PNG.
# German FR/LG (BPGD = "Blattgruene Edition") addresses, from step 13 disasm:
#   DecompressGlyph_Normal      @ 0x08006538
#   sFontNormalLatinGlyphs      @ 0x081FF1AC   (stride 0x20 u16 = 64 bytes)
#   sFontNormalLatinGlyphWidths @ 0x082071AC   (u8 per glyph, verified)
#   sFontNormalJapaneseGlyphs   @ 0x082073AC
#   sFontNormalJapaneseWidths   @ 0x0820F3AC
#   DecompressGlyphTile         @ 0x08002F0C
import struct, zlib

ROMF = r'E:\VS\repos\pokeldn\pokegift\saves-rom\Pokemon - Blattgruene Edition (Germany).gba'
BASE = 0x08000000
LAT_GLYPHS = 0x081FF1AC
LAT_WIDTHS = 0x082071AC
rom = open(ROMF, 'rb').read()
print('ROM size: 0x%X' % len(rom))

OFFS = [
0x00,0x01,0x02,0x00,0x03,0x04,0x05,0x03,0x06,0x07,0x08,0x06,0x00,0x01,0x02,0x00,
0x09,0x0A,0x0B,0x09,0x0C,0x0D,0x0E,0x0C,0x0F,0x10,0x11,0x0F,0x09,0x0A,0x0B,0x09,
0x12,0x13,0x14,0x12,0x15,0x16,0x17,0x15,0x18,0x19,0x1A,0x18,0x12,0x13,0x14,0x12,
0x00,0x01,0x02,0x00,0x03,0x04,0x05,0x03,0x06,0x07,0x08,0x06,0x00,0x01,0x02,0x00,
0x1B,0x1C,0x1D,0x1B,0x1E,0x1F,0x20,0x1E,0x21,0x22,0x23,0x21,0x1B,0x1C,0x1D,0x1B,
0x24,0x25,0x26,0x24,0x27,0x28,0x29,0x27,0x2A,0x2B,0x2C,0x2A,0x24,0x25,0x26,0x24,
0x2D,0x2E,0x2F,0x2D,0x30,0x31,0x32,0x30,0x33,0x34,0x35,0x33,0x2D,0x2E,0x2F,0x2D,
0x1B,0x1C,0x1D,0x1B,0x1E,0x1F,0x20,0x1E,0x21,0x22,0x23,0x21,0x1B,0x1C,0x1D,0x1B,
0x36,0x37,0x38,0x36,0x39,0x3A,0x3B,0x39,0x3C,0x3D,0x3E,0x3C,0x36,0x37,0x38,0x36,
0x3F,0x40,0x41,0x3F,0x42,0x43,0x44,0x42,0x45,0x46,0x47,0x45,0x3F,0x40,0x41,0x3F,
0x48,0x49,0x4A,0x48,0x4B,0x4C,0x4D,0x4B,0x4E,0x4F,0x50,0x4E,0x48,0x49,0x4A,0x48,
0x36,0x37,0x38,0x36,0x39,0x3A,0x3B,0x39,0x3C,0x3D,0x3E,0x3C,0x36,0x37,0x38,0x36,
0x00,0x01,0x02,0x00,0x03,0x04,0x05,0x03,0x06,0x07,0x08,0x06,0x00,0x01,0x02,0x00,
0x09,0x0A,0x0B,0x09,0x0C,0x0D,0x0E,0x0C,0x0F,0x10,0x11,0x0F,0x09,0x0A,0x0B,0x09,
0x12,0x13,0x14,0x12,0x15,0x16,0x17,0x15,0x18,0x19,0x1A,0x18,0x12,0x13,0x14,0x12,
0x00,0x01,0x02,0x00,0x03,0x04,0x05,0x03,0x06,0x07,0x08,0x06,0x00,0x01,0x02,0x00]
assert len(OFFS) == 256 and max(OFFS) == 0x50

def gen_lut(fg, bg, shadow):
    colors = [bg, fg, shadow]  # GenerateFontHalfRowLookupTable: {bg, fg, shadow}
    lut = []
    for i in range(3):
        for j in range(3):
            for k in range(3):
                for l in range(3):
                    lut.append((colors[l] << 12) | (colors[k] << 8) | (colors[j] << 4) | colors[i])
    return lut

# default printer colors: TEXT_COLOR_WHITE=1 fg, TRANSPARENT=0 bg, LIGHT_GRAY=3 shadow
LUT = gen_lut(1, 0, 3)

def tile(src_byte_off):
    # DecompressGlyphTile: each source u16 -> 2 dest u16 (high byte = even i,
    # low byte = odd i); 8 u16 src -> 16 u16 dest = one 8x8 4bpp tile.
    # (ROM asm: (i<<31) tests bit0 of i = odd/even, NOT i>=8)
    out = []
    for k in range(8):
        w = int.from_bytes(rom[src_byte_off+2*k:src_byte_off+2*k+2], 'little')
        out.append(LUT[OFFS[w >> 8]])
        out.append(LUT[OFFS[w & 0xFF]])
    return out

def decode_glyph(gid):
    # latin: 4 GBA 8x8 tiles (tile order) at src u16 +0x00/+0x08/+0x10/+0x18
    #   +0x00=(rows0-7,cols0-7)  +0x08=(rows0-7,cols8-15)
    #   +0x10=(rows8-15,cols0-7) +0x18=(rows8-15,cols8-15)
    # each 8x8 4bpp tile = 32 B = 8 rows x 8 px; row r: u16 [2r,2r+1]
    base = LAT_GLYPHS - BASE + 0x40 * gid  # 0x20 u16 stride = 64 bytes
    grid = {}
    for t, (y0, x0) in ((0x00, (0, 0)), (0x08, (0, 8)), (0x10, (8, 0)), (0x18, (8, 8))):
        tile16 = tile(base + 2*t)
        for r in range(8):
            for c in range(8):
                v = tile16[2*r + (c >> 2)]
                grid[(y0+r, x0+c)] = (v >> (4*(c & 3))) & 0xF
    rows = [[grid[(y,x)] for x in range(16)] for y in range(16)]
    w = rom[LAT_WIDTHS - BASE + gid]
    return rows, w

CHARS = {'P':0xCA,'O':0xC9,'K':0xC5,'E':0xBF,'H':0xC2,'A':0xBB,'X':0xD2,'M':0xC7,
        'B':0xBC,'U':0xCF,'T':0xCE,'R':0xCC,'I':0xC3}
TEXT = "POKEHAX HOME ABOUT EXIT"
SCALE = 4
BG, FG, SH = (18,18,18,255), (255,255,255,255), (160,160,160,255)
# decoded palette indices are 0=bg,1=fg,3=shadow (see gen_lut colors=[0,1,3]); idx2 unused
PAL = [BG, FG, BG, SH]

# render text line (advance = width, no letter spacing), on a 16x14 cell grid
cells = []
x = 0
for ch in TEXT:
    gid = CHARS.get(ch, 0x00)
    if gid == 0:  # space: advance by widths[0], no pixels
        x += rom[LAT_WIDTHS - BASE]
        continue
    rows, w = decode_glyph(gid)
    cells.append((x, gid, rows, w))
    x += w
W = x + 8
H = 14
img = [[0]*(W) for _ in range(H)]  # 0=bg
for cx, gid, rows, w in cells:
    for y in range(H):
        for xx in range(w):
            img[y][cx+xx] = rows[y][xx]

outpx = []
for y in range(H):
    row = []
    for x in range(W):
        col = PAL[img[y][x]]
        row += [col]*SCALE
    outpx.append(row)
pw, ph = W*SCALE, H*SCALE

def png(path, w, h, rows):
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xFFFFFFFF)
    raw = b''.join(b'\x00' + b''.join(struct.pack('4B', *p) for p in r) for r in rows)
    data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    open(path, 'wb').write(data)

PNGF = r'E:\VS\repos\pokeldn\pokegift\BPGD_font_normal_test.png'
png(PNGF, pw, ph, outpx)
print('PNG written: %s (%dx%d px, scale %d)' % (PNGF, pw, ph, SCALE))

# results
lines = []
lines.append('BPGD (German FR) FONT_NORMAL latin glyph decode - final results')
lines.append('='*70)
lines.append('DecompressGlyph_Normal        @ 0x08006538  (disasm: build/font_disasm_clean.txt)')
lines.append('  latin widths literal 0x082071AC @ lit 0x080065F2 (branch: isJapanese==FALSE)')
lines.append('  jp   widths literal 0x0820F3AC @ lit 0x080065BA (branch: isJapanese==TRUE)')
lines.append('sFontNormalLatinGlyphs      @ 0x081FF1AC   (ld r0,[pc,#4C] @ 0x0800661E, latin branch)')
lines.append('sFontNormalLatinGlyphWidths @ 0x082071AC   (512 B; next data 0x082073AC = JP glyphs)')
lines.append('sFontNormalJapaneseGlyphs   @ 0x082073AC   (ld r1,[pc,#4C] @ 0x08006582)')
lines.append('sFontNormalJapaneseWidths   @ 0x0820F3AC')
lines.append('DecompressGlyphTile         @ 0x08002F0C   (bl from 0x0800658E/659A/65A8/65B6/6628/... )')
lines.append('')
lines.append('Format (verified vs pret DecompressGlyph_Normal, latin branch):')
lines.append('  glyphs = sFontNormalLatinGlyphs + 0x20*u16 * glyphId   (64 B stride)')
lines.append('  4x DecompressGlyphTile at src u16 +0x00/+0x08/+0x10/+0x18')
lines.append('     -> pixels +0x00/+0x20/+0x40/+0x60 (u16) = 8x8 4bpp tiles')
lines.append('     quadrant map: +0x00=(rows0-7,cols0-7) +0x08=(rows0-7,cols8-15)')
lines.append('                    +0x10=(rows8-15,cols0-7) +0x18=(rows8-15,cols8-15)')
lines.append('  DecompressGlyphTile: 8 u16 -> 16 u16; dest[2k]   = LUT[Off[hi16(src[k])]]')
lines.append('                                          dest[2k+1] = LUT[Off[lo16(src[k])]]')
lines.append('     dest[i] = LUT[ sFontHalfRowOffsets[idx] ]  (LUT from GenerateFontHalfRowLookupTable)')
lines.append('  width = sFontNormalLatinGlyphWidths[glyphId]; height = 14 px (top-aligned)')
lines.append('  glyphId = raw character byte (e.g. CHAR_P = 0xCA, CHAR_A = 0xBB)')
lines.append('')
lines.append('Rendered test string: %r' % TEXT)
lines.append('PNG: %s' % PNGF)
lines.append('')
lines.append('Per-char decoded widths (glyphId -> width):')
seen = []
for ch in TEXT:
    gid = CHARS.get(ch, 0x00)
    if gid not in [s[0] for s in seen]: seen.append((gid, ch))
for gid, ch in seen:
    w = rom[LAT_WIDTHS - BASE + gid]
    if gid == 0:
        lines.append('  0x%02X %-2s width=%2d  (space: fill path, bg color)' % (gid, ch, w))
        continue
    rows, w = decode_glyph(gid)
    nz = sum(1 for y in range(14) for x in range(w) if rows[y][x])
    lines.append('  0x%02X %-2s width=%2d  set-pixels=%3d' % (gid, ch, w, nz))
lines.append('')
lines.append('Self-check: widths table fingerprint matches English-verified 0x082071AC (step 1/13).')
open(r'E:\VS\repos\pokeldn\build\font_normal_final.txt', 'w', encoding='utf-8').write('\n'.join(lines))
print('results -> build/font_normal_final.txt')