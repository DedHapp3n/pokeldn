package com.dedhapp3n.pokeldn.frlg

import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FrlgBackupStorageTest {
    @Test fun `completed backup persists internally and survives repository recreation`() {
        val directory = Files.createTempDirectory("frlg-backup").toFile()
        val save = sampleSave()
        val first = store(directory).persist(completed(save))
        val reloaded = store(directory).latest()!!
        assertEquals(first.filename, reloaded.filename)
        assertArrayEquals(save, reloaded.save)
        assertEquals("LeafGreen", reloaded.game)
        assertEquals("German", reloaded.language)
    }

    @Test fun `external backup receives exact bytes after internal commit`() {
        val directory = Files.createTempDirectory("frlg-backup").toFile()
        var external: ByteArray? = null
        val store = FrlgBackupStore(directory, NOW, ZONE) {
            FrlgExternalBackupDirectory { name, bytes -> external = bytes.copyOf(); name }
        }
        val save = sampleSave()
        val result = store.persist(completed(save))
        assertArrayEquals(save, external)
        assertArrayEquals(save, directory.resolve(result.filename).readBytes())
    }

    @Test fun `filename collision creates suffix without overwrite`() {
        val directory = Files.createTempDirectory("frlg-backup").toFile()
        val store = store(directory)
        val first = store.persist(completed(sampleSave(1)))
        val second = store.persist(completed(sampleSave(2)))
        assertEquals("LeafGreen-German-20261017-213045.sav", first.filename)
        assertEquals("LeafGreen-German-20261017-213045-2.sav", second.filename)
        assertArrayEquals(sampleSave(1), directory.resolve(first.filename).readBytes())
        assertArrayEquals(sampleSave(2), directory.resolve(second.filename).readBytes())
    }

    @Test fun `automatic external collision preserves both exact saves`() {
        val directory = Files.createTempDirectory("frlg-backup").toFile()
        val documents = linkedMapOf<String, ByteArray>()
        val external = FrlgExternalBackupDirectory { preferred, bytes ->
            val name = FrlgBackupStore.uniqueFilename(preferred, documents::containsKey)
            documents[name] = bytes.copyOf()
            name
        }
        val store = FrlgBackupStore(directory, NOW, ZONE) { external }
        val first = store.persist(completed(sampleSave(3)))
        val second = store.persist(completed(sampleSave(4)))
        assertEquals("LeafGreen-German-20261017-213045.sav", first.externalFilename)
        assertEquals("LeafGreen-German-20261017-213045-2.sav", second.externalFilename)
        assertArrayEquals(sampleSave(3), documents[first.externalFilename])
        assertArrayEquals(sampleSave(4), documents[second.externalFilename])
    }

    @Test fun `external failure never removes internal backup`() {
        val directory = Files.createTempDirectory("frlg-backup").toFile()
        val result = FrlgBackupStore(directory, NOW, ZONE) {
            FrlgExternalBackupDirectory { _, _ -> error("SAF unavailable") }
        }.persist(completed(sampleSave()))
        assertTrue(directory.resolve(result.filename).isFile)
        assertTrue(result.externalWarning!!.contains("SAF unavailable"))
    }

    @Test fun `tree URI preference survives recreation and can be cleared`() {
        val values = mutableMapOf<String, String>()
        val backend = object : FrlgStringPreference {
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        }
        FrlgBackupFolderPreference(backend).setUri("content://tree/backups")
        assertEquals("content://tree/backups", FrlgBackupFolderPreference(backend).uri())
        FrlgBackupFolderPreference(backend).setUri(null)
        assertNull(FrlgBackupFolderPreference(backend).uri())
    }

    @Test fun `completed UI data survives transport failure without resetting progress`() {
        val save = sampleSave()
        val state = FrlgSaveBackupUiState(bytesReceived = save.size, save = save,
            operation = FrlgOperationState(FrlgOperationPhase.CLOSING_LINK))
            .preserveAfterTransportFailure("ESP32 disconnected")
        assertEquals(FrlgOperationPhase.COMPLETED, state.operation.phase)
        assertEquals(FrlgSave.SAVE_SIZE, state.bytesReceived)
        assertArrayEquals(save, state.save)
    }

    @Test fun `completed UI data survives cleanup failure`() {
        val save = sampleSave()
        val state = FrlgSaveBackupUiState(
            bytesReceived = save.size,
            save = save,
            operation = FrlgOperationState(FrlgOperationPhase.RETURNING_TO_READY),
        ).preserveAfterTransportFailure("adapter cleanup failed")
        assertEquals(FrlgOperationPhase.COMPLETED, state.operation.phase)
        assertArrayEquals(save, state.save)
    }

    @Test fun `incomplete UI data remains a hard failure`() {
        val state = FrlgSaveBackupUiState(bytesReceived = 1024)
            .preserveAfterTransportFailure("ESP32 disconnected")
        assertEquals(FrlgOperationPhase.FAILED, state.operation.phase)
        assertEquals(1024, state.bytesReceived)
    }

    @Test fun `persisted backup cannot become overall failed during cleanup`() {
        val save = sampleSave()
        val state = FrlgSaveBackupUiState(
            bytesReceived = save.size,
            save = save,
            internalFilename = "backup.sav",
            operation = FrlgOperationState(FrlgOperationPhase.RETURNING_TO_READY),
        ).finishAfterPersistence("Adapter cleanup failed", FrlgSaveCloseResult.CLEANUP_WARNING)
        assertEquals(FrlgOperationPhase.COMPLETED, state.operation.phase)
        assertEquals(FrlgSaveCloseResult.CLEANUP_WARNING, state.closeResult)
        assertArrayEquals(save, state.save)
    }

    @Test fun `every completed terminal path rejects pending connection result`() {
        val state = FrlgSaveBackupUiState(
            bytesReceived = FrlgSave.SAVE_SIZE,
            save = sampleSave(),
            internalFilename = "backup.sav",
        )
        FrlgSaveCloseResult.entries.filterNot { it == FrlgSaveCloseResult.PENDING }.forEach { result ->
            assertEquals(result, state.finishAfterPersistence("done", result).closeResult)
        }
        assertThrows(IllegalArgumentException::class.java) {
            state.finishAfterPersistence("done", FrlgSaveCloseResult.PENDING)
        }
    }

    private fun store(directory: java.io.File) = FrlgBackupStore(directory, NOW, ZONE)
    private fun completed(save: ByteArray) = FrlgCompletedBackup(save, "LeafGreen", "German", "BPGD", true)
    private fun sampleSave(seed: Int = 7) = ByteArray(FrlgSave.SAVE_SIZE) { (it * 31 + seed).toByte() }
    companion object {
        private val NOW = { Instant.parse("2026-10-17T19:30:45Z") }
        private val ZONE = ZoneId.of("Europe/Berlin")
    }
}
