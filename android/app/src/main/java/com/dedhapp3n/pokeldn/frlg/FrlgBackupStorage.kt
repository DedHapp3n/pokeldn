package com.dedhapp3n.pokeldn.frlg

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.Properties

data class FrlgCompletedBackup(
    val save: ByteArray,
    val game: String,
    val language: String,
    val gameCode: String?,
    val hasWholeSlot: Boolean,
)

internal data class FrlgStoredBackup(
    val filename: String,
    val game: String,
    val language: String,
    val gameCode: String?,
    val hasWholeSlot: Boolean,
    val savedAtMillis: Long,
    val save: ByteArray,
    val externalFilename: String? = null,
    val externalWarning: String? = null,
)

internal fun interface FrlgExternalBackupDirectory {
    /** Writes without replacing an existing document and returns the name actually used. */
    fun writeUnique(preferredFilename: String, save: ByteArray): String
}

/** App-owned durable storage. The internal copy is committed before an optional SAF copy is tried. */
internal class FrlgBackupStore(
    private val directory: File,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val externalDirectory: () -> FrlgExternalBackupDirectory? = { null },
) {
    fun persist(backup: FrlgCompletedBackup): FrlgStoredBackup {
        require(backup.save.size == FrlgSave.SAVE_SIZE)
        directory.mkdirs()
        check(directory.isDirectory) { "Could not create private backup directory" }
        val instant = now()
        val preferred = FrlgSaveExport.suggestedFilename(backup.game, backup.language, instant, zone)
        val filename = uniqueFilename(preferred) { File(directory, it).exists() }
        val target = File(directory, filename)
        atomicWrite(target, backup.save)
        val properties = Properties().apply {
            setProperty("filename", filename)
            setProperty("game", backup.game)
            setProperty("language", backup.language)
            backup.gameCode?.let { setProperty("gameCode", it) }
            setProperty("hasWholeSlot", backup.hasWholeSlot.toString())
            setProperty("savedAtMillis", instant.toEpochMilli().toString())
        }
        atomicProperties(File(directory, "$filename.properties"), properties)

        var externalName: String? = null
        var externalWarning: String? = null
        try {
            externalName = externalDirectory()?.writeUnique(preferred, backup.save.copyOf())
        } catch (error: Exception) {
            externalWarning = error.message ?: "Could not write the configured backup folder"
        }
        return FrlgStoredBackup(filename, backup.game, backup.language, backup.gameCode,
            backup.hasWholeSlot, instant.toEpochMilli(), backup.save.copyOf(), externalName, externalWarning)
    }

    fun latest(): FrlgStoredBackup? = directory.listFiles { file -> file.name.endsWith(".sav.properties") }
        ?.mapNotNull(::readMetadata)
        ?.maxByOrNull { it.savedAtMillis }

    private fun readMetadata(metadata: File): FrlgStoredBackup? {
        return try {
            val properties = Properties().also { value -> metadata.inputStream().use(value::load) }
            val filename = properties.getProperty("filename") ?: return null
            val save = File(directory, filename).readBytes()
            if (save.size != FrlgSave.SAVE_SIZE) return null
            FrlgStoredBackup(
                filename = filename,
                game = properties.getProperty("game", "FRLG"),
                language = properties.getProperty("language", "Unknown"),
                gameCode = properties.getProperty("gameCode"),
                hasWholeSlot = properties.getProperty("hasWholeSlot", "false").toBoolean(),
                savedAtMillis = properties.getProperty("savedAtMillis", metadata.lastModified().toString()).toLong(),
                save = save,
            )
        } catch (_: Exception) { null }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(directory, ".${target.name}.tmp")
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        check(temp.renameTo(target)) { "Could not commit private backup" }
    }

    private fun atomicProperties(target: File, properties: Properties) {
        val temp = File(directory, ".${target.name}.tmp")
        FileOutputStream(temp).use { output -> properties.store(output, "PokeLDN FRLG backup"); output.fd.sync() }
        check(temp.renameTo(target)) { "Could not commit backup metadata" }
    }

    companion object {
        internal fun uniqueFilename(preferred: String, exists: (String) -> Boolean): String {
            if (!exists(preferred)) return preferred
            val dot = preferred.lastIndexOf('.')
            val stem = if (dot >= 0) preferred.substring(0, dot) else preferred
            val extension = if (dot >= 0) preferred.substring(dot) else ""
            var suffix = 2
            while (exists("$stem-$suffix$extension")) suffix++
            return "$stem-$suffix$extension"
        }
    }
}

internal interface FrlgStringPreference {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

internal class FrlgBackupFolderPreference(private val preference: FrlgStringPreference) {
    fun uri(): String? = preference.get(KEY)
    fun setUri(uri: String?) = preference.put(KEY, uri)
    companion object { private const val KEY = "frlg_backup_tree_uri" }
}
