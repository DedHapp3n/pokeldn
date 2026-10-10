package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgRfuTest {
    @Test fun `connect accept and link state match upstream fixture`() {
        val leader = FrlgRfuLeader(byteArrayOf(0xb7.toByte(), 0xf1.toByte()))
        assertEquals("connect", leader.receive(hex("574302008084")))
        assertArrayEquals(hex("57410600b7f180840000"), leader.tick())
        assertArrayEquals(hex("5747040000000000"), leader.tick())
        assertEquals("connect_duplicate", leader.receive(hex("574302008084")))
    }

    @Test fun `child NI is acked and identity is reassembled before parent NI`() {
        val leader = FrlgRfuLeader(byteArrayOf(1, 0xf1.toByte()))
        leader.receive(childFrame(FrlgGbaFrame.C, byteArrayOf(0x67, 0x79)))
        leader.tick(); leader.tick()
        val gameData = gameData(version = 5, language = 5)
        val slots = childNi(gameData)
        slots.forEachIndexed { index, slot ->
            leader.receive(childT(slot, index + 1L))
            if (FrlgRfu.childFields(slot).state != FrlgRfu.NULL) assertNotNull(leader.tick())
        }
        assertArrayEquals(gameData, leader.childGameData)
        assertEquals(FrlgRfuState.PARENT_NI, leader.state)
        assertArrayEquals(hex("5747040001000000"), leader.tick())

        while (leader.state != FrlgRfuState.UNI) {
            val frame = leader.tick()!!
            val bodySize = le16Test(frame, 2)
            val body = frame.copyOfRange(4, 4 + bodySize)
            val slotLength = body[4].toInt() and 0xff
            val parentSlot = body.copyOfRange(8, 8 + slotLength)
            val fields = FrlgRfu.parentFields(parentSlot)
            if (fields.state != FrlgRfu.NULL) {
                val ack = childSlot(fields.state, fields.n, fields.phase, ack = 1)
                assertEquals("parent_ni_ack", leader.receive(childT(ack, 100)))
            }
        }
    }

    @Test fun `link player exchange parses LeafGreen German and sends host block`() {
        val exchange = FrlgLinkPlayerExchange()
        repeat(8) { assertEquals(FrlgRfu.SEND_PLAYER_IDS, exchange.tick()[0]) }
        assertEquals(FrlgRfu.SEND_BLOCK_REQ, exchange.tick()[0])

        val block = childLinkPlayer(version = 0x4005, language = 5).copyOf(204)
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, 17, 0x81)))
        for (index in 0 until 17) {
            exchange.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(index,
                block.copyOfRange(index * 12, index * 12 + 12))))
        }
        assertEquals("LeafGreen", exchange.child!!.cartridge!!.game)
        assertEquals("German", exchange.child!!.cartridge!!.language)
        assertEquals(FrlgLinkStage.SENDING_HOST, exchange.stage)
        repeat(4) { assertEquals(FrlgRfu.SEND_BLOCK_INIT, exchange.tick()[0] and FrlgRfu.MASK) }
        repeat(17) { index -> assertEquals(index, exchange.tick()[0] and 31) }
        assertEquals(FrlgLinkStage.WAITING_STANDBY, exchange.stage)
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_EXIT_STANDBY, 3)))
        assertEquals(FrlgLinkStage.ESTABLISHED, exchange.stage)
    }

    @Test fun `invalid LinkPlayer magic is rejected`() {
        val exchange = FrlgLinkPlayerExchange()
        repeat(9) { exchange.tick() }
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, 17, 0x81)))
        repeat(17) { exchange.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(it, ByteArray(12)))) }
        assertEquals(FrlgLinkStage.FAILED, exchange.stage)
        assertTrue(exchange.error!!.contains("magic"))
    }

    private fun childLinkPlayer(version: Int, language: Int): ByteArray {
        val magic = hex("47616d65467265616b20696e632e0000")
        val body = ByteArray(28)
        putLe16Test(body, 0, version); putLe16Test(body, 2, 0x8000)
        putLe16Test(body, 26, language)
        return magic + body + magic
    }

    private fun gameData(version: Int, language: Int): ByteArray {
        val out = ByteArray(26)
        putLe16Test(out, 0, 2)
        putLe16Test(out, 2, (language and 15) or ((version and 15) shl 10))
        putLe16Test(out, 4, 0x2288)
        return out
    }

    private fun childNi(data: ByteArray): List<ByteArray> {
        val header = byteArrayOf(1, 12, 0, data.size.toByte(), 0, 0, 0)
        return listOf(
            childSlot(FrlgRfu.NI_START, 1, 0, payload = header),
            childSlot(FrlgRfu.NI, 1, 0, payload = data.copyOfRange(0, 12)),
            childSlot(FrlgRfu.NI, 1, 1, payload = data.copyOfRange(12, 24)),
            childSlot(FrlgRfu.NI, 1, 2, payload = data.copyOfRange(24, 26)),
            childSlot(FrlgRfu.NI_END, 0, 0),
            childSlot(FrlgRfu.NULL, 1, 0),
        )
    }

    private fun childSlot(state: Int, n: Int, phase: Int, ack: Int = 0,
                          payload: ByteArray = byteArrayOf()): ByteArray {
        val value = ((state and 15) shl 10) or ((ack and 1) shl 9) or
            ((n and 3) shl 7) or ((phase and 3) shl 5) or payload.size
        return byteArrayOf(value.toByte(), (value ushr 8).toByte()) + payload
    }
    private fun childT(slot: ByteArray, ts: Long): ByteArray {
        val padded = slot.copyOf((slot.size + 3) and 3.inv())
        val body = ByteArray(8)
        for (i in 0 until 4) body[i] = (ts ushr (8 * i)).toByte()
        body[5] = slot.size.toByte()
        return childFrame(FrlgGbaFrame.T, body + padded)
    }
    private fun childFrame(type: Int, body: ByteArray) = FrlgGbaFrame.frame(type, body)
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun le16Test(data: ByteArray, offset: Int) = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    private fun putLe16Test(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
}
