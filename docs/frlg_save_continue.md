---
title: Save loading and CONTINUE
parent: FireRed and LeafGreen
nav_order: 8
---

# Save loading and CONTINUE

This page documents the normal FireRed/LeafGreen data flow from flash to the overworld. It is based
on `pret/pokefirered` commit `037335f4c725d7c9aecdac87066f2002b4bd7e14` (`REVISION >= 0xA`),
which is the revision used by the Switch releases. It describes ordinary game behavior only.

Offsets below are offsets in the reconstructed `struct SaveBlock1` or `struct SaveBlock2`, not raw
flash-file offsets. The flash sectors are rotated and identified by sector metadata; `save.c`
reassembles them into the two structs before any field described here is interpreted.

## End-to-end control flow

```text
title screen
  SetSaveBlocksPointers
  LoadGameSave(SAVE_NORMAL)
    GetSaveValidStatus
    CopySaveSlotData                 flash sectors -> SaveBlock1/2/storage
    LoadSerializedGame
      LoadPlayerParty               SaveBlock1 party -> runtime party
      LoadObjectEvents              SaveBlock1 objectEvents -> gObjectEvents
  SetPokemonCryStereo
  CB2_InitMainMenu
    Task_SetWin0BldRegsAndCheckSaveFile
    Task_PrintMainMenuText
  CONTINUE
    TryStartQuestLogPlayback
      quest log present -> Quest Log playback -> CB2_EnterFieldFromQuestLog
      no quest log      -> CB2_ContinueSavedGame
        LoadSaveblockMapHeader
        LoadSaveblockObjEventScripts
        InitMapFromSavedGame
        specialSaveWarpFlags bit 0?
          no  -> CB2_ReturnToField -> CB2_Overworld
          yes -> continueGameWarp -> CB2_LoadMap -> CB2_Overworld
```

`LoadGameSave` validates both redundant save slots, chooses the newest complete slot by its sector
counter, and copies every valid sector into RAM. No gameplay field selects a function during this
phase: sector ids, signatures, checksums and counters control reconstruction. `LoadSerializedGame`
then gives only the saved party and saved live object-event array an immediate runtime copy.

## Phase 1: flash load

| Save data | Offset | Reader | Normal effect | Selects a script? |
|---|---:|---|---|---|
| complete `SaveBlock2` | `SB2 + 0x0000..0x0F23` | `CopySaveSlotData` | Reassembles the block from the selected flash slot. | No; this is a byte copy. |
| complete `SaveBlock1` | `SB1 + 0x0000..0x3D67` | `CopySaveSlotData` | Reassembles the block from the selected flash slot. | No; this is a byte copy. |
| `playerPartyCount` | `SB1 + 0x0034` | `LoadPlayerParty` | Restores `gPlayerPartyCount`. | No. |
| `playerParty[6]` | `SB1 + 0x0038..0x028F` | `LoadPlayerParty` | Restores `gPlayerParty`. | No. |
| `objectEvents[16]` | `SB1 + 0x06A0..0x08DF` | `LoadObjectEvents` | Restores the live field-object states into `gObjectEvents`; they are respawned later. | No direct script selection. |
| `optionsSound` | `SB2 + 0x0015`, bit 0 | `SetPokemonCryStereo` from the title-screen task | Selects mono/stereo cry playback before the menu. | No. |

The PC storage block is loaded at the same time, but it is a separate save structure and is outside
the SaveBlock1/SaveBlock2 field inventory on this page.

## Phase 2: main-menu construction

`CB2_InitMainMenu` builds the menu according to `gSaveFileStatus`. For a usable save it reads these
fields:

