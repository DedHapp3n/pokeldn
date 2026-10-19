"""Execution tests for Android's resident, read-only POKEHAX LAB payload."""

from pathlib import Path
import re

import pytest

from pokeldn.frlg.rom import buffer_script as bs
from pokeldn.frlg.rom.buffer_payloads import POKEHAX_SCREEN_BODY
from pokeldn.frlg.save.mon import to_encrypted


pytestmark = pytest.mark.skipif(not bs.emulation_available(), reason="needs unicorn")

REG_DISPCNT = 0x04000000
REG_KEYINPUT = 0x04000130
REG_IME = 0x04000208
TILE_BASE = 0x06017B40
TILE_BYTES = 38 * 32
OAM_120 = 0x030026C8
OAM_BASE = OAM_120 - 0x3C0
OVERLAY_PAL = 0x020379D6
BG_FADED = OVERLAY_PAL - 0x3E2
BG_UNFADED = BG_FADED - 0x400
OBJ_FADED = OVERLAY_PAL - 2
OBJ_UNFADED = OBJ_FADED - 0x400
SCRATCH = 0x0201F000
PARTY = 0x02024280
AVATAR = 0x02037074
OBJECTS = 0x02036E34
GMAIN = 0x030022D0
RNG = 0x03004220


def _android_body():
    source = (Path(__file__).parents[1] / "android/app/src/main/java/com/dedhapp3n/"
              "pokeldn/frlg/PokeHaxScreenPayload.kt").read_text()
    block = source.split("private val baseBody = (", 1)[1].split(").hexBytesScreen()", 1)[0]
    return bytes.fromhex("".join(re.findall(r'\"([0-9a-f]+)\"', block)))


def _word(uc, address):
    return int.from_bytes(uc.mem_read(address, 4), "little")


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


