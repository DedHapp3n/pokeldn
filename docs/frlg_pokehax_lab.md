---
title: POKEHAX LAB
parent: FireRed and LeafGreen
nav_order: 8
---

# POKEHAX LAB

`POKEHAX LAB` is a frame-driven, RAM-only diagnostics menu delivered through the existing FRLG
Mystery Gift `CLI_RUN_BUFFER_SCRIPT` path. The 1 KiB receive limit is handled by bounded copy
stages which place one fixed-address body in unused `gDecompressionBuffer` space; the final 12-byte
trampoline calls that body once per client frame. No handler waits for a key and every non-EXIT
invocation returns zero to the Mystery Gift client.

The renderer extends the physically verified resident overlay design. It writes only 32-bit words
to borrowed OBJ VRAM, updates the game's shadow OAM and palette buffers, and never changes IME.
OAM slot 125 remains hidden because it conflicts on German LeafGreen/Switch 2 hardware. The
verified `POKEHAX` letters retain slots 120, 121, 122, 123, 124, 127 and 126; `LAB` uses 116..118.
The original display control, complete shadow OAM, affected faded/unfaded palettes, backdrop colour
and all borrowed OBJ tiles are copied to EWRAM before the first draw and restored by EXIT.

## Read-only game data

The pages use only build-selected symbols already documented by the FRLG ROM map:

- LIVE reads `gRngValue`, `gMain.callback1/callback2`, `gPlayerAvatar`, the player's
  `ObjectEvent`, and the active SaveBlock1 map group/number.
- PARTY reads six 100-byte `struct Pokemon` values from `gPlayerParty`. Nickname, level and HP are
  plaintext. PID selects the standard 24-way substructure order; the payload XOR-decrypts Item,
  EV, IV and ability fields into its own scratch words without writing or temporarily decrypting
  the Pokemon.
- WORLD reads the selected member of the 16-entry `gObjectEvents` array: active/status flags,
  local ID, graphics ID and current X/Y.
- MEMORY permits only `0x02000000..0x0203FFF0` (EWRAM) and
  `0x03000000..0x03007FF0` (IWRAM). Directional movement clamps before dereferencing; A switches
  between the two known RAM buses.
- DISPLAY changes only the two borrowed OBJ colours and backdrop colour. Text/background R, G and
  B are independently clamped to 0..31; rainbow and palette demos use the same RGB555 words.

The only writes outside private scratch are the renderer's reversible OAM, palette, display and
OBJ-tile writes. SaveBlock1 and SaveBlock2 are read-only, and no flash command or flash address is
used.

## HOST boundary

The retail GBA program runs in a GBA guest address space. Its architecturally visible interfaces
are BIOS/SVC, cartridge ROM, EWRAM, IWRAM, MMIO, palette RAM, VRAM, OAM and save-memory buses. The
Switch GBA wrapper does not map a console-name, wall-clock, firmware, wrapper-version, build-ID or
other host metadata structure into any interface identified by the cartridge or by this project's
measured ROM/RAM maps.

Consequently HOST deliberately performs no exploratory bus reads and displays:

```
GUEST: GBA
CONSOLE: NOT EXPOSED
DATE: NOT EXPOSED
FIRMWARE: NOT EXPOSED
WRAPPER: NOT EXPOSED
```

Reporting guessed bytes from undocumented addresses would be both speculative and unsafe. Crossing
this boundary would require a wrapper-provided guest interface or a host vulnerability; neither is
part of POKEHAX LAB. There is no host pointer traversal, vtable/bus-object mutation, sandbox escape,
or probe outside documented GBA memory.

`tests/test_pokehax_screen.py` executes every copy stage and menu frame under Unicorn, drives
Up/Down/Left/Right/A/B, checks live RAM values and decrypted party fields, checks both RAM-viewer
bounds and RGB state, records HOST reads, enforces word-only VRAM writes, and verifies complete
restore with Save1, Save2 and flash untouched.