| Save data | Offset | Reader | Normal effect | Selects a script? |
|---|---:|---|---|---|
| `optionsWindowFrameType` | `SB2 + 0x0014`, bits 3..7 | `LoadUserFrameToBg` | Selects one of the ROM-resident window-frame graphics. | No. |
| `playerGender` | `SB2 + 0x0008` | `Task_PrintMainMenuText` | Selects the blue or red continue-window accent palette. | No. |
| `playerName[8]` | `SB2 + 0x0000..0x0007` | `PrintPlayerName` | Prints the player name. | No. |
| `playTimeHours` | `SB2 + 0x000E..0x000F` | `PrintPlayTime` | Prints the hours. | No. |
| `playTimeMinutes` | `SB2 + 0x0010` | `PrintPlayTime` | Prints the minutes. | No. |
| Pokédex `nationalMagic` | `SB2 + 0x001B` | `IsNationalPokedexEnabled` | Together with a var and flag, chooses Kanto or National owned count. | No. |
| Pokédex `owned[52]` | `SB2 + 0x0028..0x005B` | `GetNationalPokedexCount` / `GetKantoPokedexCount` | Supplies the caught count printed on CONTINUE. | No. |
| `flags[]`: Pokédex obtained | `SB1 + 0x0FE5`, bit 1 (`FLAG_SYS_POKEDEX_GET`) | `PrintDexCount` / `FlagGet` | Controls whether the Pokédex line is printed. | No. |
| `flags[]`: National Dex | `SB1 + 0x0FE8`, bit 0 (`FLAG_SYS_NATIONAL_DEX`) | `IsNationalPokedexEnabled` | Part of the three-value National Dex validity check. | No. |
| `vars[VAR_NATIONAL_DEX]` | `SB1 + 0x109C` | `IsNationalPokedexEnabled` | Must contain `0x6258` for National mode. | No. |
| `flags[]`: badges 1..8 | `SB1 + 0x0FE4`, bits 0..7 | `PrintBadgeCount` / `FlagGet` | Supplies the badge count. | No. |
| `flags[]`: Mystery Gift enabled | `SB1 + 0x0FE7`, bit 1 (`FLAG_SYS_MYSTERY_GIFT_ENABLED`) | `IsMysteryGiftEnabled` | Adds the MYSTERY GIFT menu row. | No; it changes only the main-menu layout. |

The `MysteryGiftSave` payload at `SB1 + 0x3120..0x348B` is not read while constructing the main
menu and is not read when CONTINUE is selected. Card/news CRC validation happens only after entering
the Mystery Gift UI or another feature that explicitly asks for it.

## Phase 3: selecting CONTINUE and the Quest Log gate

`Task_ExecuteMainMenuSelection` calls `TryStartQuestLogPlayback`. The gate scans the four saved
scenes and counts nonzero `startType` bytes. With no saved scene it goes directly to
`CB2_ContinueSavedGame`; otherwise the game plays the scenes first.

Each `QuestLogScene` is `0x668` bytes. Scene `n` starts at
`SB1 + 0x1300 + n * 0x668`, for `n = 0..3`.

| Scene-relative offset | Field | Reader and time | Normal effect | Selects a script? |
|---:|---|---|---|---|
| `+0x000` | `startType` | `TryStartQuestLogPlayback`, `GetQuestLogStartType`, `QuestLog_AdvancePlayhead` | Zero means unused; `QL_START_NORMAL` restores a standing scene; `QL_START_WARP` enters it through the warp loader. | Selects one of two fixed Quest Log setup paths, not a field script. |
| `+0x001..+0x007` | `mapGroup`, `mapNum`, `warpId`, `x`, `y` | `QLPlayback_SetInitialPlayerPosition` | Chooses the recorded map and initial player coordinates. | Indirectly selects that map's ROM `MapHeader`, including its ROM map-script table. |
| `+0x008..+0x147` | `objectEvents[16]` | `QL_LoadObjects` | Reconstructs the recorded object states used by the scene. | No script pointer is stored here. |
| `+0x148..+0x267` | `flags[NUM_FLAG_BYTES]` | `QL_CopySaveState` | Temporarily restores the scene's flag snapshot for faithful playback. | Indirectly affects ROM map scripts and object visibility that test flags. |
| `+0x268..+0x467` | `vars[VARS_COUNT]` | `QL_CopySaveState` | Temporarily restores the scene's variable snapshot. This includes the compressed trainer-rematch backup and recorded map-layout backup vars. | Indirectly affects conditional ROM map-script tables. |
| `+0x468..+0x567` | `objectEventTemplates[64]` | `QL_LoadObjectsAndTemplates` | Restores recorded object coordinates, elevation and movement type into the full templates. | No script pointer exists in this compact Quest Log template. |
| `+0x568..+0x667` | `script[128]` | `ReadQuestLogScriptFromSav1`, then `QL_TryRunActions` and `QL_LoadEvent` | Replays recorded input, movement, sprite-state changes, waits, scene end and descriptive events. | Yes, but only inside the fixed Quest Log format: the event id indexes ROM tables such as `sQuestLogEventCmdSizes` and `sLoadEventFuncs`. It is not a field-script pointer. |

