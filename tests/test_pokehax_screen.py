"""Real execution checks for Android's interactive PokeHax Screen payload."""

from pathlib import Path
import re

import pytest

from pokeldn.frlg.rom import buffer_script as bs
from pokeldn.frlg.rom.buffer_payloads import POKEHAX_SCREEN_BODY


pytestmark = pytest.mark.skipif(not bs.emulation_available(), reason="needs unicorn")

REG_DISPCNT = 0x04000000
REG_KEYINPUT = 0x04000130
REG_IME = 0x04000208
TILE_BASE = 0x06017C00
OAM_120 = 0x030026C8
OAM_BASE = OAM_120 - 0x3C0
OVERLAY_PAL = 0x020379D6
BG_FADED = OVERLAY_PAL - 0x3E2
BG_UNFADED = BG_FADED - 0x400
OBJ_FADED = OVERLAY_PAL - 2
OBJ_UNFADED = OBJ_FADED - 0x400
SCRATCH = 0x0201E000

GLYPHS = (
    (0, 0, 0, 0, 0), (7, 5, 7, 1, 1), (7, 5, 5, 5, 7),
    (5, 5, 3, 5, 5), (7, 1, 3, 1, 7), (5, 5, 7, 5, 5),
    (2, 5, 7, 5, 5), (5, 5, 2, 5, 5),
)
TITLE_SLOTS = (120, 121, 122, 123, 124, 127, 126)


def _android_body():
    source = (Path(__file__).parents[1] / "android/app/src/main/java/com/dedhapp3n/"
              "pokeldn/frlg/PokeHaxScreenPayload.kt").read_text()
    block = source.split("private val baseBody = (", 1)[1].split(").hexBytesScreen()", 1)[0]
    return bytes.fromhex("".join(re.findall(r'\"([0-9a-f]+)\"', block)))


def _word(uc, address):
    return int.from_bytes(uc.mem_read(address, 4), "little")


