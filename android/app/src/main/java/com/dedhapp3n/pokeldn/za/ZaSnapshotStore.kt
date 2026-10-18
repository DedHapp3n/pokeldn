package com.dedhapp3n.pokeldn.za

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ZaCapturedPokemon(
    val fingerprint: String, val raw: ByteArray, val summary: ZaPokemonInfo,
    val firstSeen: Long, val lastSeen: Long, val observations: Int,
)

data class ZaSnapshot(
    val id: String, val started: Long, val updated: Long, val finished: Boolean,
    val linkCode: String?, val trainer: ZaTrainer?, val selectionRaw: ByteArray?,
    val pokemon: List<ZaCapturedPokemon>, val previewRecords: Int, val duplicatePreviews: Int,
    val lastMessageId: Int?, val lastDecodeError: String?,
)

/** Private versioned binary snapshot. Each meaningful observation is committed with fsync + rename. */
internal class ZaSnapshotStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun start(linkCode: String? = null): ZaSnapshot {
        val time = now()
        return ZaSnapshot(UUID.randomUUID().toString(), time, time, false, linkCode, null, null, emptyList(), 0, 0, null, null).also(::save)
    }

    @Synchronized fun observe(snapshot: ZaSnapshot, message: ByteArray): ZaSnapshot {
        val id = if (message.size >= 2) (message[0].toInt() and 255) shl 8 or (message[1].toInt() and 255) else null
        val time = now()
        val next = try {
            when (id) {
                0x1400 -> snapshot.copy(trainer = ZaMessages.trainer(message), updated = time, lastMessageId = id, lastDecodeError = null)
                0x1403 -> { ZaMessages.identitySync(message); snapshot.copy(updated = time, lastMessageId = id, lastDecodeError = null) }
                0x0100 -> snapshot.copy(selectionRaw = ZaMessages.selection(message), updated = time, lastMessageId = id, lastDecodeError = null)
                0x0101 -> {
                    val preview = ZaMessages.preview(message)
                    if (!preview.shown) snapshot.copy(updated = time, lastMessageId = id) // a pick is never captured as a preview
                    else {
                        val index = snapshot.pokemon.indexOfFirst { it.fingerprint == preview.fingerprint }
                        val entries = snapshot.pokemon.toMutableList()
                        if (index < 0) entries += ZaCapturedPokemon(preview.fingerprint, preview.raw.copyOf(), preview.pokemon, time, time, 1)
                        else entries[index] = entries[index].copy(lastSeen = time, observations = entries[index].observations + 1)
                        snapshot.copy(updated = time, pokemon = entries, previewRecords = snapshot.previewRecords + 1,
                            duplicatePreviews = snapshot.duplicatePreviews + if (index < 0) 0 else 1,
                            lastMessageId = id, lastDecodeError = null)
                    }
                }
                else -> snapshot
            }
        } catch (error: IllegalArgumentException) {
            snapshot.copy(updated = time, lastMessageId = id, lastDecodeError = error.message)
        }
        if (next !== snapshot) save(next)
        return next
    }

    @Synchronized fun finish(snapshot: ZaSnapshot): ZaSnapshot = snapshot.copy(updated = now(), finished = true).also(::save)

    @Synchronized fun save(snapshot: ZaSnapshot) {
        require(UUID.fromString(snapshot.id).toString() == snapshot.id)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Z-A snapshot directory" }
        val target = File(directory, "${snapshot.id}.zas")
        val temp = File(directory, ".${snapshot.id}.tmp")
        FileOutputStream(temp).use { file ->
            DataOutputStream(file).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION)
                out.writeUTF(snapshot.id); out.writeLong(snapshot.started); out.writeLong(snapshot.updated); out.writeBoolean(snapshot.finished)
                out.optional(snapshot.linkCode)
                out.writeBoolean(snapshot.trainer != null)
                snapshot.trainer?.let { out.writeUTF(it.name); out.writeInt(it.tid); out.writeInt(it.sid); out.writeInt(it.gender); out.writeInt(it.language) }
                out.bytes(snapshot.selectionRaw)
                out.writeInt(snapshot.previewRecords); out.writeInt(snapshot.duplicatePreviews)
                out.writeInt(snapshot.lastMessageId ?: -1); out.optional(snapshot.lastDecodeError)
                out.writeInt(snapshot.pokemon.size)
                snapshot.pokemon.forEach { entry ->
                    out.writeUTF(entry.fingerprint); out.writeLong(entry.firstSeen); out.writeLong(entry.lastSeen); out.writeInt(entry.observations)
                    out.bytes(entry.raw)
                }
                out.flush(); file.fd.sync()
            }
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized fun load(id: String): ZaSnapshot? {
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not()) return null
        val file = File(directory, "$id.zas")
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == VERSION)
                require(input.readUTF() == id)
                val started = input.readLong(); val updated = input.readLong(); val finished = input.readBoolean()
                val code = input.optional()
                val trainer = if (input.readBoolean()) ZaTrainer(input.readUTF(), input.readInt(), input.readInt(), input.readInt(), input.readInt()) else null
                val selection = input.bytes(1211)?.also { require(it.size == 1211) }
                val previews = input.readInt(); val duplicates = input.readInt(); val lastId = input.readInt().takeIf { it >= 0 }; val error = input.optional()
                val count = input.readInt(); require(count in 0..5000)
                val pokemon = (0 until count).map {
                    val fingerprint = input.readUTF(); val first = input.readLong(); val last = input.readLong(); val observations = input.readInt()
                    val raw = input.bytes(344)!!.also { require(it.size == 344) }
                    val parsed = ZaMessages.preview(byteArrayOf(1, 1, 0xb9.toByte(), 3, 0, 0xbc.toByte(), 0x81.toByte(), 0x58, 1) + raw + byteArrayOf(1))
                    require(parsed.fingerprint == fingerprint)
                    ZaCapturedPokemon(fingerprint, raw, parsed.pokemon, first, last, observations)
                }
                ZaSnapshot(id, started, updated, finished, code, trainer, selection, pokemon, previews, duplicates, lastId, error)
            }
        }.getOrNull()
    }

    @Synchronized fun list(): List<ZaSnapshot> = directory.listFiles { file -> file.extension == "zas" }
        ?.mapNotNull { load(it.nameWithoutExtension) }?.sortedByDescending { it.updated }.orEmpty()

    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); if (value != null) writeUTF(value) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
    private fun DataOutputStream.bytes(value: ByteArray?) { writeInt(value?.size ?: -1); if (value != null) write(value) }
    private fun DataInputStream.bytes(max: Int): ByteArray? { val size = readInt(); require(size in -1..max); return if (size < 0) null else ByteArray(size).also { readFully(it) } }

    companion object { private const val MAGIC = 0x5a41534e; private const val VERSION = 1 }
}