Quest Log setup also reads the scene-restored `VAR_QUEST_LOG_MON_COUNTS` (`vars` byte offset
`0x104E`) to normalize party/PC counts for the replay and
`VAR_QLBAK_TRAINER_REMATCHES..+3` (`0x1154..0x115B`) to reconstruct rematch state. For a standing
scene, `VAR_QLBAK_MAP_LAYOUT` (`0x115C`) restores `SB1.mapLayoutId`. When playback finishes,
`CB2_EnterFieldFromQuestLog` re-enters the real saved-game flow described below.

During Quest Log map setup the game runs the selected map's ordinary ROM scripts against the
scene's temporary flag/var snapshot. The command stream in `QuestLogScene.script` is decoded as
Quest Log actions and events; it is not handed to the field-script interpreter.

## Phase 4: the saved map and field state

Both direct CONTINUE and return from Quest Log begin with the real save state:

1. `LoadSaveblockMapHeader` uses `location.mapGroup/mapNum` to copy a ROM `MapHeader` and uses
   `mapLayoutId` to select a ROM map layout.
2. `LoadSaveblockObjEventScripts` overwrites every saved full object template's `script` member with
   the corresponding pointer from the selected ROM map header.
3. `Overworld_ResetStateOnContinue` clears transient Safari state and updates ambient-cry/roamer
   state.
4. `InitMapFromSavedGame` constructs the map grid, overlays the saved map view, and runs the ROM
   `MAP_SCRIPT_ON_LOAD` script if the selected map has one.

The path-defining saved fields are:

| Save data | Offset | Reader | Normal effect | Selects a script? |
|---|---:|---|---|---|
| `pos` | `SB1 + 0x0000..0x0003` | `LoadSavedMapView`, camera/object helpers | Restores player coordinates and locates the saved 16-by-16 map-view overlay. | No direct selection. |
| `location` | `SB1 + 0x0004..0x000B` | `LoadSaveblockMapHeader`, object/map helpers | `mapGroup` and `mapNum` select `gMapGroups[group][num]`; `warpId/x/y` are used when a warp path applies. | Yes, indirectly: selecting the ROM `MapHeader` selects its ROM events and `mapScripts` table. |
| `continueGameWarp` | `SB1 + 0x000C..0x0013` | `SetWarpDestinationToContinueGameWarp` | Destination used only when `CONTINUE_GAME_WARP` is set. | Indirectly selects the destination map's ROM scripts. |
| `mapLayoutId` | `SB1 + 0x0032..0x0033` | `GetMapLayout`, `QL_RestoreMapLayoutId` | Selects `gMapLayouts[mapLayoutId - 1]` for the saved map. | No field script; it selects layout data. |
| `weather` | `SB1 + 0x002E` | weather setup reached by `ResumeMap` | Restores saved field weather. | No. |
| `weatherCycleStage` | `SB1 + 0x002F` | `TranslateWeatherNum` on a warp into a cycling-weather map | Selects one of four normal weather-cycle entries. | No. |
| `flashLevel` | `SB1 + 0x0030` | `Overworld_GetFlashLevel` during graphics setup | Restores cave darkness/flash scanline state. | No. |
| `savedMusic` | `SB1 + 0x002C..0x002D` | overworld music selection helpers | Overrides the map's default music when nonzero. | No. |
| `flags[0x120]` | `SB1 + 0x0EE0..0x0FFF` | `FlagGet`, map scripts, object spawning | Supplies story/system state and object hide flags; CONTINUE also clears transient Safari state in RAM. | Indirectly: ROM scripts branch on flags; object templates are shown/hidden by `flagId`. |
| `vars[0x100]` | `SB1 + 0x1000..0x11FF` | `VarGet`, conditional map-script tables | Supplies map scenes and other event state. | Yes, indirectly: `MAP_SCRIPT_ON_FRAME_TABLE` and `MAP_SCRIPT_ON_WARP_INTO_MAP_TABLE` compare vars before choosing a ROM script. |
| `objectEvents[16]` | `SB1 + 0x06A0..0x08DF` | `LoadObjectEvents`, `SpawnObjectEventsOnReturnToField` | Restores active objects, graphics/movement state, coordinates and directions for the no-warp return path. | No saved script pointer; movement type selects fixed ROM movement behavior. |
| `objectEventTemplates[64]` | `SB1 + 0x08E0..0x0EDF` | `LoadSaveblockObjEventScripts`, spawn/update helpers | Preserves template state such as coordinates, graphics, movement, trainer data and hide flag. | The saved `script` member does not select the script on CONTINUE: it is replaced from the ROM map header first. |
| `roamer` | `SB1 + 0x30D0..0x30EB` | `UpdateLocationHistoryForRoamer`, `RoamerMoveToOtherLocationSet` | Advances the roaming Pokémon's normal location state. | No. |
| `mapView[0x100]` | `SB2 + 0x0898..0x0A97` | `SavedMapViewIsEmpty`, `LoadSavedMapView` | Restores the saved 16-by-16 metatile window around `pos`, then clears it in RAM. | No. |
| `playerGender` | `SB2 + 0x0008` | `InitObjectEventsLocal` on a warp load | Selects the player avatar graphics. | No. |
| `specialSaveWarpFlags` | `SB2 + 0x0009` | `UseContinueGameWarp`, `ClearContinueGameWarpStatus` | Bit 0 chooses the alternate Continue-warp branch and is then cleared in RAM. | Indirectly, through the destination map. |
| `encryptionKey` | `SB2 + 0x0F20..0x0F23` | `MoveSaveBlocks_ResetHeap` / `ApplyNewEncryptionKeyToAllEncryptedData` | Re-encrypts money, coins, bag quantities, game stats, Berry Powder and Trainer Tower times after the randomized save-block relocation. | No. |

