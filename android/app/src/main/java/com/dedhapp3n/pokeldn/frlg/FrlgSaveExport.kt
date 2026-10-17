package com.dedhapp3n.pokeldn.frlg

import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FrlgSaveExportPhase { NOT_AVAILABLE, AVAILABLE, EXPORTING, EXPORTED, CANCELLED, FAILED }

data class FrlgSaveBackupUiState(
    val operation: FrlgOperationState = FrlgOperationState(),
    val bytesReceived: Int = 0,
    val totalBytes: Int = FrlgSave.SAVE_SIZE,
    val cartridge: String? = null,
    val hasWholeSlot: Boolean? = null,
    val save: ByteArray? = null,
    val suggestedFilename: String? = null,
    val exportPhase: FrlgSaveExportPhase = FrlgSaveExportPhase.NOT_AVAILABLE,
    val exportDetail: String? = null,
) {
    val running: Boolean get() = operation.running
    val dataAvailable: Boolean get() = save?.size == FrlgSave.SAVE_SIZE
}

internal object FrlgSaveExport {
    private val filenameTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun suggestedFilename(game: String, language: String, now: Instant, zone: ZoneId): String =
        "${safePart(game)}-${safePart(language)}-${filenameTime.withZone(zone).format(now)}.sav"

    fun write(save: ByteArray, output: OutputStream) {
        require(save.size == FrlgSave.SAVE_SIZE) { "A FireRed or LeafGreen save must be exactly 131072 bytes" }
        output.write(save)
        output.flush()
    }

    private fun safePart(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .ifEmpty { "FRLG" }
}
