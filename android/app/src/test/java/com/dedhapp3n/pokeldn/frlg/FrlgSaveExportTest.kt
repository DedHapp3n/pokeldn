package com.dedhapp3n.pokeldn.frlg

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrlgSaveExportTest {
    @Test fun `export writes completed save byte for byte`() {
        val save = ByteArray(FrlgSave.SAVE_SIZE) { ((it * 29) xor (it ushr 3)).toByte() }
        val output = ByteArrayOutputStream()
        FrlgSaveExport.write(save, output)
        assertArrayEquals(save, output.toByteArray())
    }

    @Test fun `export rejects partial save`() {
        assertThrows(IllegalArgumentException::class.java) {
            FrlgSaveExport.write(ByteArray(FrlgSave.SAVE_SIZE - 1), ByteArrayOutputStream())
        }
    }

    @Test fun `filename is deterministic and safe`() {
        assertEquals(
            "LeafGreen-German-20261017-213045.sav",
            FrlgSaveExport.suggestedFilename(
                "LeafGreen", "German", Instant.parse("2026-10-17T19:30:45Z"), ZoneId.of("Europe/Berlin"),
            ),
        )
    }

    @Test fun `cancelled export does not change completed protocol result`() {
        val save = ByteArray(FrlgSave.SAVE_SIZE)
        val completed = FrlgSaveBackupUiState(
            operation = FrlgOperationState(FrlgOperationPhase.COMPLETED, "Save backup complete"),
            save = save,
            exportPhase = FrlgSaveExportPhase.AVAILABLE,
        )
        val cancelled = completed.copy(
            exportPhase = FrlgSaveExportPhase.CANCELLED,
            exportDetail = "Export cancelled",
        )
        assertEquals(FrlgOperationPhase.COMPLETED, cancelled.operation.phase)
        assertEquals(true, cancelled.dataAvailable)
        assertArrayEquals(save, cancelled.save)
    }
}
