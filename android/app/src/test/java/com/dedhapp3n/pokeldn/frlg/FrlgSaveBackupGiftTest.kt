package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgSaveBackupGiftTest {
    @Test fun `complete backup reaches non-saving close and peer departure completes`() {
        val source = wholeSave()
        val engine = readyEngine()
        sendMessage(engine, 17, gameData("BPGD"))
        drain(engine)
        val messages = deflateMessages(source)
        messages.forEach { sendMessage(engine, 19, it) }
        repeat(32 - messages.size) { sendMessage(engine, 19, ByteArray(4)) }
        drain(engine)

        val copied = engine.snapshot()
        assertEquals(FrlgSaveBackupStage.WAITING_READY_END, copied.stage)
        assertEquals(FrlgSave.SAVE_SIZE, copied.bytesReceived)
        assertArrayEquals(source, copied.save)
        assertTrue(copied.backupSucceeded)
        assertTrue(copied.hasWholeSlot == true)

        sendMessage(engine, 20, ByteArray(1024))
        assertEquals(FrlgSaveBackupStage.CLOSING, engine.snapshot().stage)
        assertTrue(engine.snapshot().nonSavingCloseEntered)
        engine.onPeerLeft()
        assertEquals(FrlgSaveBackupStage.COMPLETED, engine.snapshot().stage)
        assertArrayEquals(source, engine.snapshot().save)
    }

    @Test fun `Android initiated close also preserves successful backup`() {
        val engine = completeToClosing()
        engine.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_CLOSE_LINK, 1)))
        assertTrue(engine.snapshot().nonSavingCloseConfirmed)
        repeat(300) { engine.tick() }
        assertTrue(engine.snapshot().disconnectRequested)
        engine.markDisconnected()
        assertEquals(FrlgSaveBackupStage.COMPLETED, engine.snapshot().stage)
    }

    @Test fun `peer departure before complete image fails and exposes no save`() {
        val engine = readyEngine()
        engine.onPeerLeft()
        assertEquals(FrlgSaveBackupStage.FAILED, engine.snapshot().stage)
        assertFalse(engine.snapshot().backupSucceeded)
        assertNull(engine.snapshot().save)
    }

    @Test fun `complete image with no whole slot is retained with validation warning`() {
        val source = ByteArray(FrlgSave.SAVE_SIZE)
        val engine = readyEngine()
        sendMessage(engine, 17, gameData("BPGD"))
        drain(engine)
        val messages = deflateMessages(source)
        messages.forEach { sendMessage(engine, 19, it) }
        repeat(32 - messages.size) { sendMessage(engine, 19, ByteArray(4)) }
        drain(engine)

        assertEquals(FrlgSaveBackupStage.WAITING_READY_END, engine.snapshot().stage)
        assertEquals(false, engine.snapshot().hasWholeSlot)
        assertArrayEquals(source, engine.snapshot().save)
        assertTrue(engine.snapshot().detail!!.contains("without saving"))
    }

    @Test fun `truncated token response fails cleanly`() {
        val engine = readyEngine()
        sendMessage(engine, 17, gameData("BPGD"))
        drain(engine)
        sendMessage(engine, 19, byteArrayOf(0))
        assertEquals(FrlgSaveBackupStage.FAILED, engine.snapshot().stage)
        assertNull(engine.snapshot().save)
    }

    @Test fun `unsupported build is refused before any save bytes`() {
        val engine = readyEngine()
        sendMessage(engine, 17, gameData("XXXX"))
        assertEquals(FrlgSaveBackupStage.FAILED, engine.snapshot().stage)
        assertEquals(0, engine.snapshot().bytesReceived)
        assertNull(engine.snapshot().save)
    }

    @Test fun `backup completion script is the upstream failure result that cannot save`() {
        assertArrayEquals(
            hex("02000000150000000c000000000000001400000000000000010000000e000000"),
            FrlgSaveBackupGiftEngine.nonSavingEndScript(),
        )
        assertEquals(14, le32(FrlgSaveBackupGiftEngine.nonSavingEndScript(), 28))
        assertArrayEquals(
            hex("d3e3e9e600e7d5ead900ebd5e700d7e3e4ddd9d800e8e3fee8dcd900d7e3e1e4e9e8d9e6adff"),
            FrlgSaveBackupGiftEngine.backedUpMessage(),
        )
    }

    private fun readyEngine(): FrlgSaveBackupGiftEngine = FrlgSaveBackupGiftEngine(
        FrlgCartridge("LeafGreen", "German", 0x4005, 5),
    ).also { engine ->
        repeat(20) { engine.receive(ByteArray(14)) }
        drain(engine)
        assertEquals(FrlgSaveBackupStage.READING_CARTRIDGE, engine.snapshot().stage)
    }

    private fun completeToClosing(): FrlgSaveBackupGiftEngine {
        val engine = readyEngine()
        val source = wholeSave()
        sendMessage(engine, 17, gameData("BPGD"))
        drain(engine)
        val messages = deflateMessages(source)
        messages.forEach { sendMessage(engine, 19, it) }
        repeat(32 - messages.size) { sendMessage(engine, 19, ByteArray(4)) }
        drain(engine)
        sendMessage(engine, 20, ByteArray(1024))
        return engine
    }

    private fun drain(engine: FrlgSaveBackupGiftEngine) = repeat(5_000) { engine.tick() }

    private fun wholeSave(): ByteArray {
        val save = ByteArray(FrlgSave.SAVE_SIZE)
        repeat(2) { slot ->
            repeat(14) { id ->
                val at = (slot * 14 + id) * FrlgSave.SECTOR_SIZE
                put16(save, at + FrlgSave.SECTOR_ID_OFFSET, id)
                put16(save, at + FrlgSave.SECTOR_CHECKSUM_OFFSET, 0)
                put32(save, at + FrlgSave.SECTOR_SIGNATURE_OFFSET, FrlgSave.SECTOR_SIGNATURE.toInt())
                put32(save, at + FrlgSave.SECTOR_COUNTER_OFFSET, slot + 10)
            }
        }
        return save
    }

    private fun gameData(code: String) = ByteArray(0x64).also {
        put32(it, 0, 0x101); put16(it, 4, 1); put32(it, 8, 1); put16(it, 12, 1)
        put32(it, 16, if (code.startsWith("BPG")) 2 else 1)
        code.encodeToByteArray().copyInto(it, 0x5c)
    }

    private fun deflateMessages(data: ByteArray): List<ByteArray> {
        val tokens = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            var run = 1
            while (i + run < data.size && run < 130 && data[i + run] == data[i]) run++
            if (run >= 3) {
                tokens += byteArrayOf((0x80 + run - 3).toByte(), data[i])
                i += run
            } else {
                val start = i
                while (i < data.size && i - start < 128) {
                    if (i > start && i + 2 < data.size && data[i] == data[i + 1] && data[i] == data[i + 2]) break
                    i++
                }
                tokens += byteArrayOf((i - start - 1).toByte()) + data.copyOfRange(start, i)
            }
        }
        val messages = mutableListOf<ByteArray>()
        var current = mutableListOf<Byte>()
        tokens.forEach { token ->
            if (current.size + token.size > 900) {
                messages += current.toByteArray()
                current = mutableListOf()
            }
            current.addAll(token.toList())
        }
        if (current.isNotEmpty()) messages += current.toByteArray()
        require(messages.size <= 32)
        return messages
    }

    private fun sendMessage(engine: FrlgSaveBackupGiftEngine, ident: Int, payload: ByteArray) {
        val header = ByteArray(6).also {
            put16(it, 0, ident); put16(it, 2, crc(payload)); put16(it, 4, payload.size)
        }
        (listOf(header) + payload.asList().chunked(252).map { it.toByteArray() }).forEach { block ->
            val padded = block.copyOf(((block.size + 11) / 12) * 12)
            val count = padded.size / 12
            engine.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, count, 0x81)))
            repeat(count) { index ->
                engine.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(index,
                    padded.copyOfRange(index * 12, index * 12 + 12))))
            }
        }
    }

    private fun crc(data: ByteArray): Int {
        var value = 0x1121
        data.forEach { b ->
            value = value xor (b.toInt() and 255)
            repeat(8) { value = if (value and 1 != 0) (value ushr 1) xor 0x8408 else value ushr 1 }
        }
        return value.inv() and 0xffff
    }
    private fun put16(data: ByteArray, at: Int, value: Int) { data[at] = value.toByte(); data[at + 1] = (value ushr 8).toByte() }
    private fun put32(data: ByteArray, at: Int, value: Int) = repeat(4) { data[at + it] = (value ushr (8 * it)).toByte() }
    private fun le32(data: ByteArray, at: Int) = (0 until 4).fold(0) { v, i -> v or ((data[at + i].toInt() and 255) shl (8 * i)) }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
