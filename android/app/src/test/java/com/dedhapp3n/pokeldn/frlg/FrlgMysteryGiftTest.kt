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
        val transfer = PokeHaxScreenPayload.forGameCode("BPGD")!!
        assertEquals(listOf(1024, 1024, 1024, 912), transfer.leads.map { it.size })
        assertEquals(12, transfer.payload.size)
        assertTrue((transfer.leads + transfer.payload).all { it.size <= 1024 })
        val same = PokeHaxScreenPayload.forGameCode("BPGD")!!
        assertTrue(transfer.leads.zip(same.leads).all { (a, b) -> a.contentEquals(b) })
        assertTrue(transfer.leads.zip(PokeHaxScreenPayload.forGameCode("BPGE")!!.leads)
            .any { (a, b) -> !a.contentEquals(b) })
        assertTrue(transfer.leads.zip(PokeHaxScreenPayload.forGameCode("BPGJ")!!.leads)
            .any { (a, b) -> !a.contentEquals(b) })
    }

    @Test fun `persistent PokeHax installer is bounded and build specific`() {
        val german = PokeHaxScreenPayload.installerForGameCode("BPGD")!!
        val english = PokeHaxScreenPayload.installerForGameCode("BPGE")!!
        val japanese = PokeHaxScreenPayload.installerForGameCode("BPGJ")!!
        assertEquals(984, german.payload.size)
        assertTrue((german.leads + german.payload).all { it.size <= 1024 })
        assertTrue(!german.payload.contentEquals(english.payload))
        assertTrue(!german.payload.contentEquals(japanese.payload))
    }

    @Test fun `PokeHax screen uses the existing run buffer Mystery Gift flow`() {
        val engine = preparedEngine(FrlgMysteryGiftOperation.POKEHAX_SCREEN)
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("PokeHax Lab"))
    }

    @Test fun `persistent PokeHax installer uses the existing run buffer Mystery Gift flow`() {
        val engine = preparedEngine(FrlgMysteryGiftOperation.POKEHAX_INSTALL)
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("persistent install"))
    }

    @Test fun `custom gift selects the detected variant and uses the run buffer payload sequence`() {
        val selectedLead = byteArrayOf(0x11, 0x12, 0x13, 0x14)
        val selectedPayload = byteArrayOf(0x21, 0x22, 0x23, 0x24)
        val gift = FrlgGiftFile("Custom", mapOf(
            "BPGD" to FrlgBufferTransfer(listOf(selectedLead), selectedPayload),
            "BPRD" to FrlgBufferTransfer(listOf(byteArrayOf(0x31)), byteArrayOf(0x41)),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)
        sendGameData(engine, "BPGD")

        val messages = collectMessagesUntilWaitingResult(engine)

        assertEquals(listOf(16, 25, 25), messages.map { it.first })
        assertTrue(messages[1].second.contentEquals(selectedLead))
        assertTrue(messages[2].second.contentEquals(selectedPayload))
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
    }

    @Test fun `ack per lead mode confirms every chunk before sending the next one`() {
        val ack = 0x4b43414fL
        val leads = listOf(bytes4(0x11), bytes4(0x22), bytes4(0x33))
        val finalPayload = bytes4(0x44)
        val gift = FrlgGiftFile("Acked", mapOf(
            "BPGD" to FrlgBufferTransfer(
                leads, finalPayload, FrlgBufferExpectation.Status(0x12345678),
                ackEachLead = true, leadExpectation = ack,
            ),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)
        sendGameData(engine, "BPGD")

        val clientScripts = mutableListOf<ByteArray>()
        leads.forEachIndexed { index, lead ->
            val client = nextOutboundMessage(engine)
            assertEquals(16, client.first)
            clientScripts += client.second
            val payload = nextOutboundMessage(engine)
            assertEquals(25, payload.first)
            assertTrue(payload.second.contentEquals(lead))
            assertEquals(FrlgGiftStage.SENDING_BOOST, engine.snapshot().stage)
            sendMessage(engine, 19, u32(ack))
            if (index < leads.lastIndex) {
                assertEquals(FrlgGiftStage.SENDING_BOOST, engine.snapshot().stage)
            }
        }
        val finalClient = nextOutboundMessage(engine)
        assertEquals(16, finalClient.first)
        clientScripts += finalClient.second
        val final = nextOutboundMessage(engine)
        assertEquals(25, final.first)
        assertTrue(final.second.contentEquals(finalPayload))
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)

        val core = listOf(2 to 25, 21 to 0, 14 to 0, 3 to 0)
        assertEquals(4, clientScripts.size)
        clientScripts.forEach { script ->
            assertEquals(core, decodeClientScript(script).take(4))
        }
    }

    @Test fun `bad second lead ack stops before third lead and final payload`() {
        val ack = 0x11223344L
        val leads = listOf(bytes4(0x11), bytes4(0x22), bytes4(0x33))
        val finalPayload = bytes4(0x44)
        val gift = FrlgGiftFile("Acked", mapOf(
            "BPGD" to FrlgBufferTransfer(
                leads, finalPayload, ackEachLead = true, leadExpectation = ack,
            ),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)
        sendGameData(engine, "BPGD")

        nextOutboundMessage(engine)
        assertTrue(nextOutboundMessage(engine).second.contentEquals(leads[0]))
        sendMessage(engine, 19, u32(ack))
        nextOutboundMessage(engine)
        assertTrue(nextOutboundMessage(engine).second.contentEquals(leads[1]))
        sendMessage(engine, 19, u32(ack xor 1L))

        val failureScript = nextOutboundMessage(engine)
        assertEquals(16, failureScript.first)
        val failureMessage = nextOutboundMessage(engine)
        assertEquals(21, failureMessage.first)
        assertTrue(engine.snapshot().detail!!.contains("lead 2"))
        assertTrue(engine.snapshot().detail!!.contains("mismatch"))
        assertTrue(drainOutboundMessages(engine, 2_000).none { it.first == 25 })
    }

    @Test fun `all lead acks still leave final buffer expectation in charge`() {
        val ack = 0x55667788L
        val gift = FrlgGiftFile("Acked", mapOf(
            "BPGD" to FrlgBufferTransfer(
                listOf(bytes4(0x11), bytes4(0x22)), bytes4(0x44),
                FrlgBufferExpectation.Status(0x12345678),
                ackEachLead = true, leadExpectation = ack,
            ),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)
        sendGameData(engine, "BPGD")

        repeat(2) {
            nextOutboundMessage(engine)
            nextOutboundMessage(engine)
            sendMessage(engine, 19, u32(ack))
        }
        nextOutboundMessage(engine)
        nextOutboundMessage(engine)
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)

        sendMessage(engine, 19, u32(0x12345679))
        nextOutboundMessage(engine)
        nextOutboundMessage(engine)
        assertTrue(!engine.snapshot().installSucceeded)
        assertTrue(engine.snapshot().detail!!.contains("expected 0x12345678"))
    }

    @Test fun `custom gift without detected cartridge variant fails before payload transmission`() {
        val gift = FrlgGiftFile("Custom", mapOf(
            "BPRD" to FrlgBufferTransfer(emptyList(), byteArrayOf(1, 2, 3, 4)),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)

        sendGameData(engine, "BPGD")

        assertEquals(FrlgGiftStage.FAILED, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("no variant for detected cartridge BPGD"))
        assertTrue(repeatTicks(engine, 2_000).none { it.first == 25 })
    }

    @Test fun `custom integer expectation match succeeds`() {
        val result = completeCustomResult(FrlgBufferExpectation.Status(0x12345678), 0x12345678)
        assertEquals(FrlgGiftStage.COMPLETED, result.stage)
        assertTrue(result.installSucceeded)
    }

    @Test fun `custom integer expectation mismatch fails after clean close`() {
        val result = completeCustomResult(FrlgBufferExpectation.Status(0x12345678), 0x12345679)
        assertEquals(FrlgGiftStage.FAILED, result.stage)
        assertTrue(!result.installSucceeded)
        assertTrue(result.closePhaseEntered)
        assertTrue(result.detail!!.contains("expected 0x12345678"))
        assertTrue(result.detail!!.contains("received 0x12345679"))
    }

    @Test fun `trainer id expectation exact match succeeds`() {
        val result = completeCustomResult(
            FrlgBufferExpectation.TrainerId, 0x89abcdef, trainerId = 0x89abcdef,
            playerName = byteArrayOf(0x41, 0x42, 0xff.toByte()),
        )
        assertEquals(FrlgGiftStage.COMPLETED, result.stage)
        assertTrue(result.installSucceeded)
    }

    @Test fun `trainer id expectation mismatch fails`() {
        val result = completeCustomResult(
            FrlgBufferExpectation.TrainerId, 0x89abcdee, trainerId = 0x89abcdef,
            playerName = byteArrayOf(0x41, 0xff.toByte()),
        )
        assertEquals(FrlgGiftStage.FAILED, result.stage)
        assertTrue(result.detail!!.contains("expected 0x89ABCDEF"))
        assertTrue(result.detail!!.contains("received 0x89ABCDEE"))
    }

    @Test fun `seven character name ignores low trainer id byte`() {
        val result = completeCustomResult(
            FrlgBufferExpectation.TrainerId, 0x12345699, trainerId = 0x12345644,
            playerName = byteArrayOf(1, 2, 3, 4, 5, 6, 7),
        )
        assertEquals(FrlgGiftStage.COMPLETED, result.stage)
        assertTrue(result.installSucceeded)
    }

    @Test fun `custom gift without expectation keeps normal successful response behavior`() {
        val result = completeCustomResult(null, 0xdeadbeef)
        assertEquals(FrlgGiftStage.COMPLETED, result.stage)
        assertTrue(result.installSucceeded)
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

    private fun engineWaitingForGameData(
        operation: FrlgMysteryGiftOperation,
        gift: FrlgGiftFile,
    ): FrlgMysteryGiftEngine {
        val engine = FrlgMysteryGiftEngine(
            FrlgCartridge("LeafGreen", "German", 0x4005, 5), operation, gift,
        )
        repeat(20) { engine.receive(ByteArray(14)) }
        repeat(300) { engine.tick() }
        assertEquals(FrlgGiftStage.READING_CARTRIDGE, engine.snapshot().stage)
        return engine
    }

    private fun sendGameData(
        engine: FrlgMysteryGiftEngine,
        code: String,
        trainerId: Long = 0,
        playerName: ByteArray = byteArrayOf(0xff.toByte()),
    ) {
        val data = ByteArray(0x64).also {
            put32(it, 0, 0x101); put16(it, 4, 1); put32(it, 8, 1); put16(it, 12, 1)
            put32(it, 16, if (code.startsWith("BPR")) 1 else 2)
            playerName.copyOf(7).copyInto(it, 0x45)
            put32(it, 0x4c, trainerId.toInt())
            code.encodeToByteArray().copyInto(it, 0x5c)
        }
        sendMessage(engine, 17, data)
    }

    private fun completeCustomResult(
        expectation: FrlgBufferExpectation?,
        returned: Long,
        trainerId: Long = 0,
        playerName: ByteArray = byteArrayOf(0xff.toByte()),
    ): FrlgGiftSnapshot {
        val gift = FrlgGiftFile("Custom", mapOf(
            "BPGD" to FrlgBufferTransfer(emptyList(), byteArrayOf(1, 2, 3, 4), expectation),
        ))
        val engine = engineWaitingForGameData(FrlgMysteryGiftOperation.CUSTOM_PATCH, gift)
        sendGameData(engine, "BPGD", trainerId, playerName)
        repeat(4_000) { engine.tick() }
        assertEquals(FrlgGiftStage.WAITING_RESULT, engine.snapshot().stage)
        sendMessage(engine, 19, ByteArray(4).also { put32(it, 0, returned.toInt()) })
        repeat(1_000) { engine.tick() }
        sendMessage(engine, 20, ByteArray(1024))
        assertEquals(FrlgGiftStage.CLOSING, engine.snapshot().stage)
        engine.onPeerLeft()
        return engine.snapshot()
    }

    private fun collectMessagesUntilWaitingResult(engine: FrlgMysteryGiftEngine): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        val receiver = RfuBlockReceiver()
        var ident: Int? = null
        var size = 0
        val payload = ArrayList<Byte>()
        repeat(8_000) {
            receiver.receive(FrlgRfu.serialize(engine.tick()))?.let { block ->
                if (ident == null) {
                    ident = le16(block)
                    size = le16(block, 4).let { if (it == 0) 1024 else it }
                    payload.clear()
                } else {
                    payload.addAll(block.take(minOf(252, size - payload.size)))
                    if (payload.size == size) {
                        result += requireNotNull(ident) to payload.toByteArray()
                        ident = null
                    }
                }
            }
            if (engine.snapshot().stage == FrlgGiftStage.WAITING_RESULT && ident == null) return result
        }
        throw AssertionError("Custom transfer did not reach the result stage")
    }

    private fun nextOutboundMessage(engine: FrlgMysteryGiftEngine): Pair<Int, ByteArray> {
        val messages = drainOutboundMessages(engine, 8_000, stopAfterFirst = true)
        return messages.firstOrNull() ?: throw AssertionError("No complete outbound Mystery Gift message")
    }

    private fun drainOutboundMessages(
        engine: FrlgMysteryGiftEngine,
        count: Int,
        stopAfterFirst: Boolean = false,
    ): List<Pair<Int, ByteArray>> {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        val receiver = RfuBlockReceiver()
        var ident: Int? = null
        var size = 0
        val payload = ArrayList<Byte>()
        repeat(count) {
            receiver.receive(FrlgRfu.serialize(engine.tick()))?.let { block ->
                if (ident == null) {
                    ident = le16(block)
                    size = le16(block, 4).let { if (it == 0) 1024 else it }
                    payload.clear()
                } else {
                    payload.addAll(block.take(minOf(252, size - payload.size)))
                    if (payload.size == size) {
                        messages += requireNotNull(ident) to payload.toByteArray()
                        ident = null
                        if (stopAfterFirst) {
                            // The RFU sender repeats the final 12-byte fragment up to three times.
                            // Do not inject a synthetic console ACK until those repeats finish and
                            // the engine's afterSend callback has armed expect(MG_LINKID_RESPONSE).
                            repeat(4) { engine.tick() }
                            return messages
                        }
                    }
                }
            }
        }
        return messages
    }

    private fun decodeClientScript(script: ByteArray): List<Pair<Int, Int>> =
        script.asList().chunked(8).filter { it.size == 8 }.map { chunk ->
            val bytes = chunk.toByteArray()
            le32(bytes, 0) to le32(bytes, 4)
        }

    private fun u32(value: Long) = ByteArray(4).also { put32(it, 0, value.toInt()) }
    private fun bytes4(value: Int) = byteArrayOf(value.toByte(), 0, 0, 0)

    private fun repeatTicks(engine: FrlgMysteryGiftEngine, count: Int): List<Pair<Int, ByteArray>> {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        val receiver = RfuBlockReceiver()
        repeat(count) {
            receiver.receive(FrlgRfu.serialize(engine.tick()))?.let { block ->
                if (block.size >= 6) messages += le16(block) to block
            }
        }
        return messages
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
    private fun le16(data: ByteArray, at: Int = 0) =
        (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    private fun le32(data: ByteArray, at: Int = 0) =
        (0 until 4).fold(0) { value, index ->
            value or ((data[at + index].toInt() and 255) shl (8 * index))
        }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
