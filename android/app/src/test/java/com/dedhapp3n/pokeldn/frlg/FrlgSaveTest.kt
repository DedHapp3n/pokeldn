package com.dedhapp3n.pokeldn.frlg

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgSaveTest {
    @Test fun `normalizes exact flash and emulator footer only`() {
        val exact = ByteArray(FrlgSave.SAVE_SIZE) { (it * 17).toByte() }
        assertArrayEquals(exact, FrlgSave.normalize(exact))
        assertArrayEquals(exact, FrlgSave.normalize(exact + ByteArray(16) { 0x55 }))
        listOf(0, FrlgSave.SAVE_SIZE - 1, FrlgSave.SAVE_SIZE + 1, FrlgSave.SAVE_SIZE + 15, FrlgSave.SAVE_SIZE + 17)
            .forEach { size -> assertThrows(FrlgSave.SaveException::class.java) { FrlgSave.normalize(ByteArray(size)) } }
    }

    @Test fun `sector checksum uses upstream wrapping little endian sum`() {
        val raw = byteArrayOf(
            0x78, 0x56, 0x34, 0x12, 0xef.toByte(), 0xcd.toByte(), 0xab.toByte(), 0x90.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 1, 0, 0, 0,
        )
        assertEquals(0xc747, FrlgSave.sectorChecksum(raw, raw.size))
    }

    @Test fun `selects newest whole slot and rejects a damaged checksum`() {
        val save = syntheticSave(oldCounter = 50, newCounter = 51, FrlgSave.Layout.LATIN)
        val summary = FrlgSave.describe(save)
        assertTrue(summary.sound)
        assertEquals(1, summary.newest!!.index)
        assertEquals(51L, summary.newest!!.counter)

        val broken = save.copyOf()
        broken[14 * FrlgSave.SECTOR_SIZE + 10] = (broken[14 * FrlgSave.SECTOR_SIZE + 10].toInt() xor 0x5a).toByte()
        val damaged = FrlgSave.describe(broken)
        assertFalse(damaged.slots[1].sound)
        assertEquals(0, damaged.newest!!.index)
    }

    @Test fun `newest slot comparison matches game counter wrap behavior`() {
        val wrapped = FrlgSave.describe(syntheticSave(0xffffffffL, 0, FrlgSave.Layout.LATIN))
        assertEquals(1, wrapped.newest!!.index)
        assertEquals(0L, wrapped.newest!!.counter)
    }

    @Test fun `recognizes Latin and Japanese sector layouts`() {
        val latin = FrlgSave.describe(syntheticSave(8, 9, FrlgSave.Layout.LATIN))
        val japanese = FrlgSave.describe(syntheticSave(8, 9, FrlgSave.Layout.JAPANESE))
        assertEquals(setOf(FrlgSave.Layout.LATIN), latin.newest!!.layouts)
        assertFalse(latin.japanese)
        assertEquals(setOf(FrlgSave.Layout.JAPANESE), japanese.newest!!.layouts)
        assertTrue(japanese.japanese)
    }

    @Test fun `inflates literal and run tokens and rejects overflow or truncation`() {
        val out = ByteArray(12)
        val at = FrlgSave.inflate(byteArrayOf(2, 1, 2, 3, 0x82.toByte(), 9, 0, 7), out, 0)
        assertEquals(9, at)
        assertArrayEquals(byteArrayOf(1, 2, 3, 9, 9, 9, 9, 9, 7), out.copyOf(9))
        assertThrows(FrlgSave.SaveException::class.java) {
            FrlgSave.inflate(byteArrayOf(0x80.toByte()), ByteArray(10), 0)
        }
        assertThrows(FrlgSave.SaveException::class.java) {
            FrlgSave.inflate(byteArrayOf(0x87.toByte(), 1), ByteArray(9), 0)
        }
    }

    @Test fun `backup payload patches exact upstream addresses for every supported cartridge`() {
        val queues = mapOf(
            "BPRE" to 0x03005e62, "BPGE" to 0x03005e62,
            "BPRF" to 0x03005db2, "BPGF" to 0x03005db2,
            "BPRD" to 0x03005db2, "BPGD" to 0x03005db2,
            "BPRI" to 0x03005db2, "BPGI" to 0x03005db2,
            "BPRS" to 0x03005db2, "BPGS" to 0x03005db2,
            "BPRJ" to 0x03005df2, "BPGJ" to 0x03005df2,
        )
        assertEquals(queues.keys, FrlgSaveBackupPayload.supportedGameCodes)
        val canonical = FrlgSaveBackupPayload.build("BPRE")
        assertEquals(564, canonical.size)
        queues.forEach { (code, queue) ->
            val payload = FrlgSaveBackupPayload.build(code, first = 0x12345)
            assertEquals(0x12345, le32(payload, 4))
            assertEquals(queue, le32(payload, 8))
            assertArrayEquals(canonical.copyOfRange(0, 4), payload.copyOfRange(0, 4))
            assertArrayEquals(canonical.copyOfRange(12, canonical.size), payload.copyOfRange(12, payload.size))
        }
        assertEquals("c45f3e841c63879d033f36ad3ce33317e64d40fdb71f67ffdda5d1c245d53bf9",
            sha256(FrlgSaveBackupPayload.build("BPRF")))
    }

    companion object {
        internal fun syntheticSave(oldCounter: Long, newCounter: Long, layout: FrlgSave.Layout): ByteArray {
            val image = ByteArray(FrlgSave.SAVE_SIZE) { 0xff.toByte() }
            listOf(oldCounter, newCounter).forEachIndexed { slot, counter ->
                val rotate = if (slot == 0) 5 else 9
                repeat(FrlgSave.SLOT_SECTORS) { position ->
                    val id = (position + FrlgSave.SLOT_SECTORS - rotate) % FrlgSave.SLOT_SECTORS
                    val raw = ByteArray(FrlgSave.SECTOR_SIZE)
                    val size = FrlgSave.chunkSize(layout, id)
                    repeat(size) { i -> raw[i] = ((i * 37 + id * 53 + slot * 19) and 0xff).toByte() }
                    // The Japanese id-4 checksum stops at 3776. A nonzero unused tail makes the
                    // otherwise-identical Latin 3816-byte layout distinguishable, as upstream tests do.
                    if (layout == FrlgSave.Layout.JAPANESE && id == 4) raw[3776] = 1
                    put16(raw, FrlgSave.SECTOR_ID_OFFSET, id)
                    put16(raw, FrlgSave.SECTOR_CHECKSUM_OFFSET, FrlgSave.sectorChecksum(raw, size))
                    put32(raw, FrlgSave.SECTOR_SIGNATURE_OFFSET, FrlgSave.SECTOR_SIGNATURE)
                    put32(raw, FrlgSave.SECTOR_COUNTER_OFFSET, counter)
                    raw.copyInto(image, (slot * FrlgSave.SLOT_SECTORS + position) * FrlgSave.SECTOR_SIZE)
                }
            }
            repeat(4) { extra ->
                val at = (28 + extra) * FrlgSave.SECTOR_SIZE
                repeat(512) { i -> image[at + i] = ((extra * 71 + i * 11) and 0xff).toByte() }
            }
            return image
        }

        private fun put16(data: ByteArray, at: Int, value: Int) {
            data[at] = value.toByte(); data[at + 1] = (value ushr 8).toByte()
        }
        private fun put32(data: ByteArray, at: Int, value: Long) = repeat(4) { i -> data[at + i] = (value ushr (8 * i)).toByte() }
        private fun le32(data: ByteArray, at: Int) = (0 until 4).fold(0) { v, i -> v or ((data[at + i].toInt() and 0xff) shl (8 * i)) }
        private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
