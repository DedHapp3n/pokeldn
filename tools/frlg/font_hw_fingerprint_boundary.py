#!/usr/bin/env python3
"""Verify the exact DecompressGlyph_Normal / GetGlyphWidth_Normal boundary (bit 2).

The user states GetGlyphWidth_Normal is at 0x08006678 and the region to hash is
0x08006538 .. 0x08006678 (0x140 bytes). thumb.function_end returned 0x0800669E, so
disassemble the whole span with a real THUMB length decoder to see the truth.
"""

import sys
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from pokeldn.frlg.rom import thumb  # noqa: E402

ROMF = ROOT / "pokegift" / "saves-rom" / "Pokemon - Blattgruene Edition (Germany).gba"
BASE = 0x08000000
rom = ROMF.read_bytes()


def hw(at):
    return int.from_bytes(rom[at:at + 2], "little")


def thumb_len(h):
    """Return the size (2 or 4 bytes) of the THUMB instruction halfword h."""
    hi = h & 0xF800
    # Two-word THUMB (32-bit) instructions.
    if hi in (0xD000, 0xD100, 0xD200, 0xD300, 0xD400, 0xD500, 0xD600, 0xD700,
              0xD800, 0xD900, 0xDA00, 0xDB00):          # cond (16-bit form)
        return 2
    if 0xE000 <= h <= 0xE6FF:                            # 32-bit data proc / branch
        return 4
    if h in (0xBEF9, 0xBF00, 0xBF01, 0xBF02, 0xBF03, 0xBF0F,
             0xBF10, 0xBF11, 0xBF1F):                     # misc (bkpt, etc.)
        return 4
    if (h & 0xF000) == 0xF000:                            # wide 32-bit (bl/bx/movw/movt/...)
        return 4
    if (h & 0xF800) == 0xF000:                            # wide immediate
        return 4
    return 2


def disassemble(start, count):
    at = start - BASE
    for _ in range(count):
        h = hw(at)
        size = thumb_len(h)
        tag = ""
        if thumb.is_return(h):
            tag = "  <== RETURN"
        elif thumb.is_prologue(h):
            tag = "  <== PROLOGUE"
        ldr = thumb.LDR_PC
        if (h & thumb.LDR_PC_MASK) == ldr:
            pool = (((BASE + at) + 4) & ~3) + (h & 0xFF) * 4
            tag = "  ldr [pc,#%02X] -> 0x%08X" % (h & 0xFF,
                  int.from_bytes(rom[pool - BASE:pool - BASE + 4], "little"))
        print("    0x%08X  0x%04X  (%dB)%s" % (BASE + at, h, size, tag))
        at += size


print("=== 0x08006538 (DecompressGlyph_Normal) first 8 insns ===")
disassemble(0x08006538, 8)
print()
print("=== span 0x08006660 .. 0x080066A0 (the boundary of interest) ===")
disassemble(0x08006660, 40)
print()
# Where does the function at 0x08006678 (user's GetGlyphWidth_Normal) end?
print("=== function_end from user's 0x08006678 ===")
e1 = thumb.function_end(rom, BASE, 0x08006678, 0x08006678 + 0x400)
print("    0x08006678 .. 0x%08X  (len 0x%04X)" % (e1, e1 - 0x08006678))