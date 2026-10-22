#!/usr/bin/env python3
"""Offline FNV-1a analysis for the BPGD FONT hardware-fingerprint patch.

Computes, for every region the read-only buffer_code will hash on the console,
the expected FNV-1a 32-bit value over the local German BPGD ROM, and determines
the exact DecompressGlyphTile function length with the project's thumb reader.
"""

import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from pokeldn.frlg.rom import thumb  # noqa: E402

ROMF = ROOT / "pokegift" / "saves-rom" / "Pokemon - Blattgruene Edition (Germany).gba"
BASE = 0x08000000
rom = ROMF.read_bytes()
print("ROM file size: 0x%08X / %d" % (len(rom), len(rom)))
print("ROM SHA256:    %s" % __import__("hashlib").sha256(rom).hexdigest())


def fnv1a(data):
    h = 0x811C9DC5
    for byte in data:
        h ^= byte
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h


def region(name, addr, length):
    off = addr - BASE
    assert 0 <= off and off + length <= len(rom), (name, hex(addr), hex(length))
    data = rom[off:off + length]
    print("%-28s 0x%08X len 0x%06X fnv1a 0x%08X" % (name, addr, length, fnv1a(data)))
    return data


# Bit 5 needs the exact DecompressGlyphTile length, measured offline first.
tile_start = 0x08002F0C
tile_end = thumb.function_end(rom, BASE, tile_start, tile_start + 0x400)
print()
print("DecompressGlyphTile 0x%08X .. 0x%08X  length 0x%04X (%d)"
      % (tile_start, tile_end, tile_end - tile_start, tile_end - tile_start))
print("  last function bytes:")
off = tile_end - BASE
for at in range(off - 8, off, 2):
    hw = int.from_bytes(rom[at:at + 2], "little")
    tag = " return" if thumb.is_return(hw) else (" prologue" if thumb.is_prologue(hw) else "")
    print("    0x%08X 0x%04X%s" % (BASE + at, hw, tag))
print("  bytes after the end (next function):")
for at in range(off, off + 8, 2):
    hw = int.from_bytes(rom[at:at + 2], "little")
    tag = " return" if thumb.is_return(hw) else (" prologue" if thumb.is_prologue(hw) else "")
    print("    0x%08X 0x%04X%s" % (BASE + at, hw, tag))

# Cross-check the DecompressGlyph_Normal region against GetGlyphWidth_Normal.
gl_start = 0x08006538
gl_end = 0x08006678
gl_end2 = thumb.function_end(rom, BASE, gl_start, gl_start + 0x400)
print()
print("DecompressGlyph_Normal 0x%08X .. 0x%08X (0x%04X); thumb function_end says 0x%08X"
      % (gl_start, gl_end, gl_end - gl_start, gl_end2))

print()
print("== expected FNV-1a constants ==")
region("bit0 gStdTextWindow_Gfx", 0x0846FFA8, 0x0120)
region("bit1 sGlyphWidthFuncs", 0x081EA500, 0x0038)
region("bit2 DecompressGlyph_Normal", gl_start, gl_end - gl_start)
region("bit3 sFontNormalLatinGlyphWidths", 0x082071AC, 0x0200)
region("bit4 sFontNormalLatinGlyphs", 0x081FF1AC, 0x8000)
region("bit5 DecompressGlyphTile", tile_start, tile_end - tile_start)
region("bit6 sFontHalfRowOffsets", 0x081E9EF0, 0x0100)