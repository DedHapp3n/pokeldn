package com.dedhapp3n.pokeldn.za

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ZaMessagesTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/za/$name.bin")!!.use { it.readBytes() }

    @Test fun referenceTrainerAndSync() {
        val trainer = ZaMessages.trainer(fixture("identity10"))
        assertEquals("Player", trainer.name)
        assertEquals(5071, trainer.tid)
        assertEquals(14217, trainer.sid)
        assertEquals("Female", trainer.genderLabel)
        assertEquals("English", trainer.languageLabel)
        assertEquals(5071, trainer.asTrainerProfile()!!.tid)
        assertEquals(0x8f30fb69L, ZaMessages.identitySync(fixture("identity11b")))
    }

    @Test fun upstreamGeneratedPreviewAndMarker() {
        val message = fixture("preview")
        val parsed = ZaMessages.preview(message)
        assertTrue(parsed.shown)
        assertEquals(716, parsed.pokemon.species)
        assertEquals(72, parsed.pokemon.level)
        assertEquals("Player", parsed.pokemon.ot)
        assertEquals(12345, parsed.pokemon.tid)
        assertEquals(2791, parsed.pokemon.sid)
        assertArrayEquals(message.copyOfRange(9, 353), parsed.raw)
        val pick = message.copyOf().also { it[it.lastIndex] = 0 }
        assertFalse(ZaMessages.preview(pick).shown)
        assertEquals(parsed.fingerprint, ZaMessages.preview(pick).fingerprint)
    }

    @Test fun incrementalDedupAndOfflineReload() {
        val folder = Files.createTempDirectory("za-snapshot-test").toFile()
        try {
            var tick = 1000L
            val store = ZaSnapshotStore(folder) { tick++ }
            var snapshot = store.start("12345678")
            snapshot = store.observe(snapshot, fixture("identity10"))
            snapshot = store.observe(snapshot, fixture("selection"))
            val preview = fixture("preview")
            snapshot = store.observe(snapshot, preview)
            assertEquals(1, ZaSnapshotStore(folder).load(snapshot.id)!!.pokemon.size)
            snapshot = store.observe(snapshot, preview)
            assertEquals(1, snapshot.pokemon.size)
            assertEquals(1, snapshot.duplicatePreviews)
            assertEquals(2, snapshot.pokemon.single().observations)
            val pick = preview.copyOf().also { it[it.lastIndex] = 0 }
            snapshot = store.observe(snapshot, pick)
            assertEquals(1, snapshot.pokemon.size)
            val restored = ZaSnapshotStore(folder).load(snapshot.id)!!
            assertEquals("Player", restored.trainer!!.name)
            assertArrayEquals(fixture("selection"), restored.selectionRaw)
            assertArrayEquals(preview.copyOfRange(9, 353), restored.pokemon.single().raw)
            assertEquals(1, ZaSnapshotStore(folder).list().size)
            assertTrue(store.finish(snapshot).finished)
        } finally { folder.deleteRecursively() }
    }

    @Test fun materiallyDifferentRecordStaysDistinct() {
        // The fixture's encrypted record cannot be edited without invalidating the checksum.
        // A separate upstream-built record is generated with a different species and PID.
        val first = ZaMessages.preview(fixture("preview"))
        val other = ZaMessages.preview(fixture("preview_other"))
        assertNotEquals(first.fingerprint, other.fingerprint)
        assertNotEquals(first.pokemon.species, other.pokemon.species)
    }

    @Test fun readOnlyPolicyRejectsTradeProgression() {
        ZaReadOnlyPolicy.requireSafeApplicationMessage(10, fixture("identity10"))
        ZaReadOnlyPolicy.requireSafeApplicationMessage(10, fixture("selection"))
        ZaReadOnlyPolicy.requireSafeApplicationMessage(10, fixture("preview"))
        for (message in listOf(
            byteArrayOf(1, 2, 0xb9.toByte(), 1, 0),
            byteArrayOf(1, 4, 0xb9.toByte(), 1, 0),
            byteArrayOf(2, 0, 0xb9.toByte(), 1, 3),
            fixture("preview").also { it[it.lastIndex] = 0 },
        )) {
            assertThrows(IllegalArgumentException::class.java) { ZaReadOnlyPolicy.requireSafeApplicationMessage(10, message) }
            assertTrue(ZaReadOnlyPolicy.shouldCloseForInbound(10, message))
        }
    }
}
