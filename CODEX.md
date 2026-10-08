# CODEX.md

## Purpose

This branch is the Android port of [Decryptu/pokeldn](https://github.com/Decryptu/pokeldn), maintained in the fork `DedHapp3n/pokeldn`.

The project goal is a **native, touch-first Android application** that connects directly to an ESP32 over USB OTG and exposes pokeldn functionality without requiring a Raspberry Pi, desktop computer, SSH session, terminal, or external Wi-Fi adapter.

This is not a Raspberry Pi frontend. Android is the fixed target for this branch.

## Branch policy

- `main` should remain as close to upstream `Decryptu/pokeldn` as practical so upstream changes can be incorporated cleanly.
- Android-specific development belongs on the `android` branch.
- Avoid unnecessary edits to upstream protocol code and documentation.
- Keep the relationship to upstream and the AGPLv3 license explicit.
- Prefer small, reviewable commits with tests over large rewrites.

## Target architecture

Expected hardware path:

```text
Nintendo Switch / Switch 2
          |
          | Nintendo LDN, 2.4 GHz
          |
        ESP32
          |
          | USB serial
          | CP2102/CH340 or native USB Serial/JTAG
          |
   Android USB Host / OTG
          |
      Android app
```

The Android device is the host/controller. The ESP32 remains the radio and runs the upstream pokeldn ESP32 firmware.

The first development/reference board is a classic ESP32/ESP32S development board with a CP2102 USB-to-serial bridge and USB-C connector. Do not assume a fixed Android USB device path; enumerate devices through Android's USB Host API.

## Upstream facts to preserve

At the time this branch was created, upstream pokeldn:

- Uses an ESP32 board over USB serial as the LDN radio.
- Supports classic ESP32 and ESP32-S3/C3/C6 targets.
- Requires no software installation on the Switch or Switch 2.
- Supports FRLG, LGPE, SwSh, BDSP, PLA, SV and PLZA to differing degrees.
- Supports FRLG trades, Mystery Gift, link battles, console code execution and save read/write.
- Supports FRLG in English, French, German, Italian, Spanish and Japanese.
- Supports FRLG `.wc3` Wonder Cards and pokeldn `.pokegift` files.
- Supports Sword/Shield Mystery Gifts and `.wc8`.
- Uses `firmware/esp32` for the radio firmware.
- Keeps shared protocol/runtime code under `pokeldn/`.
- Uses `services/pkhex/` / PKHeX.Core for legality/building features in the desktop project.
- Uses Python 3.11+ for the existing host implementation.

Check upstream before relying on this list; pokeldn is actively developed.

## Android product goals

The finished application should feel like an appliance rather than a terminal wrapper.

Primary UX:

1. Open the app.
2. Connect the ESP32 by USB OTG.
3. Android requests USB permission when needed.
4. The app identifies and validates the board.
5. Select a game.
6. Select an operation such as Trade or Mystery Gift.
7. Configure the operation using touch-friendly controls.
8. Start it and show clear connection/protocol progress.
9. Save received Pokemon, gifts, captures or backups using Android storage APIs.

The normal user should not need to type command-line arguments.

Use large touch targets, clear status indicators, useful error messages and an obvious connected/disconnected state.

## Android implementation direction

Prefer a native Android application, initially Kotlin.

Recommended baseline:

- Kotlin
- Jetpack Compose for UI
- Android USB Host API for device discovery, permission and lifecycle
- A maintained USB serial implementation supporting CP210x and CH34x where needed
- Coroutines/Flow for serial and protocol state
- Storage Access Framework for user-selected files
- ViewModel/state-driven UI
- No root requirement
- No dependence on Termux

Do not blindly translate the Python UI or CLI to Kotlin. Separate protocol/data logic from Android UI and hardware lifecycle code.

Before porting a subsystem, identify its upstream Python entry point and tests. Port behavior with test vectors where practical.

## Major technical workstreams

### 1. ESP32 transport

This is the first milestone.

Understand and reproduce the host side of the existing ESP32 serial protocol. Android must be able to:

- discover the supported ESP32 serial device;
- request USB permission;
- open/configure the serial connection;
- perform the firmware HELLO/first-contact exchange;
- read board/firmware information;
- send commands and receive responses reliably;
- expose counters/errors to diagnostics;
- recover cleanly from unplug/replug and Android lifecycle changes.

Do not redesign the ESP32 wire protocol unless there is a compelling compatibility reason. Staying compatible with upstream firmware is a major goal.

### 2. LDN/network layer

Port or adapt only what Android needs above the ESP32 transport. The ESP32 is the Wi-Fi/LDN radio; Android should not attempt monitor mode or direct Nintendo LDN using the phone's Wi-Fi chipset.

### 3. Game protocol modules

Bring features across incrementally instead of attempting every supported title at once.

Suggested first functional target: **FireRed/LeafGreen**, because this project was initially motivated by FRLG Mystery Gift and the existing upstream implementation is feature-rich.

Initial FRLG candidates:

- ESP32 connection/diagnostics
- Direct Corner trade
- Mystery Gift / Wonder Card
- import/open `.wc3` and `.pokegift`
- safe/read-only save dump/backup before write-capable tools

Write-capable save operations and console-code features should be clearly distinguished in the UI from read-only operations and require deliberate confirmation.

After FRLG is stable, add other titles using the shared architecture.

### 4. Pokemon/file handling

Do not assume the existing .NET PKHeX helper can simply run on Android.

Investigate what functionality is actually required on-device. Prefer interoperable file import/export first. Treat a full PKHeX.Core integration/replacement as a separate workstream.

Preserve upstream file formats and compatibility wherever practical.

### 5. Firmware management

Eventually the Android app should be able to identify firmware version and guide/update/flash supported ESP32 boards where technically practical. This is not required for the first milestone.

First milestone may assume the ESP32 has already been flashed with compatible upstream firmware.

## Safety and data integrity

Some upstream FRLG functionality can execute native ARM payloads and write cartridge/save data. Treat these as advanced operations.

- Default to non-destructive/read-only functionality during early development.
- Never silently write a save.
- Clearly label operations that modify persistent game data.
- Validate inputs and target game/build/language before writes.
- Preserve backups and surface failure states.
- Do not weaken upstream checks merely to make an operation proceed.

## Security and research scope

pokeldn deals with network protocols and can be useful for interoperability and security research. Keep ordinary app features separate from exploit research.

Do not turn experimental vulnerability research into an implicit normal-user feature. Any future security-research tooling should be isolated, explicitly enabled, and documented separately.

## Testing

Reuse upstream fixtures and protocol documentation whenever possible.

For each ported subsystem:

- identify relevant upstream tests;
- create deterministic Kotlin/JVM unit tests for codecs/state machines;
- keep Android/USB integration tests separate from pure protocol tests;
- test disconnect/reconnect and malformed serial input;
- compare encoded/decoded bytes against upstream known-good vectors.

Do not rely only on a live Switch for correctness.

## Repository structure

Do not commit to a final Android directory layout until the first architecture pass is complete.

A likely direction is an `android/` Gradle project with modules separating:

- app/UI
- ESP32 USB/serial transport
- protocol/core
- game-specific modules
- file formats

Avoid moving or deleting upstream source trees just to make the repository look Android-native. Keeping upstream history mergeable is more valuable.

## Development priorities

Current order:

1. Establish Android project/build.
2. USB Host + CP2102 discovery and permission.
3. ESP32 first-contact/HELLO.
4. Stable serial transport and diagnostics screen.
5. Port the minimum shared protocol stack required for FRLG.
6. FRLG Mystery Gift read/safe path.
7. FRLG trade and file workflows.
8. Expand game support.
9. Optional firmware flashing/updating.
10. Polish, releases and documentation.

## Important references in this repository

Start investigations with:

- `README.md`
- `docs/architecture.md`
- `docs/hardware_esp32.md`
- `docs/frlg_link.md`
- `docs/frlg_gift.md`
- `docs/frlg_rom.md`
- `firmware/esp32/`
- `tools/ldn/esp32_first_contact.py`
- `pokeldn/ldn/`
- `pokeldn/gba/`
- `pokeldn/app/`
- `tests/`

Read the relevant implementation and tests before changing protocol behavior.

## Instructions for coding agents

When working on this branch:

- Treat Android as the only frontend target unless explicitly told otherwise.
- Do not propose a Raspberry Pi frontend as an alternative.
- Do not replace the ESP32 with Android Wi-Fi; the ESP32 is the intended LDN radio.
- Preserve upstream ESP32 firmware compatibility whenever possible.
- Verify assumptions against repository code/tests instead of guessing protocol details.
- Prefer adding Android code over rewriting unrelated upstream code.
- Keep `main` clean; work on `android`.
- Explain architectural deviations in commits/docs.
- Keep generated/build artifacts out of Git.
- Never commit `prod.keys`, private keys, captured credentials, user saves, or other private user data.
- Keep AGPLv3/upstream attribution intact.
- When upstream changes, re-evaluate whether a custom Android implementation can be simplified by new upstream functionality.

## Current project decision

The project previously considered a Raspberry Pi touchscreen appliance. That direction has been dropped. The maintained goal is a direct Android + USB OTG + ESP32 application.