`MoveSaveBlocks_ResetHeap` mechanically copies complete SaveBlock1/SaveBlock2/storage structs while
moving their RAM bases. That whole-struct copy is not a semantic read or selector. Its subsequent
key rotation does interpret the encrypted-value groups listed above.

### Saved map-view implementation detail

The original, non-`UBFIX` implementation of `SavedMapViewIsEmpty` ORs `0x200` halfwords starting at
`SB2.mapView`, although the declared array contains only `0x100` halfwords. Thus the normal retail
check reads `SB2 + 0x0898..0x0C97`; the latter half overlaps the following link/minigame records and
padding. Its sole normal effect here is deciding whether the declared `mapView` array is treated as
empty. `LoadSavedMapView` copies only the declared `0x100` halfwords.

## Phase 5: the two Continue branches

### Ordinary saved position (`CONTINUE_GAME_WARP` clear)

`CB2_ContinueSavedGame` sets `FieldCB_ShowMapNameOnContinue`, installs `CB1_Overworld`, and enters
`CB2_ReturnToField`. The loader then:

- relocates the save blocks and rotates the encryption key;
- calls `ResumeMap`, which restores field systems and runs the selected map's ROM
  `MAP_SCRIPT_ON_RESUME`;
- respawns the saved `gObjectEvents` with `SpawnObjectEventsOnReturnToField`;
- runs the selected map's ROM `MAP_SCRIPT_ON_RETURN_TO_FIELD`;
- performs the fade/map-name callback and switches to `CB2_Overworld`.

The automatic ROM map-script order is therefore `ON_LOAD -> ON_RESUME -> ON_RETURN_TO_FIELD`.
`MAP_SCRIPT_ON_TRANSITION` and the warp-into-map conditional table do not run on this branch.

### Continue warp (`CONTINUE_GAME_WARP` set)

`UseContinueGameWarp` tests bit 0 of `SB2.specialSaveWarpFlags`. The game clears that bit in RAM,
copies `SB1.continueGameWarp` to the warp destination, calls `WarpIntoMap`, and enters `CB2_LoadMap`.
The destination-map loader replaces object templates from ROM and runs:

```text
MAP_SCRIPT_ON_TRANSITION
MAP_SCRIPT_ON_LOAD
MAP_SCRIPT_ON_RESUME
MAP_SCRIPT_ON_WARP_INTO_MAP_TABLE (only when its saved-var comparison matches)
```

The other `specialSaveWarpFlags` bits do not choose the CONTINUE branch:

| Bit | Name | Normal producer/use | Read by this path? |
|---:|---|---|---|
| 0 | `CONTINUE_GAME_WARP` | Marks `continueGameWarp` for the next CONTINUE. | Yes, by `UseContinueGameWarp`; then cleared. |
| 1 | `POKECENTER_SAVEWARP` | Maintained by `TrySetMapSaveWarpStatus` for Pokémon Center/link maps. | No branch decision in `CB2_ContinueSavedGame`. |
| 2 | `LOBBY_SAVEWARP` | Reserved reload-location status; the FRLG location list is empty. | No. |
| 3 | `UNK_SPECIAL_SAVE_WARP_FLAG_3` | Backed by an empty map list and normally cleared. | No. |
| 7 | `CHAMPION_SAVEWARP` | Set with postgame flags and consulted by Union Room logic. | No. |

