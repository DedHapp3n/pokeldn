#!/usr/bin/env python3
"""Assemble PokeHax_BPGD_FONT_HW_FINGERPRINT.s and commit the payload and gift file.

    python tools/frlg/font_hw_fingerprint_build.py

The region table in the .s is the source of truth: every expected FNV-1a is recomputed
from the BPGD ROM dump and the build fails if a constant or boundary drifted.
"""

import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from pokeldn.frlg.rom import custom_code  # noqa: E402

HERE = ROOT / "pokegift"
SOURCE = HERE / "PokeHax_BPGD_FONT_HW_FINGERPRINT.s"
BINARY = HERE / "PokeHax_BPGD_FONT_HW_FINGERPRINT.pokegift.buffer_code"
GIFT = HERE / "PokeHax_BPGD_FONT_HW_FINGERPRINT.pokegift"
ROM = HERE / "saves-rom/Pokemon - Blattgruene Edition (Germany).gba"
EXPECTED = 0x4846007F
ROM_SHA = "6ab7183caf8bf093f42351cf7c891722ecef85f2ded922f6d2491c6062fa3fc2"


def fnv1a(data):
    value = 0x811C9DC5
    for byte in data:
        value = ((value ^ byte) * 0x01000193) & 0xFFFFFFFF
    return value


def main():
    rom = ROM.read_bytes()
    assert hashlib.sha256(rom).hexdigest() == ROM_SHA, "the BPGD ROM dump changed"
    source = SOURCE.read_text(encoding="utf-8")
    table = re.findall(r"\.word\s+(0x[0-9A-Fa-f]{8}),\s*(0x[0-9A-Fa-f]{8}),"
                       r"\s*(0x[0-9A-Fa-f]{8})", source)
    assert len(table) == 7, "expected the 7-entry region table"
    for bit, (start_s, end_s, expect_s) in enumerate(table):
        start, end, expect = (int(value, 16) for value in (start_s, end_s, expect_s))
        assert 0x08000000 <= start < end <= 0x09000000, (bit, start_s, end_s)
        got = fnv1a(rom[start - 0x08000000:end - 0x08000000])
        assert got == expect, f"bit {bit}: ROM FNV-1a is 0x{got:08X}, table says {expect_s}"
    code = custom_code.assemble(source)
    assert len(code) <= 0x400 and len(code) % 4 == 0, f"{len(code)} bytes"
    BINARY.write_bytes(code)
    gift = {
        "format": "pokeldn.gift",
        "game": "frlg",
        "name": "PokeHax BPGD FONT hardware fingerprint",
        "variants": {
            "BPGD": {
                "data": {"buffer_code": {
                    "hex": code.hex(),
                    "sha256": hashlib.sha256(code).hexdigest(),
                }},
                "options": {"buffer_expect": EXPECTED},
            }
        },
        "version": 2,
    }
    GIFT.write_text(json.dumps(gift, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"payload: {len(code)} bytes / 0x400  sha256 {hashlib.sha256(code).hexdigest()}")
    print(f"buffer_expect: 0x{EXPECTED:08X} ({EXPECTED})")
    for bit, (start_s, end_s, expect_s) in enumerate(table):
        print(f"bit {bit}: {start_s} .. {end_s}  fnv1a {expect_s}")


if __name__ == "__main__":
    main()