def test_lab_executes_every_page_bounds_host_reads_and_full_restore():
    assert _android_body() == POKEHAX_SCREEN_BODY
    original_oam = bytes((i * 17 + 3) & 0xFF for i in range(0x400))
    original_tiles = bytes((i * 29 + 7) & 0xFF for i in range(TILE_BYTES))
    original_dispcnt = b"\x03\x1f"
    original_obj_faded = b"\x12\x34\x56\x78\x9a\xbc\xde\xf0"
    original_obj_unfaded = b"\x0f\xed\xcb\xa9\x87\x65\x43\x21"
    original_bg_faded = b"\x21\x43"
    original_bg_unfaded = b"\x65\x87"
    sav1 = bytes((i * 5) & 0xFF for i in range(0x1000))
    sav2 = bytes((i * 7) & 0xFF for i in range(0x1000))
    party = bytearray(600)
    for slot in range(6):
        party[slot * 100:slot * 100 + 4] = (0x10203040 + slot).to_bytes(4, "little")
        party[slot * 100 + 84] = 10 + slot
        party[slot * 100 + 86:slot * 100 + 90] = \
            (20 + slot).to_bytes(2, "little") + (30 + slot).to_bytes(2, "little")
    decoded = bytearray(100)
    decoded[0:4] = (1).to_bytes(4, "little")
    decoded[4:8] = (2).to_bytes(4, "little")
    decoded[8:16] = bytes(range(0xBB, 0xC3))
    decoded[16:18] = b"\xff\xff"
    decoded[34:36] = (0x1234).to_bytes(2, "little")
    decoded[56:62] = bytes((1, 2, 3, 4, 5, 6))
    iv_word = 31 | (30 << 5) | (29 << 10) | (28 << 15) | (27 << 20) | (26 << 25) | (1 << 31)
    decoded[72:76] = iv_word.to_bytes(4, "little")
    decoded[84] = 42
    decoded[86:90] = (77).to_bytes(2, "little") + (99).to_bytes(2, "little")
    party[100:200] = to_encrypted(decoded)
    objects = bytearray(16 * 0x24)
    objects[0:2] = b"\x01\x00"
    objects[5] = 7
    objects[8] = 3
    objects[0x10:0x14] = b"\x0a\x00\x0b\x00"
    machine = _start_machine({
        REG_DISPCNT: original_dispcnt, REG_KEYINPUT: b"\xff\x03", REG_IME: b"\x01\x00",
        OAM_BASE: original_oam, TILE_BASE: original_tiles,
        OBJ_FADED: original_obj_faded, OBJ_UNFADED: original_obj_unfaded,
        BG_FADED: original_bg_faded, BG_UNFADED: original_bg_unfaded,
        RNG: (0xDEADBEEF).to_bytes(4, "little"), PARTY: party,
        AVATAR + 5: b"\x00", OBJECTS: objects,
        GMAIN: (0x08001235).to_bytes(4, "little") + (0x08005679).to_bytes(4, "little"),
    }, sav1, sav2)

    writes = []
    from unicorn import UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE
    write_hook = machine.uc.hook_add(
        UC_HOOK_MEM_WRITE,
        lambda _uc, _access, address, size, _value, _data: writes.append((address, size)),
    )

    def frame(key=0x3FF):
        machine.uc.mem_write(REG_KEYINPUT, key.to_bytes(2, "little"))
        run = machine.call(instruction_limit=20_000)
        assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"
        return run

    def press(key):
        result = frame(0x3FF & ~key)
        if not result.done:
            frame()
        return result

    try:
        first = frame()
        assert not first.done and first.param == 0x5048584D
        assert _word(machine.uc, SCRATCH + 4) == 0
        oam = bytes(machine.uc.mem_read(OAM_BASE, 0x400))
        assert int.from_bytes(oam[125 * 8:125 * 8 + 2], "little") == 160
        assert int.from_bytes(oam[127 * 8 + 4:127 * 8 + 6], "little") == 0xF3E0
        for slot in (116, 117, 118, 120, 121, 122, 123, 124, 126, 127):
            assert int.from_bytes(oam[slot * 8:slot * 8 + 2], "little") == 0x310

        # LIVE: changing values are read again on every non-blocking invocation.
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 1
        before = _word(machine.uc, SCRATCH + 12)
        machine.uc.mem_write(RNG, (0x12345678).to_bytes(4, "little"))
        frame()
        assert _word(machine.uc, SCRATCH + 12) == before + 1
        press(0x02)

        # PARTY list, all six slots, and detail page with RAM PID/secure data.
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 2
        press(0x80)
        assert _word(machine.uc, SCRATCH + 20) == 1
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 7
        assert _word(machine.uc, SCRATCH + 32) == 1
        assert _word(machine.uc, SCRATCH + 36) == 0x1234
        assert _word(machine.uc, SCRATCH + 40) == iv_word
        assert bytes(machine.uc.mem_read(SCRATCH + 44, 6)) == bytes((1, 2, 3, 4, 5, 6))
        press(0x02)
        assert _word(machine.uc, SCRATCH + 4) == 2
        press(0x02)

        # WORLD selection is live and bounded to the 16 ObjectEvent slots.
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 3
        press(0x80)
        assert _word(machine.uc, SCRATCH + 24) == 1
        press(0x40)
        assert _word(machine.uc, SCRATCH + 24) == 0
        press(0x80)
        machine.uc.mem_write(OBJECTS + 0x24 + 0x10, b"\x22\x00\x33\x00")
        frame()
        press(0x02)

        # MEMORY permits only EWRAM/IWRAM and clamps both ends.
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 4
        assert _word(machine.uc, SCRATCH + 28) == 0x02000000
        press(0x20)
        assert _word(machine.uc, SCRATCH + 28) == 0x02000000
        machine.uc.mem_write(SCRATCH + 28, (0x0203FFF0).to_bytes(4, "little"))
        press(0x10)
        assert _word(machine.uc, SCRATCH + 28) == 0x0203FFF0
        press(0x01)
        assert _word(machine.uc, SCRATCH + 28) == 0x03000000
        machine.uc.mem_write(SCRATCH + 28, (0x03007FF0).to_bytes(4, "little"))
        press(0x80)
        assert _word(machine.uc, SCRATCH + 28) == 0x03007FF0
        press(0x02)

        # DISPLAY changes the live palette through known safe presets.
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 5
        old_colour = _word(machine.uc, SCRATCH + 60)
        press(0x20)
        assert _word(machine.uc, SCRATCH + 60) != old_colour
        assert bytes(machine.uc.mem_read(OBJ_FADED + 2, 2)) != b"\xff\x7f"
        for _ in range(6):
            press(0x80)
        assert _word(machine.uc, SCRATCH + 56) == 6
        press(0x01)
        assert _word(machine.uc, SCRATCH + 68) == 1
        rainbow_before = _word(machine.uc, SCRATCH + 60)
        frame()
        assert _word(machine.uc, SCRATCH + 60) != rainbow_before
        press(0x01)
        assert _word(machine.uc, SCRATCH + 68) == 0
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 72) == 1
        press(0x02)

        # HOST renders constants only; all observed reads stay on documented GBA buses.
        press(0x80)
        press(0x01)
        assert _word(machine.uc, SCRATCH + 4) == 6
        host_reads = []
        read_hook = machine.uc.hook_add(
            UC_HOOK_MEM_READ,
            lambda _uc, _access, address, size, _value, _data: host_reads.append((address, size)),
        )
        frame()
        machine.uc.hook_del(read_hook)
        assert host_reads
        assert all(0x02000000 <= a < 0x08000000 for a, _ in host_reads)
        assert not any(0x0E000000 <= a < 0x0E010000 for a, _ in host_reads)
        press(0x02)

        press(0x80)
        final = press(0x01)
    finally:
        machine.uc.hook_del(write_hook)

    assert final.done and final.param == 1
    assert final.sav1 == sav1 and final.sav2 == sav2
    assert machine.flash_reads == [] and machine.flash_writes == [] and machine.bkpts == []
    assert bytes(machine.uc.mem_read(REG_IME, 2)) == b"\x01\x00"
    assert bytes(machine.uc.mem_read(REG_DISPCNT, 2)) == original_dispcnt
    assert bytes(machine.uc.mem_read(OAM_BASE, 0x400)) == original_oam
    assert bytes(machine.uc.mem_read(TILE_BASE, TILE_BYTES)) == original_tiles
    assert bytes(machine.uc.mem_read(OBJ_FADED, 8)) == original_obj_faded
    assert bytes(machine.uc.mem_read(OBJ_UNFADED, 8)) == original_obj_unfaded
    assert bytes(machine.uc.mem_read(BG_FADED, 2)) == original_bg_faded
    assert bytes(machine.uc.mem_read(BG_UNFADED, 2)) == original_bg_unfaded
    assert {size for address, size in writes if 0x06000000 <= address < 0x06018000} == {4}


def test_multistage_payloads_fit_and_patch_all_lab_ram_symbols():
    for code, expected in (
        ("BPGD", (0x030026C8, 0x030022D0, 0x02024280, 0x02037074, 0x02036E34)),
        ("BPGE", (0x03002778, 0x03002380, 0x02024280, 0x02037074, 0x02036E34)),
        ("BPGJ", (0x030026D8, 0x030022E0, 0x020241E0, 0x02036FA8, 0x02036D68)),
    ):
        leads, final = bs.pokehax_screen_stages(code)
        assert all(len(stage) <= 1024 for stage in (*leads, final))
        assert len(leads) >= 3 and len(final) == 12
        body = b"".join(lead[48:] for lead in leads)
        for address in expected:
            assert address.to_bytes(4, "little") in body