The remaining saved warp records are not consulted by this startup path: `dynamicWarp` at
`SB1 + 0x0014..0x001B`, `lastHealLocation` at `0x001C..0x0023`, and `escapeWarp` at
`0x0024..0x002B`. They are used by their corresponding later gameplay transitions. A normal save
operation may have copied `dynamicWarp` into `continueGameWarp` before reset, but CONTINUE reads the
latter record only.

## Automatic map-script selection

All automatic field scripts reached here come from ROM through the selected `MapHeader.mapScripts`:

| Loader call | Script resolver | Saved inputs that affect it |
|---|---|---|
| `InitMapFromSavedGame` / `InitMap` | `RunOnLoadMapScript` -> `MapHeaderRunScriptType(MAP_SCRIPT_ON_LOAD)` | `location` chooses the ROM map; its script reads saved flags/vars. |
| warp loader | `RunOnTransitionMapScript` | `continueGameWarp` chooses the ROM map. |
| `ResumeMap` | `RunOnResumeMapScript` | Selected ROM map plus flags/vars read by that script. |
| no-warp return | `RunOnReturnToFieldMapScript` | Selected ROM map plus flags/vars. |
| object initialization after warp | `TryRunOnWarpIntoMapScript` -> `MapHeaderCheckScriptTable` | Selected ROM map and equality comparisons between saved vars. |
| overworld frames | `TryRunOnFrameMapScript` -> `MapHeaderCheckScriptTable` | Selected ROM map and equality comparisons between saved vars. This begins after the overworld is active, not during flash load. |

The save therefore selects map scripts indirectly through a map id and through their normal
flag/variable conditions. It does not supply a map-script pointer.

## Object events, RAM Script and Mystery Gift

### Object events

The no-warp branch preserves the `ObjectEvent` runtime states loaded from `SB1.objectEvents` and
respawns every active entry. The full saved templates retain mutable object state, but
`LoadSaveblockObjEventScripts` replaces their `script` pointers from the selected ROM map before the
overworld starts. On the warp branch, `LoadObjEventTemplatesFromHeader` replaces whole templates
from ROM. Each full template is `0x18` bytes; its `script` member is at entry offset `+0x10` and its
hide `flagId` at `+0x14`. Object interaction scripts are therefore selected normally by the ROM map
data.

### RAM Script

The record is:

| Save data | Offset |
|---|---:|
| `ramScript.checksum` | `SB1 + 0x361C..0x361F` |
| `ramScript.data.magic` | `SB1 + 0x3620` |
| `mapGroup`, `mapNum`, `objectId` | `SB1 + 0x3621..0x3623` |
| `script[995]` | `SB1 + 0x3624..0x3A06` |

None of these fields is read by `LoadGameSave`, main-menu construction,
`TryStartQuestLogPlayback`, `CB2_ContinueSavedGame`, map initialization or the first overworld
callback. `GetRamScript` is reached later from `GetInteractedObjectEventScript`: only an explicit
object interaction asks whether a valid, checksum-matching RAM Script replaces that object's ROM
script. `GetSavedRamScriptIfValid` belongs to Mystery Gift handling and is likewise outside
CONTINUE.

### Mystery Gift status

The normal boot/CONTINUE path reads only `FLAG_SYS_MYSTERY_GIFT_ENABLED` to decide whether the main
menu contains MYSTERY GIFT. It does not validate or execute `SB1.mysteryGift`, does not inspect a
saved Wonder Card, and does not consult a saved RAM Script. Selecting CONTINUE follows the same
Quest Log/warp/map flow whether a Wonder Card is present or absent.

## Source trail

The relevant `pret/pokefirered` files at the pinned reference commit are:

- `src/title_screen.c`: save load and transition to the main menu;
- `src/save.c`, `src/load_save.c`: slot reconstruction, serialized party/object restoration and
  save-block relocation;
- `src/main_menu.c`: menu shape, continue summary and CONTINUE dispatch;
- `src/quest_log.c`, `src/quest_log_events.c`, `src/quest_log_objects.c`: scene selection and
  playback;
- `src/overworld.c`, `src/fieldmap.c`: continue branches, map setup and field callbacks;
- `src/script.c`: ROM map-script lookup and RAM Script validation;
- `src/event_object_movement.c`, `src/field_control_avatar.c`: saved-object respawn and later object
  interaction;
- `include/global.h`, `include/global.fieldmap.h`, `include/save_location.h`: layouts and flag bits.
