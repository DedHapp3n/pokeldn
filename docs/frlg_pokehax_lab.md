---
title: POKEHAX LAB
parent: FireRed and LeafGreen
nav_order: 8
---

# POKEHAX LAB

`POKEHAX LAB` is a frame-driven FRLG diagnostics menu. It has two launch modes:

- **Start PokeHax Lab** transfers the body through the existing Mystery Gift
  `CLI_RUN_BUFFER_SCRIPT` path and keeps it only in RAM.
- **Install persistent Lab** performs the same transfer once, then deliberately reserves one
  documented flash sector and installs a saved Mom RAM script. On later boots the player can use
  `CONTINUE`, enter the normal overworld, and talk to Mom to load the Lab without Android or a new
  Mystery Gift transfer.

Neither mode blocks while waiting for input. Every invocation processes one frame and returns. The
interactive body is 3792 bytes, so the initial transfer uses four bounded copy stages rather than
weakening restore or validation to fit one 1024-byte receive buffer.

## BG0 renderer

The menu no longer depends on the game's sprite allocator. It switches to mode 0 and uses a private
BG0 character block at `0x0600C000`, screen block 31 at `0x0600F800`, and BG palette entries 0..3.
A compact 3x5 font supplies title, all seven menu entries, subpages, decimal values and hexadecimal
values. The complete menu fits inside the visible 30x20-tile display.

Before its first draw the body saves `DISPCNT`, `BG0CNT`, BG0 scroll, both faded and unfaded palette
entries, all 38 borrowed font tiles, and the complete 2 KiB BG0 tilemap. EXIT restores every byte.
VRAM writes are aligned 32-bit stores. The renderer performs no OAM writes, so `gOamLimit`,
`AddSpritesToOamBuffer`, OAM slots below 64, and the hardware-specific slot 125 conflict cannot hide
any LAB row. Text and background RGB values are applied through the BG palette buffers and are
redrawn after the game's original VBlank handler.

## Read-only pages

- LIVE reads the frame counter, `gRngValue`, player coordinates and direction, map group/number,
  and the current `gMain` callbacks.
- PARTY reads six 100-byte `struct Pokemon` values. Nickname, level and HP are plaintext. PID
  selects the 24-way substructure order; Item, EVs, IVs and ability are XOR-decoded into private
  scratch without changing the party data.
- WORLD reads live values from the selected member of the 16-entry `gObjectEvents` array.
- MEMORY is restricted to `0x02000000..0x0203FFF0` (EWRAM) and
  `0x03000000..0x03007FF0` (IWRAM). Movement clamps before every dereference.
- DISPLAY edits only the LAB BG palette. Text/background R, G and B are clamped to 0..31; rainbow
  and preset demos use the same RGB555 entries.
- HOST reports only the architecturally visible guest boundary described below.

The normal launch path writes only private EWRAM, reversible display/BG palette/VRAM state, and the
Mystery Gift result word. SaveBlock1, SaveBlock2 and flash remain unchanged.

## Persistent Hacker-Save layout

Persistent installation is intentionally separate and requires an explicit warning confirmation in
the Android UI. It changes exactly these reserved areas:

| Location | Purpose |
| --- | --- |
| physical flash sector 30 (4 KiB) | `PHL1` header, body length, additive checksum, LAB body |
| `SaveBlock1 + 0x361C`, 1004 bytes | normal FRLG saved RAM-script record, bound to map 4/0 object 1 (Mom) |

FRLG's two rotating main-save slots occupy sectors 0..27, Hall of Fame uses sectors 28..29, Trainer
Tower data uses sector 30, and sector 31 is unused by this installer (see the repository's
[save/flash measurements](frlg_rom.md#the-sloop-syscall-boundary)). Thus the reservation does not
blindly overwrite party, boxes, inventory, map state, either main-save slot, Hall of Fame, or sector
31. It **does replace Trainer Tower auxiliary data and the existing saved RAM-script/Wonder Card
slot**, so this option is for a dedicated Hacker-Save and is not presented as harmless.

The loader selects flash bank 1, reads sector 30, requires magic `PHL1`, checks a word-aligned length
no larger than `0xF00`, copies the body to `0x0201C800`, and verifies its additive checksum before it
installs anything. It then disables IME only around the short resident-hook copy/table replacement,
restores IME immediately, and chains the game's original VBlank handler before drawing LAB. EXIT
restores the full display state and original VBlank table entry.

## Continue/autostart research

A true `CONTINUE -> automatic LAB` trigger is **not implemented**, because no safe serialized
callback has been established. FRLG's Continue path uses fixed ROM callbacks to deserialize the
rotating sector save into fresh EWRAM. The interrupt table and all candidate resident EWRAM are
rebuilt on boot. The documented save structures contain game data and the validated saved RAM-script
record, but no proven field that the Continue loader invokes as an arbitrary callback.

Writing an assumed function pointer into an unknown save field would violate the required safety
boundary. The proven fallback therefore remains explicit:

1. install once from Android/Mystery Gift;
2. on later boots choose CONTINUE normally;
3. talk to Mom in Pallet Town;
4. the checksummed saved script validates and loads the persistent body.

The implementation follows the repository's existing `install-kept`, save-loader,
[saved RAM-script](frlg_gift.md#the-one-ram-script-slot), resident VBlank and follower-persistence
designs, but does not claim that a conversation-triggered loader is automatic boot execution.

## HOST boundary

The retail program sees the GBA BIOS/SVC interface, cartridge ROM, EWRAM, IWRAM, MMIO, palette RAM,
VRAM, OAM and save-memory buses. No console name, wall clock, Switch firmware, wrapper version,
build ID or other host metadata interface is identified by the cartridge or the measured ROM/RAM
maps. HOST therefore performs no speculative probes and reports those fields as `NOT EXPOSED`.
There is no host pointer traversal, vtable/bus-object mutation or sandbox-escape attempt.

## Tests

`tests/test_pokehax_screen.py` executes the actual ARM/Thumb payload with Unicorn. It checks the
BG0 title and every main-menu label, proves OAM is byte-identical and receives no writes, drives
Up/Down/Left/Right/A/B through every page, changes live values, exercises both RAM-viewer bounds and
RGB state, and verifies complete display restore.

The persistence test executes all transfer stages and installer, records the sole sector-30 flash
write, proves Save1 outside the documented RAM-script record and all of Save2 remain identical,
clears EWRAM to simulate a reboot, executes the embedded Mom loader from the saved record, reloads
the body from flash, opens the menu through the resident VBlank hook, and verifies EXIT restores the
original handler.
