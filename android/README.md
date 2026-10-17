# PokeLDN for Android — public preview

The Android app connects directly to a compatible PokeLDN ESP32 over USB OTG. Android remains the host and the ESP32 remains the Nintendo LDN radio.

## Requirements

- Android 8.0 or newer with USB host/OTG support
- A compatible ESP32 running the matching PokeLDN firmware
- A USB OTG adapter or cable appropriate for the phone and ESP32
- Your own `prod.keys`, imported in **Optionen → Switch Keys**
- A supported retail FireRed or LeafGreen cartridge. Current Android builds support the English, French, German, Italian, Spanish, and Japanese FRLG build codes represented by the upstream address tables.

The app never bundles or downloads `prod.keys`, ROMs, saves, or other private game files. Imported keys stay in app-private storage.

## Basic use

### Walk Through Walls

1. Connect and verify the ESP32 on Home.
2. Open **Games → FireRed / LeafGreen → Walk Through Walls**.
3. Tap **Start**.
4. On the GBA, open Mystery Gift, choose Wonder Cards, then Friend.
5. After installation, hold **R** while moving.

Walk Through Walls is physically verified end to end with German LeafGreen.

### Save Backup

1. Connect and verify the ESP32.
2. Open **Games → FireRed / LeafGreen → Save Backup**.
3. Tap **Start backup**, then enter Mystery Gift on the GBA.
4. The complete 128 KB save is validated and automatically persisted in app-private storage.
5. Use **Save a copy** for a selected document, or configure an external backup folder under **Optionen → Storage**.

Save Backup is physically verified with German LeafGreen. The resulting 131072-byte `.sav` contained a whole valid slot and was parsed successfully by PKHeX.

### Save Restore

1. Open **Games → FireRed / LeafGreen → Save Restore**.
2. Select a `.sav`. A 131072-byte save or a save with the supported 16-byte emulator footer is accepted.
3. Confirm that the app reports a whole valid save slot.
4. Connect and verify the ESP32, then enter Mystery Gift on the matching cartridge.
5. Tap **Restore save** and accept the single confirmation dialog.

Restore validates the cartridge language and build before writing, verifies every written sector, and only reports success after the game loads the restored save. Save Restore is implemented and unit tested, but physical hardware verification is still pending. Use the exact unmodified backup from the same cartridge for the first hardware test.

## Verification status

| Feature | Status |
|---|---|
| FRLG Walk Through Walls | Physically verified: LeafGreen / German |
| FRLG Save Backup | Physically verified: LeafGreen / German |
| FRLG Save Restore | Implemented and unit tested; hardware test pending |
| Other FRLG tools and games | Planned or incomplete |

## Building

From `android/`:

```text
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
