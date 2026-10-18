# Legends: Z-A full-save capture research

Research baseline: PokeLDN `8fd4f26084fc1e986c9fcae2f0839580d5565495`, upstream
`Decryptu/pokeldn` `43f5d5edc2ae2b303729c5f1dc9101e8a47f771f`, and PKHeX
`641c26ba100225f3605e5a44b61b3b16e15a3774`. The upstream and PKHeX heads were
checked again on 2026-10-18; PKHeX still pointed at the pinned commit.

This document separates observed behavior from ideas that still require a physical capture. It does
not claim that a full save can never be transferred by any Z-A mode.

## Confirmed

### What the verified trade connection carries

The implemented Local Wireless session and the reverse-engineering notes in `docs/za.md` account for
the complete set of 20 distinct game payloads in the reference trade capture. The application IDs are:

| ID | Size | Role |
|---|---:|---|
| `0x1400` | 106 | player identity |
| `0x1403` | 9 | synchronized identity checksum |
| `0x0100` | 1211 | selection-screen record |
| `0x0101` | 354 | trade preview or pick, including one 344-byte Pokemon record |
| `0x0102`, `0x0104` | 5 | trade state |
| `0x0200` | 5 | exchange worker state |

`0x1402` is also understood as the key-value channel's DeleteAllValues command. Protocol 10 is
Reliable and protocol 11 is Broadcast Reliable. The selection and preview handlers copy fixed-size
trade/session structures. No observed payload has the size, chunk metadata, offset, total length, or
block identifiers needed to reconstruct a multi-megabyte save.

The identity is populated from trainer data held by the game, and the offered Pokemon originates in
save storage. That provenance does not make either network structure a reference to the complete save.
The handlers documented in `docs/za.md` consume the serialized values directly and expose no follow-up
read request.

The current PokeLDN upstream implements the same trade session. Its host dispatches decoded PIA
messages to the known Reliable streams and its game logic handles the same identity, selection,
preview, and trade-state payloads. The upstream README only marks console-code and save read/write as
verified for FireRed/LeafGreen; it does not advertise a Z-A save-transfer implementation.

Searches of the repository, upstream implementation, and the documented Z-A call sites for save,
backup, export, serialization, SCBlock, storage, box, transfer, synchronization, and player-data paths
did not identify a message constructor plus network call chain for requesting save blocks. Save-related
code reached from the trade flow handles the received Pokemon after an exchange; that is not a read-only
full-save transport.

### PKHeX raw save format

At the pinned/current PKHeX revision, `SaveUtil.IsG9ZA` accepts these exact byte lengths:

* `0x2F3284` (3,093,124 bytes)
* `0x2F3289` (3,093,129 bytes)
* `0x309FA6` (3,186,598 bytes)
* `0x309FB3` (3,186,611 bytes)

It additionally calls `SwishCrypto.GetIsHashValid`. That function calculates SHA-256 over PKHeX's
64-byte intro constant, every input byte except the final 32, and its 64-byte outro constant. The
digest must equal the final 32 bytes. `SAV9ZA(Memory<byte>)` then decrypts the accepted raw input with
`SwishCrypto.Decrypt`; `GetFinalData()` returns the encrypted form through `SwishCrypto.Encrypt`.
Therefore a future PKHeX test file must contain the original complete encrypted blob, unchanged.

Android's isolated `ZaRawSaveValidator` implements the same size and hash checks and rejects uniform
all-zero or all-`FF` data. Unit tests build independent synthetic hash vectors for all four sizes and
cover wrong size, damaged payload, damaged final hash, and uniform data. The validator does not claim
to parse or decrypt SCBlocks.

## Likely

The verified trade mode appears designed to exchange identity, selection UI state, and one Pokemon at
a time. A complete save is roughly 2,500 times larger than its 1211-byte largest known payload, and no
assembler metadata has appeared in the reference traffic. A full save is therefore unlikely to be
available through the currently exercised trade command table without another command set or game mode.

This is evidence about the observed path, not proof about every Local Wireless feature. A different
mode, downloadable content, game update, or command table could expose additional data.

## Rejected hypotheses

* Concatenating `0x0100` selection records or `0x0101` previews cannot produce a save. They are fixed
  session records, contain only a small projection of save-backed data, and have no save-file framing.
* A valid-size file assembled by padding captured records would not be a dump. It would fail the
  SwishCrypto hash and would invent bytes not received from the console.
* Decrypted SCBlocks are not the normal input expected by PKHeX's `SAV9ZA` constructor.
* Serializer, storage, or save-related symbol names alone do not establish a network path. No matching
  request constructor, command registration, sender, and receive handler chain was found.

## Unknown

* Whether another Z-A Local Wireless mode registers a separate command table with save-sized data.
* Whether future game versions add game-layer messages beyond the reference capture.
* Whether an untraced computed call reaches a read-only transfer service.
* Whether large messages are segmented above or below the game layer in a mode not yet captured.

## Passive measurement added

The Android host now observes fully reassembled application deliveries on protocols 10 and 11. It does
not alter acknowledgements, timing, selection sends, or safe termination. Known messages continue down
the verified protocol-10 snapshot path. Unknown IDs are recorded with:

* protocol and Reliable/Broadcast channel;
* first delivered sequence and flags;
* first receive order and first/last wall-clock timestamp;
* sender IP and station constant ID;
* payload length, ID, and complete raw payload.

Reliable already removes retransmissions of the same wire sequence. Identical unknown payloads delivered
on new sequences are retained as separate ordered observations, so semantic repeats remain visible. Diagnostics
shows compact counts and provides `COPY UNKNOWN Z-A MESSAGES` for the full data. No experimental packet
is transmitted and no save candidate is exported.

## Next physical experiments

1. Install the debug APK and use the normal verified `READ FROM GAME` flow. Do not confirm a trade.
2. Exercise cursor movement, cancel, reconnect, and any other read-only screens available before a
   trade commits.
3. In Diagnostics, copy `Z-A TRACE`. If the unknown count is nonzero, also copy
   `UNKNOWN Z-A MESSAGES` immediately after the session.
4. Compare unknown IDs and payloads across sessions. Prioritize payloads over 1211 bytes, monotonic
   sequences, repeated block-like identifiers, explicit offset/length pairs, or totals near one of the
   four PKHeX sizes.
5. Reverse engineer any candidate's command registration, constructor, sender, and receive handler
   before sending a response or request.
6. Only after a complete, gap-free candidate exists, retain its original encrypted bytes and pass it
   through `ZaRawSaveValidator`. Offer it as a PKHeX file only when size and hash both pass.

No chunk assembler or `TRY FULL SAVE DUMP` control is included because current evidence provides no
chunk format or safe request to drive one. Adding either now would imply a capability the protocol does
not establish.
