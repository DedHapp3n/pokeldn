package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgSaveRestoreGiftTest {
    @Test fun `queued outbound sector blocks time out as send stall`() {
        val fixture = writingFixture()
        val current = fixture.engine.snapshot().transferDiagnostics.last()
        assertTrue(current.blocksRemaining > 0)
        assertFalse(current.senderActive)
        fixture.now = 100
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.FAILED, fixture.engine.snapshot().stage)
        assertTrue(fixture.engine.snapshot().detail!!.contains("stalled while sending sector 1"))
        assertEquals("timeout-send", fixture.engine.snapshot().transferDiagnostics.last().result)
    }

    @Test fun `active RFU block sender time out is still a send stall`() {
        val fixture = writingFixture()
        repeat(100) {
            if (!fixture.engine.snapshot().transferDiagnostics.last().senderActive) fixture.engine.tick()
        }
        assertTrue(fixture.engine.snapshot().transferDiagnostics.last().senderActive)
        fixture.now = 100
        fixture.engine.checkLiveness()
        assertEquals("timeout-send", fixture.engine.snapshot().transferDiagnostics.last().result)
    }

    @Test fun `response timeout begins after every outbound block was sent`() {
        val fixture = writingFixture()
        repeat(20_000) {
            if (!fixture.engine.snapshot().transferDiagnostics.last().waitingForResponse) fixture.engine.tick()
        }
        val waiting = fixture.engine.snapshot().transferDiagnostics.last()
        assertTrue(waiting.waitingForResponse)
        assertEquals(0, waiting.blocksRemaining)
        fixture.now = 100
        fixture.engine.checkLiveness()
        assertTrue(fixture.engine.snapshot().detail!!.contains("timed out waiting for sector 1"))
        assertEquals("timeout-readback", fixture.engine.snapshot().transferDiagnostics.last().result)
    }

    @Test fun `sender progress resets deadline and idle checks do not`() {
        val fixture = writingFixture()
        repeat(100) {
            if (!fixture.engine.snapshot().transferDiagnostics.last().senderActive) fixture.engine.tick()
        }
        fixture.now = 90
        fixture.engine.tick()
        fixture.now = 189
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.WRITING, fixture.engine.snapshot().stage)
        fixture.now = 190
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.FAILED, fixture.engine.snapshot().stage)
    }

    @Test fun `repeated inbound traffic cannot hide a saturated outbound window`() {
        val fixture = writingFixture()
        fixture.engine.updateTransportCounters(6, 6, 1, 1, 1, 1, true, 300, 1, 300)
        repeat(9) { index ->
            fixture.now = ((index + 1) * 10).toLong()
            fixture.engine.updateTransportCounters(
                6, 6, index + 2L, index + 2L, index + 2L, index + 2L,
                true, 300 + fixture.now, 1, 300,
            )
            fixture.engine.checkLiveness()
        }
        assertEquals(FrlgSaveRestoreStage.WRITING, fixture.engine.snapshot().stage)
        fixture.now = 100
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.FAILED, fixture.engine.snapshot().stage)
        assertEquals("timeout-send", fixture.engine.snapshot().transferDiagnostics.last().result)
    }

    @Test fun `decreasing reliable outstanding resets the send watchdog`() {
        val fixture = writingFixture()
        fixture.engine.updateTransportCounters(6, 6, 1, 1, 1, 1, true, 300, 1, 300)
        fixture.now = 90
        fixture.engine.updateTransportCounters(5, 6, 1, 1, 1, 1, true, 390, 1, 300)
        fixture.now = 189
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.WRITING, fixture.engine.snapshot().stage)
        fixture.now = 190
        fixture.engine.checkLiveness()
        assertEquals(FrlgSaveRestoreStage.FAILED, fixture.engine.snapshot().stage)
    }

    @Test fun `restore diagnostics retain quiet and reliable window state`() {
        val fixture = writingFixture()
        fixture.engine.updateTransportCounters(6, 8, 12, 34, 56, 78, true, 4_321, 3, 4_000)
        val item = fixture.engine.snapshot().transferDiagnostics.last()
        assertEquals(6, item.reliableOutstanding)
        assertEquals(8, item.reliableInflight)
        assertEquals(12L, item.reliableReceived)
        assertEquals(34L, item.reliableSent)
        assertTrue(item.consoleQuiet)
        assertEquals(4_321L, item.quietDurationMillis)
        assertEquals(3L, item.quietRetransmitsSent)
        assertEquals(4_000L, item.lastQuietRetransmitMillis)
    }

    @Test fun `restore diagnostic message and block indexes never exceed totals`() {
        val fixture = writingFixture()
        repeat(20_000) {
            val item = fixture.engine.snapshot().transferDiagnostics.last()
            assertTrue("message ${item.currentMgMessageIndex}/${item.mgMessageCount}", item.currentMgMessageIndex <= item.mgMessageCount)
            assertTrue("block ${item.currentBlockIndex}/${item.blockCount}", item.currentBlockIndex <= item.blockCount)
            if (!item.waitingForResponse) fixture.engine.tick()
        }
        val final = fixture.engine.snapshot().transferDiagnostics.last()
        assertEquals(final.mgMessageCount, final.currentMgMessageIndex)
        assertEquals(final.blockCount, final.currentBlockIndex)
    }

    private fun writingFixture(): Fixture {
        val fixture = Fixture()
        repeat(20) { fixture.engine.receive(ByteArray(14)) }
        repeat(10_000) { fixture.engine.tick() }
        sendMessage(fixture.engine, FrlgSaveRestoreEngine.GAME_DATA, gameData())
        repeat(10_000) { fixture.engine.tick() }
        sendMessage(fixture.engine, FrlgSaveRestoreEngine.RESPONSE, footers())
        assertEquals(FrlgSaveRestoreStage.WRITING, fixture.engine.snapshot().stage)
        return fixture
    }

    private class Fixture {
        var now = 0L
        val engine = FrlgSaveRestoreGiftEngine(
            FrlgCartridge("LeafGreen", "German", 0x4005, 5),
            FrlgSaveTest.syntheticSave(8, 9, FrlgSave.Layout.LATIN),
            echoStartupStandby = false,
            responseTimeoutMillis = 100,
            clockMillis = { now },
        )
    }

    private fun gameData() = ByteArray(0x64).also {
        put32(it, 0, 0x101); put32(it, 4, 1); put32(it, 8, 1); put32(it, 12, 1); put32(it, 16, 2)
        "BPGD".encodeToByteArray().copyInto(it, 0x5c)
    }

    private fun footers(): ByteArray {
        val save = FrlgSaveTest.syntheticSave(100, 101, FrlgSave.Layout.LATIN)
        return ByteArray(28 * 12).also { output ->
            repeat(28) { index ->
                val footer = FrlgSave.footer(save, index)
                put16(output, index * 12, footer.id)
                put16(output, index * 12 + 2, footer.checksum)
                put32(output, index * 12 + 4, footer.signature)
                put32(output, index * 12 + 8, footer.counter)
            }
        }
    }

    private fun sendMessage(engine: FrlgSaveRestoreGiftEngine, ident: Int, payload: ByteArray) {
        MgMessage.build(ident, payload).forEach { block ->
            val padded = block.copyOf(((block.size + 11) / 12) * 12)
            val count = padded.size / 12
            engine.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, count, 0x81)))
            repeat(count) { index ->
                engine.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(
                    index, padded.copyOfRange(index * 12, index * 12 + 12),
                )))
            }
        }
    }

    private fun put16(data: ByteArray, at: Int, value: Int) {
        data[at] = value.toByte(); data[at + 1] = (value ushr 8).toByte()
    }
    private fun put32(data: ByteArray, at: Int, value: Int) = put32(data, at, value.toLong())
    private fun put32(data: ByteArray, at: Int, value: Long) = repeat(4) {
        data[at + it] = (value ushr (8 * it)).toByte()
    }
}
