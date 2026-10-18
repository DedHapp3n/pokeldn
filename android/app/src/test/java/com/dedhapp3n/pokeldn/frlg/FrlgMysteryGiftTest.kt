package com.dedhapp3n.pokeldn.frlg

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgMysteryGiftTest {
    @Test fun `post barrier client startup waits twenty idle frames without duplicate standby echoes`() {
        val engine = FrlgMysteryGiftEngine(
            FrlgCartridge("LeafGreen", "German", 0x4005, 5),
            startupStandbyCount = 7,
            echoStartupStandby = false,
        )
        assertTrue(engine.tick().all { it == 0 })
        repeat(19) { engine.receive(ByteArray(14)) }
        assertEquals(FrlgGiftStage.WAITING_CLIENT, engine.snapshot().stage)
        engine.receive(ByteArray(14))
        assertEquals(FrlgGiftStage.READING_CARTRIDGE, engine.snapshot().stage)
    }

    @Test fun `upstream noclip fixture is selected for exact cartridge`() {
        val payload = WalkThroughWallsPayloads.forGameCode("BPGD")!!
        assertEquals(452, payload.size)
        assertEquals("163e9323551a1e67c122d8adb4a12e6a576aacc4f7d679542a6ddb8414d96665",
            MessageDigest.getInstance("SHA-256").digest(payload).hex())
        assertEquals(0x100, WalkThroughWallsPreset.ACTIVATION_MASK)
        val fixtures = mapOf(
            "BPRE" to "85b9b4e7c147f650bce1f06919ef21a19c3b83afb0757dd625b0c5a9ae8b51eb",
            "BPRF" to "31240cda85f2be33e639fa46ab71f537e01c0230b6643e07d6503d60d6a69fb0",
            "BPRJ" to "7255e1d7aca52f1071d11d028e08988407243226ab9db58fc06e858d2827f939",
        )
        fixtures.forEach { (code, hash) ->
            assertEquals(hash, MessageDigest.getInstance("SHA-256")
                .digest(WalkThroughWallsPayloads.forGameCode(code)!!).hex())
        }
    }

    @Test fun `PokeHax screen is a bounded shared RAM and VRAM payload`() {
        val payload = PokeHaxScreenPayload.image()
        assertEquals(760, payload.size)
        assertEquals("9e486375a487c9297abc27f8b4529fc75f09f7d22cce4fd99551e7324e57485f",
            MessageDigest.getInstance("SHA-256").digest(payload).hex())
        assertTrue(payload.size <= 1024)
        assertTrue(payload.contentEquals(PokeHaxScreenPayload.image()))
    }

    @Test fun `PokeHax screen uses the existing run buffer Mystery Gift flow`() {
        val engine = preparedEngine(FrlgMysteryGiftOperation.POKEHAX_SCREEN)
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("PokeHax Screen"))
    }

    @Test fun `successful close completes when console leaves first`() {
        val engine = closingSuccessfulEngine()
        val closing = engine.snapshot()
        assertTrue(closing.installSucceeded)
        assertTrue(closing.closePhaseEntered)
        assertTrue(!closing.closeHandshakeConfirmed)

        engine.onPeerLeft()

        assertEquals(FrlgGiftStage.COMPLETED, engine.snapshot().stage)
        assertEquals("Walk Through Walls installed", engine.snapshot().detail)
    }

    @Test fun `successful close completes when Android initiates disconnect`() {
        val engine = closingSuccessfulEngine()
        engine.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_CLOSE_LINK, 1)))
        assertTrue(engine.snapshot().closeHandshakeConfirmed)
        repeat(300) { engine.tick() }
        assertTrue(engine.snapshot().disconnectRequested)
        engine.markDisconnected()
        assertEquals(FrlgGiftStage.COMPLETED, engine.snapshot().stage)
    }

    @Test fun `console departure before install success fails`() {
        val engine = preparedEngine()
        engine.onPeerLeft()
        assertEquals(FrlgGiftStage.FAILED, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("disconnected"))
    }

    @Test fun `console departure during early RFU client stage fails`() {
        val engine = FrlgMysteryGiftEngine(FrlgCartridge("LeafGreen", "German", 0x4005, 5))
        engine.onPeerLeft()
        assertEquals(FrlgGiftStage.FAILED, engine.snapshot().stage)
        assertTrue(!engine.snapshot().installSucceeded)
    }

    @Test fun `completed result survives later participant cleanup`() {
        val engine = closingSuccessfulEngine()
        engine.onPeerLeft()
        engine.onPeerLeft()
        assertEquals(FrlgGiftStage.COMPLETED, engine.snapshot().stage)
        assertEquals("Walk Through Walls installed", engine.snapshot().detail)
    }

    @Test fun `unsupported cartridge is rejected before boost bytes are queued`() {
        val engine = FrlgMysteryGiftEngine(FrlgCartridge("LeafGreen", "German", 0x4005, 5))
        repeat(20) { engine.receive(ByteArray(14)) }
        repeat(300) { engine.tick() }
        val data = ByteArray(0x64).also {
            put32(it, 0, 0x101); put16(it, 4, 1); put32(it, 8, 1); put16(it, 12, 1)
            put32(it, 16, 2); "XXXX".encodeToByteArray().copyInto(it, 0x5c)
        }
        sendMessage(engine, 17, data)
        assertEquals(FrlgGiftStage.FAILED, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("Unsupported"))
    }

    @Test fun `console install refusal closes and remains failed`() {
        val engine = preparedEngine()
        sendMessage(engine, 19, byteArrayOf(0xd0.toByte(), 0xba.toByte(), 0xd0.toByte(), 0xba.toByte()))
        repeat(1_000) { engine.tick() }
        sendMessage(engine, 20, ByteArray(1024))
        engine.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_CLOSE_LINK, 1)))
        repeat(300) { engine.tick() }
        engine.markDisconnected()
        assertEquals(FrlgGiftStage.FAILED, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("refused"))
    }

    private fun preparedEngine(
        operation: FrlgMysteryGiftOperation = FrlgMysteryGiftOperation.WALK_THROUGH_WALLS,
    ): FrlgMysteryGiftEngine {
        val engine = FrlgMysteryGiftEngine(FrlgCartridge("LeafGreen", "German", 0x4005, 5), operation)
        repeat(20) { engine.receive(ByteArray(14)) }
        repeat(300) { engine.tick() }
        val data = ByteArray(0x64).also {
            put32(it, 0, 0x101); put16(it, 4, 1); put32(it, 8, 1); put16(it, 12, 1)
            put32(it, 16, 2); "BPGD".encodeToByteArray().copyInto(it, 0x5c)
        }
        sendMessage(engine, 17, data)
        repeat(4_000) { engine.tick() }
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
        return engine
    }

    private fun closingSuccessfulEngine(): FrlgMysteryGiftEngine = preparedEngine().also { engine ->
        sendMessage(engine, 19, byteArrayOf(1, 0, 0, 0))
        repeat(1_000) { engine.tick() }
        sendMessage(engine, 20, ByteArray(1024))
        assertEquals(FrlgGiftStage.CLOSING, engine.snapshot().stage)
    }

    private fun sendMessage(engine: FrlgMysteryGiftEngine, ident: Int, payload: ByteArray) {
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
    private fun put32(data: ByteArray, at: Int, value: Int) { repeat(4) { data[at + it] = (value ushr (8 * it)).toByte() } }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