def _pixel(uc, glyph, x, y):
    row = bytes(uc.mem_read(TILE_BASE + glyph * 32 + y * 4, 4))
    packed = row[x // 2]
    return (packed >> (4 * (x & 1))) & 0xF


def _start_machine(memory, sav1, sav2):
    leads, final = bs.pokehax_screen_stages("BPGD")
    machine = bs._Machine(leads[0], build="BPGD", sav1=sav1, sav2=sav2, memory=memory)
    run = machine.call(instruction_limit=5_000)
    assert run.done
    for lead in leads[1:]:
        machine.load(lead, param=run.param)
        run = machine.call(instruction_limit=5_000)
        assert run.done
    machine.load(final, param=0)
    return machine


def test_interactive_screen_uses_edges_pages_live_values_and_restores_everything():
    assert _android_body() == POKEHAX_SCREEN_BODY
    original_oam = bytes((index * 17 + 3) & 0xFF for index in range(0x400))
    original_tiles = bytes((index * 29 + 7) & 0xFF for index in range(30 * 32))
    original_dispcnt = b"\x03\x1f"
    original_obj_faded = b"\x12\x34\x56\x78\x9a\xbc\xde\xf0"
    original_obj_unfaded = b"\x0f\xed\xcb\xa9\x87\x65\x43\x21"
    original_bg_faded = b"\x21\x43"
    original_bg_unfaded = b"\x65\x87"
    sav1 = bytes((index * 5) & 0xFF for index in range(0x1000))
    sav2 = bytes((index * 7) & 0xFF for index in range(0x1000))
    rng = 0xDEADBEEF
    machine = _start_machine({
        REG_DISPCNT: original_dispcnt,
        REG_KEYINPUT: b"\xff\x03",
        REG_IME: b"\x01\x00",
        OAM_BASE: original_oam,
        TILE_BASE: original_tiles,
        OBJ_FADED: original_obj_faded,
        OBJ_UNFADED: original_obj_unfaded,
        BG_FADED: original_bg_faded,
        BG_UNFADED: original_bg_unfaded,
        0x03004220: rng.to_bytes(4, "little"),
    }, sav1, sav2)

    writes = []
    from unicorn import UC_HOOK_MEM_WRITE
    hook = machine.uc.hook_add(
        UC_HOOK_MEM_WRITE,
        lambda _uc, _access, address, size, _value, _data: writes.append((address, size)),
    )
    runs = []

    def frame(key=0x3FF):
        machine.uc.mem_write(REG_KEYINPUT, key.to_bytes(2, "little"))
        run = machine.call(instruction_limit=15_000)
        runs.append(run)
        assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"
        return run

    def press(key):
        pressed = frame(0x3FF & ~key)
        if not pressed.done:
            frame()
        return pressed

    try:
        first = frame()
        assert not first.done and first.param == 0x5048584D
        assert _word(machine.uc, SCRATCH + 4) == 0
        assert _word(machine.uc, SCRATCH + 8) == 0

        oam = bytes(machine.uc.mem_read(OAM_BASE, 0x400))
        assert int.from_bytes(oam[125 * 8:125 * 8 + 2], "little") == 160
        for glyph, slot in enumerate(TITLE_SLOTS, 1):
            entry = slot * 8
            assert int.from_bytes(oam[entry:entry + 2], "little") == 0x310
            assert int.from_bytes(oam[entry + 4:entry + 6], "little") == 0xF3E0 + glyph
        assert int.from_bytes(oam[127 * 8 + 4:127 * 8 + 6], "little") == 0xF3E6
        for glyph, rows in enumerate(GLYPHS):
            for y in range(8):
                for x in range(8):
                    lit = 1 <= y < 6 and 2 <= x < 5 and rows[y - 1] & (1 << (x - 2))
                    assert _pixel(machine.uc, glyph, x, y) == (1 if lit else 2)

        press(0x80)  # Down: RNG
        assert _word(machine.uc, SCRATCH + 8) == 1
        press(0x40)  # Up: FRAME
        assert _word(machine.uc, SCRATCH + 8) == 0
        press(0x01)  # A: FRAME page
        assert _word(machine.uc, SCRATCH + 4) == 1
        before = _word(machine.uc, SCRATCH + 12)
        frame()
        assert _word(machine.uc, SCRATCH + 12) == before + 1
        press(0x02)  # B: menu
        assert _word(machine.uc, SCRATCH + 4) == 0

        press(0x80)  # RNG selection
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 2
        hex_glyphs = (29, 4, 6, 29, 28, 4, 4, 8)  # DEADBEEF
        oam = bytes(machine.uc.mem_read(OAM_BASE, 0x400))
        assert tuple(int.from_bytes(oam[s * 8 + 4:s * 8 + 6], "little") - 0xF3E0
                     for s in range(90, 98)) == hex_glyphs
        press(0x02)

        press(0x80)  # COLOR selection
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 3
        press(0x01)
        assert _word(machine.uc, SCRATCH + 16) == 1
        assert bytes(machine.uc.mem_read(BG_FADED, 2)) == b"\x0c\x00"
        press(0x02)

        press(0x80)  # EXIT selection
        final = press(0x01)
    finally:
        machine.uc.hook_del(hook)

    assert final.done and final.param == 1
    assert max(run.instructions for run in runs) < 15_000
    assert final.sav1 == sav1 and final.sav2 == sav2
    assert machine.flash_reads == [] and machine.flash_writes == [] and machine.bkpts == []
    assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"
    assert bytes(machine.uc.mem_read(REG_DISPCNT, 2)) == original_dispcnt
    assert bytes(machine.uc.mem_read(OAM_BASE, 0x400)) == original_oam
    assert bytes(machine.uc.mem_read(TILE_BASE, 30 * 32)) == original_tiles
    assert bytes(machine.uc.mem_read(OBJ_FADED, 8)) == original_obj_faded
    assert bytes(machine.uc.mem_read(OBJ_UNFADED, 8)) == original_obj_unfaded
    assert bytes(machine.uc.mem_read(BG_FADED, 2)) == original_bg_faded
    assert bytes(machine.uc.mem_read(BG_UNFADED, 2)) == original_bg_unfaded
    assert {size for address, size in writes if 0x06000000 <= address < 0x06018000} == {4}


def test_multistage_payloads_fit_and_patch_english_and_japanese_buffers():
    for code, oam, palette, rng in (
        ("BPGD", 0x030026C8, 0x020379D6, 0x03004220),
        ("BPGE", 0x03002778, 0x020379D6, 0x030042D0),
        ("BPGJ", 0x030026D8, 0x0203790A, 0x03004230),
    ):
        leads, final = bs.pokehax_screen_stages(code)
        assert tuple(map(len, leads)) == (1024, 260)
        assert len(final) == 12
        body = b"".join(lead[48:] for lead in leads)
        assert oam.to_bytes(4, "little") in body
        assert palette.to_bytes(4, "little") in body
        assert rng.to_bytes(4, "little") in body
