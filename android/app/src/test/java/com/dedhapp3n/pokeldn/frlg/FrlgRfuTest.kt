package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.ldn.LdnPiaHost
import com.dedhapp3n.pokeldn.ldn.LdnParticipant
import com.dedhapp3n.pokeldn.ldn.LdnReliableDelivery
import com.dedhapp3n.pokeldn.ldn.LdnReliableEmission
import com.dedhapp3n.pokeldn.ldn.LdnReliableSession
import com.dedhapp3n.pokeldn.ldn.RELIABLE_FLAGS_DATA
import com.dedhapp3n.pokeldn.ldn.RELIABLE_FLAGS_INIT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgRfuTest {
    @Test fun `latched connect acknowledgement survives reliable half-window wrap`() {
        val pia = LdnPiaHost(ByteArray(32), byteArrayOf(2, 0, 0, 0, 0, 1), 14, 6)
        pia.participantJoined(
            LdnParticipant(1, "169.254.14.2", byteArrayOf(2, 0, 0, 0, 0, 2), ByteArray(0), 14, 0),
            0,
        )
        setPrivate(pia, "guestVar", 2)
        val reliable = privateField<LdnReliableSession>(pia, "reliable")
        val deliveries = privateField<ArrayDeque<LdnReliableDelivery>>(pia, "reliableDeliveries")
        val connectSequence = 0xfff0
        val connect = hex("574302008084")
        deliveries.addAll(reliable.receive(
            LdnReliableEmission(connectSequence, RELIABLE_FLAGS_INIT, connectSequence, connect).encode(), 0,
        ))
        reliable.poll(34) // Emits the cumulative ACK that covers C.

        val host = FrlgHostLink(pia, byteArrayOf(1, 0xf1.toByte()), runWalkThroughWalls = true)
        host.tick(35)
        val opened = host.snapshot()
        assertEquals(connectSequence, opened.connectSequence)
        assertTrue(opened.connectAckLatched)
        assertTrue(opened.localReliableOpened)
        assertEquals(1L, opened.protocolTickCount)

        var sequence = (connectSequence + 1) and 0xffff
        repeat(0x7ffe) { index ->
            reliable.receive(
                LdnReliableEmission(sequence, RELIABLE_FLAGS_DATA, 0, ByteArray(0)).encode(),
                100L + index,
            )
            sequence = (sequence + 1) and 0xffff
        }
        reliable.poll(40_000)
        assertTrue(pia.reliableReceiveAcknowledgementSent(connectSequence))
        assertEquals((connectSequence + 0x7fff) and 0xffff, pia.reliableLastReceiveAcknowledgement)
        host.tick(40_001)
        val beforeBoundary = host.snapshot().protocolTickCount

        reliable.receive(
            LdnReliableEmission(sequence, RELIABLE_FLAGS_DATA, 0, ByteArray(0)).encode(), 40_002,
        )
        reliable.poll(40_035)
        // This is the historical comparison used by the old gate: it flips at distance 0x8000.
        assertTrue(!pia.reliableReceiveAcknowledgementSent(connectSequence))
        host.tick(40_036)

        val afterBoundary = host.snapshot()
        assertTrue(afterBoundary.connectAckLatched)
        assertTrue(afterBoundary.localReliableOpened)
        assertEquals(beforeBoundary + 1, afterBoundary.protocolTickCount)
        assertEquals((connectSequence + 0x8000) and 0xffff, afterBoundary.lastReceiveAcknowledgement)
    }

    @Test fun `peer departure during early RFU setup is a protocol failure`() {
        val pia = LdnPiaHost(ByteArray(32), byteArrayOf(2, 0, 0, 0, 0, 1), 14, 6)
        val host = FrlgHostLink(pia, byteArrayOf(1, 0xf1.toByte()), runWalkThroughWalls = true)

        host.onPeerLeft()

        assertEquals(FrlgGiftStage.FAILED, host.snapshot().operationStage)
        assertTrue(host.snapshot().detail!!.contains("disconnected"))
    }

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
        val hostWire = ArrayList<Byte>()
        repeat(17) { index ->
            repeat(2) {
                val words = exchange.tick()
                assertEquals(index, words[0] and 31)
                if (it == 0) hostWire += FrlgRfu.serialize(words).drop(2)
            }
        }
        assertArrayEquals(UPSTREAM_HOST_BLOCK.copyOf(204), hostWire.toByteArray())
        assertArrayEquals(hex("47616d65467265616b20696e632e0000"), hostWire.toByteArray().copyOfRange(44, 60))
        assertEquals(FrlgLinkStage.WAITING_STANDBY, exchange.stage)
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_EXIT_STANDBY, 3)))
        assertEquals(FrlgLinkStage.ESTABLISHED, exchange.stage)
        assertTrue(!exchange.readyForGift)
        repeat(4) { assertEquals(FrlgRfu.READY_EXIT_STANDBY, exchange.tick()[0] and FrlgRfu.MASK) }
        assertTrue(exchange.readyForGift)
        val diagnostics = exchange.snapshot()
        assertTrue(diagnostics.childBlockReceived)
        assertEquals(1, diagnostics.childMpId)
        assertEquals(4, diagnostics.hostInitCount)
        assertEquals((0..16).joinToString(",") { "${it}x2" }, diagnostics.hostFragmentIndexes)
        assertTrue(diagnostics.hostBlockComplete)
        assertTrue(diagnostics.standbyReceived)
        assertEquals(4, diagnostics.standbyEchoesSent)
        assertTrue(diagnostics.barrierComplete)
    }

    @Test fun `integrated RFU rows preserve child echoes while upstream host block reaches wire`() {
        val leader = leaderInUni()
        val exchange = FrlgLinkPlayerExchange()
        val childBlock = childLinkPlayer(0x4005, 5).copyOf(204)
        val childCommands = buildList {
            add(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, 17, 0x81)))
            repeat(17) { index ->
                add(FrlgRfu.serialize(FrlgRfu.blockFragment(index,
                    childBlock.copyOfRange(index * 12, index * 12 + 12))))
            }
        }
        var timestamp = 1_000L
        val wireCommands = mutableListOf<ByteArray>()
        val echoCommands = mutableListOf<ByteArray>()

        childCommands.forEach { childCommand ->
            assertEquals("uni", leader.receive(childT(
                childSlot(FrlgRfu.UNI, 0, 0, payload = childCommand), timestamp++)))
            exchange.receive(leader.childCommand)
            val parentWords = exchange.tick()
            val rows = parentRows(leader.tick(parentWords)!!)
            wireCommands += rows.first
            echoCommands += rows.second
        }
        while (!exchange.snapshot().hostBlockComplete) {
            val parentWords = exchange.tick()
            val rows = parentRows(leader.tick(parentWords)!!)
            wireCommands += rows.first
            echoCommands += rows.second
        }

        val blockWire = wireCommands.filter {
            le16Test(it, 0) and FrlgRfu.MASK in setOf(FrlgRfu.SEND_BLOCK_INIT, FrlgRfu.SEND_BLOCK)
        }
        assertEquals(4 + 17 * 2, blockWire.size)
        assertEquals(List(4) { FrlgRfu.SEND_BLOCK_INIT }, blockWire.take(4).map { le16Test(it, 0) and FrlgRfu.MASK })
        assertEquals((0..16).flatMap { listOf(it, it) }, blockWire.drop(4).map { le16Test(it, 0) and 31 })
        assertEquals(0, leader.echoDropped)
        assertTrue(leader.echoBacklogPeak > 0)
        assertTrue(echoCommands.any { le16Test(it, 0) and FrlgRfu.MASK == FrlgRfu.SEND_BLOCK_INIT })
        repeat(leader.echoBacklog) { leader.tick(IntArray(7)) }
        val persisted = parentRows(leader.tick(IntArray(7))!!).second
        assertEquals(FrlgRfu.SEND_BLOCK or 16, le16Test(persisted, 0))
    }

    @Test fun `standby during host block is latched until every repeated fragment completes`() {
        val exchange = exchangeWithChildBlock()
        repeat(4) { exchange.tick() }
        repeat(5) { exchange.tick() }

        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_EXIT_STANDBY, 7)))
        assertEquals(FrlgLinkStage.SENDING_HOST, exchange.stage)
        assertTrue(exchange.snapshot().standbyLatched)
        repeat(4) { assertEquals(FrlgRfu.READY_EXIT_STANDBY, exchange.tick()[0] and FrlgRfu.MASK) }
        while (!exchange.snapshot().hostBlockComplete) exchange.tick()

        assertEquals(FrlgLinkStage.ESTABLISHED, exchange.stage)
        assertTrue(exchange.readyForGift)
        assertTrue(!exchange.snapshot().standbyLatched)
    }

    @Test fun `repeated child block commands cannot rewind the active host sender`() {
        val exchange = exchangeWithChildBlock()
        assertEquals(FrlgLinkStage.SENDING_HOST, exchange.stage)
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, 17, 0x81)))
        exchange.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(16, ByteArray(12))))

        assertEquals(FrlgLinkStage.SENDING_HOST, exchange.stage)
        assertEquals(FrlgRfu.SEND_BLOCK_INIT, exchange.tick()[0] and FrlgRfu.MASK)
    }

    @Test fun `premature standby is echoed but cannot satisfy the LinkPlayer barrier`() {
        val exchange = FrlgLinkPlayerExchange()
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.READY_EXIT_STANDBY, 1)))
        repeat(4) { exchange.tick() }
        repeat(9) { exchange.tick() }
        feedChildBlock(exchange)
        repeat(4 + 17 * 2) { exchange.tick() }

        assertEquals(FrlgLinkStage.WAITING_STANDBY, exchange.stage)
        assertTrue(!exchange.snapshot().standbyLatched)
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

    private fun exchangeWithChildBlock() = FrlgLinkPlayerExchange().also { exchange ->
        repeat(9) { exchange.tick() }
        feedChildBlock(exchange)
    }

    private fun feedChildBlock(exchange: FrlgLinkPlayerExchange) {
        val block = childLinkPlayer(version = 0x4005, language = 5).copyOf(204)
        exchange.receive(FrlgRfu.serialize(intArrayOf(FrlgRfu.SEND_BLOCK_INIT, 17, 0x81)))
        repeat(17) { index ->
            exchange.receive(FrlgRfu.serialize(FrlgRfu.blockFragment(index,
                block.copyOfRange(index * 12, index * 12 + 12))))
        }
    }

    private fun gameData(version: Int, language: Int): ByteArray {
        val out = ByteArray(26)
        putLe16Test(out, 0, 2)
        putLe16Test(out, 2, (language and 15) or ((version and 15) shl 10))
        putLe16Test(out, 4, 0x2288)
        return out
    }

    private fun leaderInUni(): FrlgRfuLeader {
        val leader = FrlgRfuLeader(byteArrayOf(1, 0xf1.toByte()))
        leader.receive(childFrame(FrlgGbaFrame.C, byteArrayOf(0x67, 0x79)))
        leader.tick(); leader.tick()
        childNi(gameData(5, 5)).forEachIndexed { index, slot ->
            leader.receive(childT(slot, index + 1L))
            if (FrlgRfu.childFields(slot).state != FrlgRfu.NULL) leader.tick()
        }
        leader.tick() // queued link-state frame
        while (leader.state != FrlgRfuState.UNI) {
            val frame = leader.tick()!!
            val bodySize = le16Test(frame, 2)
            val body = frame.copyOfRange(4, 4 + bodySize)
            val slotLength = body[4].toInt() and 0xff
            val parentSlot = body.copyOfRange(8, 8 + slotLength)
            val fields = FrlgRfu.parentFields(parentSlot)
            if (fields.state != FrlgRfu.NULL) {
                leader.receive(childT(childSlot(fields.state, fields.n, fields.phase, ack = 1), 500))
            }
        }
        return leader
    }

    private fun parentRows(frame: ByteArray): Pair<ByteArray, ByteArray> {
        val slot = frame.copyOfRange(12, frame.size)
        assertEquals(FrlgRfu.UNI, FrlgRfu.parentFields(slot).state)
        return slot.copyOfRange(3, 17) to slot.copyOfRange(17, 31)
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
    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(instance: Any, name: String): T = instance.javaClass
        .getDeclaredField(name).also { it.isAccessible = true }.get(instance) as T
    private fun setPrivate(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(instance, value)
    }
    private fun le16Test(data: ByteArray, offset: Int) = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
    private fun putLe16Test(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }

    companion object {
        private val UPSTREAM_HOST_BLOCK = hexStatic(
            "47616d65467265616b20696e632e0000044000802288ed47bfc7cfffffffffff" +
                "00000000000000000000020047616d65467265616b20696e632e0000").copyOf(200)
        private fun hexStatic(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
