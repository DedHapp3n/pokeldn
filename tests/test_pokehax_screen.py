"""Execution checks for the Android PokeHax Screen CLI_RUN_BUFFER_SCRIPT payload."""

from pathlib import Path
import re

import pytest

from pokeldn.frlg.rom import buffer_script as bs
from pokeldn.frlg.rom.buffer_payloads import PAYLOADS


pytestmark = pytest.mark.skipif(not bs.emulation_available(), reason="needs unicorn")

REG_DISPCNT = 0x04000000
REG_KEYINPUT = 0x04000130
REG_IME = 0x04000208
TILE_BASE = 0x06017C80
OAM_120 = 0x030026C8
OAM_BASE = OAM_120 - 0x3C0
OVERLAY_PAL = 0x020379D6
BG_FADED = OVERLAY_PAL - 0x3E2
BG_UNFADED = BG_FADED - 0x400
OBJ_FADED = OVERLAY_PAL - 2
OBJ_UNFADED = OBJ_FADED - 0x400

GLYPHS = (
    (7, 5, 7, 1, 1),  # P
    (7, 5, 5, 5, 7),  # O
    (5, 5, 3, 5, 5),  # K
    (7, 1, 3, 1, 7),  # E
    (5, 5, 7, 5, 5),  # H
    (2, 5, 7, 5, 5),  # A
    (5, 5, 2, 5, 5),  # X
)


def _android_payload():
    source = (Path(__file__).parents[1] / "android/app/src/main/java/com/dedhapp3n/"
              "pokeldn/frlg/PokeHaxScreenPayload.kt").read_text()
    block = source.split("private val baseImage = (", 1)[1].split(").hexBytesScreen()", 1)[0]
    return bytes.fromhex("".join(re.findall(r'\"([0-9a-f]+)\"', block)))


def _pixel(uc, glyph, x, y):
    tile = 996 + glyph * 4 + (y // 8) * 2 + x // 8
    row = bytes(uc.mem_read(0x06010000 + tile * 32 + (y & 7) * 4, 4))
    packed = row[(x & 7) // 2]
    return (packed >> (4 * (x & 1))) & 0xF


def test_android_pokehax_screen_executes_one_bounded_step_per_frame_and_restores():
    code = PAYLOADS["pokehax-screen"][0]
    assert _android_payload() == code

    original_oam = bytes((index * 17 + 3) & 0xFF for index in range(0x400))
    original_tiles = bytes((index * 29 + 7) & 0xFF for index in range(28 * 32))
    original_dispcnt = b"\x03\x1f"
    original_obj_faded = b"\x12\x34\x56\x78"
    original_obj_unfaded = b"\x9a\xbc\xde\xf0"
    original_bg_faded = b"\x21\x43"
    original_bg_unfaded = b"\x65\x87"
    sav1 = bytes((index * 5) & 0xFF for index in range(0x1000))
    sav2 = bytes((index * 7) & 0xFF for index in range(0x1000))

    machine = bs._Machine(code, build="BPGD", sav1=sav1, sav2=sav2, memory={
        REG_DISPCNT: original_dispcnt,
        REG_KEYINPUT: b"\x00\x00",  # A is held when the payload starts.
        REG_IME: b"\x01\x00",
        OAM_BASE: original_oam,
        TILE_BASE: original_tiles,
        OBJ_FADED: original_obj_faded,
        OBJ_UNFADED: original_obj_unfaded,
        BG_FADED: original_bg_faded,
        BG_UNFADED: original_bg_unfaded,
    })

    writes = []
    from unicorn import UC_HOOK_MEM_WRITE
    hook = machine.uc.hook_add(
        UC_HOOK_MEM_WRITE,
        lambda _uc, _access, address, size, _value, _data: writes.append((address, size)),
    )
    try:
        runs = []
        for key in (0, 0, 1, 1):
            machine.uc.mem_write(REG_KEYINPUT, key.to_bytes(2, "little"))
            runs.append(machine.call(instruction_limit=10_000))
            assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"

        assert [run.returned for run in runs] == [0, 0, 0, 0]
        assert [run.param for run in runs] == [0x50485801, 0x50485801,
                                               0x50485802, 0x50485802]
        assert max(run.instructions for run in runs) < 6_000

        assert bytes(machine.uc.mem_read(REG_DISPCNT, 2)) == b"\x40\x10"
        assert bytes(machine.uc.mem_read(OBJ_FADED, 4)) == b"\x00\x00\xff\x7f"
        assert bytes(machine.uc.mem_read(OBJ_UNFADED, 4)) == b"\x00\x00\xff\x7f"
        assert bytes(machine.uc.mem_read(BG_FADED, 2)) == b"\x00\x00"
        assert bytes(machine.uc.mem_read(BG_UNFADED, 2)) == b"\x00\x00"

        oam = bytes(machine.uc.mem_read(OAM_BASE, 0x400))
        assert all(int.from_bytes(oam[index:index + 2], "little") == 160
                   for index in range(0, 120 * 8, 8))
        for glyph in range(7):
            entry = (120 + glyph) * 8
            assert int.from_bytes(oam[entry:entry + 2], "little") == 72
            assert int.from_bytes(oam[entry + 2:entry + 4], "little") == 0x4040 + glyph * 16
            assert int.from_bytes(oam[entry + 4:entry + 6], "little") == 0xF3E4 + glyph * 4

        for glyph, rows in enumerate(GLYPHS):
            for y in range(16):
                for x in range(16):
                    lit = 3 <= y < 13 and 5 <= x < 11 and (
                        rows[(y - 3) // 2] & (1 << ((x - 5) // 2)))
                    assert _pixel(machine.uc, glyph, x, y) == (1 if lit else 2)

        machine.uc.mem_write(REG_KEYINPUT, b"\x00\x00")
        final = machine.call(instruction_limit=10_000)
    finally:
        machine.uc.hook_del(hook)

    assert final.done and final.param == 1
    assert final.instructions < 3_000
    assert final.sav1 == sav1 and final.sav2 == sav2
    assert machine.flash_reads == [] and machine.flash_writes == [] and machine.bkpts == []
    assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"
    assert bytes(machine.uc.mem_read(REG_DISPCNT, 2)) == original_dispcnt
    assert bytes(machine.uc.mem_read(OAM_BASE, 0x400)) == original_oam
    assert bytes(machine.uc.mem_read(TILE_BASE, 28 * 32)) == original_tiles
    assert bytes(machine.uc.mem_read(OBJ_FADED, 4)) == original_obj_faded
    assert bytes(machine.uc.mem_read(OBJ_UNFADED, 4)) == original_obj_unfaded
    assert bytes(machine.uc.mem_read(BG_FADED, 2)) == original_bg_faded
    assert bytes(machine.uc.mem_read(BG_UNFADED, 2)) == original_bg_unfaded
    assert {size for address, size in writes if 0x06000000 <= address < 0x06018000} == {4}


def test_pokehax_screen_patch_points_cover_english_and_japanese_game_buffers():
    code = PAYLOADS["pokehax-screen"][0]
    assert int.from_bytes(code[760:764], "little") == 0x030026C8
    assert int.from_bytes(code[764:768], "little") == 0x020379D6

    source = (Path(__file__).parents[1] / "android/app/src/main/java/com/dedhapp3n/"
              "pokeldn/frlg/PokeHaxScreenPayload.kt").read_text()
    assert '"BPRE" to (0x03002778 to 0x020379d6)' in source
    assert '"BPRJ" to (0x030026d8 to 0x0203790a)' in source
