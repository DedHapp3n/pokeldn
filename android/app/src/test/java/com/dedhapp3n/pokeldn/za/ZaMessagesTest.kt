package com.dedhapp3n.pokeldn.za

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.DataOutputStream
import java.util.UUID

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
        assertArrayEquals(fixture("identity11b"), ZaReference.syncMessage(fixture("identity10")))
        val changed = ZaReference.namedIdentity(fixture("identity10"), "POKELDN")
        assertEquals("POKELDN", ZaMessages.trainer(changed).name)
        assertFalse(ZaReference.syncMessage(changed).contentEquals(fixture("identity11b")))
    }

    @Test fun exactCurrentUpstreamAdvertisements() {
        assertEquals("1068a742ac3a8787ab6066a161f5d5e1", ZaAdvertisementBuilder.linkCodeMask().joinToString("") { "%02x".format(it.toInt() and 255) })
        assertArrayEquals(fixture("advertisement_00000000"), ZaAdvertisementBuilder.buildApplicationData("00000000"))
        assertArrayEquals(fixture("advertisement_12345678"), ZaAdvertisementBuilder.buildApplicationData("12345678"))
        assertEquals(112, ZaAdvertisementBuilder.buildApplicationData("12345678").size)
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
        val roundOne = message.copyOf().also { it[4] = 1 }
        assertEquals(parsed.fingerprint, ZaMessages.preview(roundOne).fingerprint)
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

    @Test fun duplicatePacketsDoNotCauseRepeatedDiskWrites() {
        val folder = Files.createTempDirectory("za-disk-test").toFile()
        try {
            var writes = 0
            val store = ZaSnapshotStore(folder, onDiskWrite = { writes++ })
            var snapshot = store.start()
            assertEquals(1, writes)
            val preview = fixture("preview")
            snapshot = store.observe(snapshot, preview)
            assertEquals(2, writes)
            repeat(100) { snapshot = store.observe(snapshot, preview) }
            assertEquals(2, writes)
            assertEquals(100, snapshot.duplicatePreviews)
            snapshot = store.observe(snapshot, fixture("preview_other"))
            assertEquals(3, writes)
            assertEquals(2, ZaSnapshotStore(folder).load(snapshot.id)!!.pokemon.size)
            store.finish(snapshot)
            assertEquals(4, writes)
        } finally { folder.deleteRecursively() }
    }

    @Test fun unreadablePreviewPersistsAndDoesNotStopLaterCapture() {
        val folder = Files.createTempDirectory("za-unreadable-test").toFile()
        try {
            var time = 2000L
            val store = ZaSnapshotStore(folder) { time++ }
            var snapshot = store.start()
            val unreadable = fixture("preview").also { it[20] = (it[20].toInt() xor 1).toByte() }
            snapshot = store.observe(snapshot, unreadable)
            assertEquals(1, snapshot.unreadablePreviews)
            assertEquals(0, snapshot.pokemon.size)
            assertNotNull(snapshot.lastDecodeError)
            assertArrayEquals(unreadable, snapshot.unreadableRaw)
            assertNotNull(snapshot.lastUnreadableAt)
            snapshot = store.observe(snapshot, fixture("preview"))
            assertEquals(1, snapshot.pokemon.size)
            val restored = ZaSnapshotStore(folder).load(snapshot.id)!!
            assertEquals(1, restored.unreadablePreviews)
            assertArrayEquals(unreadable, restored.unreadableRaw)
            assertEquals(1, restored.pokemon.size)
            assertEquals("Pokémon Legends: Z-A", restored.game)
            assertEquals(2, restored.readerVersion)
        } finally { folder.deleteRecursively() }
    }

    @Test fun versionOneSnapshotStillLoadsAndCanBeUpgraded() {
        val folder = Files.createTempDirectory("za-v1-test").toFile()
        try {
            val id = UUID.randomUUID().toString()
            DataOutputStream(folder.resolve("$id.zas").outputStream()).use { out ->
                out.writeInt(0x5a41534e); out.writeInt(1); out.writeUTF(id)
                out.writeLong(100); out.writeLong(200); out.writeBoolean(true)
                out.writeBoolean(false); out.writeBoolean(false); out.writeInt(-1)
                out.writeInt(0); out.writeInt(0); out.writeInt(-1); out.writeBoolean(false)
                out.writeInt(0)
            }
            val store = ZaSnapshotStore(folder)
            val old = store.load(id)!!
            assertEquals(1, old.readerVersion)
            assertEquals(0, old.unreadablePreviews)
            assertEquals("Pokémon Legends: Z-A", old.game)
            store.save(old)
            assertEquals(1, store.load(id)!!.readerVersion)
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

    @Test fun protocolElevenFrameAndRetransmissionMatchUpstreamShape() {
        val stream = ZaReliableStream(11)
        val opened = stream.open(byteArrayOf(0x14, 0x02, 0xb9.toByte(), 0), 0).single()
        assertEquals("0f0004fff0fff003000000021402b900", stream.frame(opened).toHex())
        val due = stream.poll(500).first { it.retransmitted }
        assertEquals(stream.frame(opened).toHex(), stream.frame(due).let { it.copyOf().also { data -> data[0] = 0x0f }.toHex() })
        assertEquals(1, stream.outstanding)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
