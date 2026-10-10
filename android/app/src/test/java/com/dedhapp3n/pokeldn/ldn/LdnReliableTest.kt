package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnReliableTest {
    @Test
    fun upstreamInitialMetadataExchangeAndDelayedAck() {
        val host = LdnReliableSession()
        val child = LdnReliableSession()
        val opening = child.open(FRLG_RELIABLE_METADATA, 0)!!
        assertEquals(0xfff0, opening.sequence)
        assertEquals(RELIABLE_FLAGS_INIT, opening.flags)

        val delivered = host.receive(opening.encode(), 1)
        assertTrue(host.peerOpened)
        assertArrayEquals(FRLG_RELIABLE_METADATA, delivered.single().payload)
        assertTrue(host.poll(33).isEmpty())
        val ack = host.poll(34).single()
        assertEquals(0x40, ack.piaMessageFlags)
        val parsed = LdnReliableSession.decode(ack.encode())!!
        assertEquals(0xfff1, LdnReliableSession.parseBulkAck(parsed.payload)!!.first)

        child.receive(ack.encode(), 36)
        assertEquals(0, child.inflight)
    }

    @Test
    fun gapAckTriggersRetransmissionAndOrderedDuplicateFreeDelivery() {
        val sender = LdnReliableSession(acknowledgementPeriodMillis = 0)
        val receiver = LdnReliableSession(acknowledgementPeriodMillis = 0)
        sender.open(FRLG_RELIABLE_METADATA, 0)
        sender.receive(control(0xfff1), 1)
        val first = sender.send("first".toByteArray(), 2)
        val second = sender.send("second".toByteArray(), 2)
        val third = sender.send("third".toByteArray(), 2)

        receiver.receive(LdnReliableEmission(0xfff0, RELIABLE_FLAGS_INIT, 0xfff0, FRLG_RELIABLE_METADATA).encode(), 0)
        receiver.poll(0)
        assertTrue(receiver.receive(second.encode(), 3).isEmpty())
        assertTrue(receiver.receive(third.encode(), 3).isEmpty())
        val sack = receiver.poll(3).single()
        val (ack, mask) = LdnReliableSession.parseBulkAck(sack.payload)!!
        assertEquals(0xfff1, ack)
        assertEquals(3, mask[0].toInt() and 7)

        sender.receive(sack.encode(), 4)
        val retry = sender.poll(4).single()
        assertEquals(first.sequence, retry.sequence)
        assertEquals(0x20, retry.piaMessageFlags)
        val delivered = receiver.receive(retry.encode(), 5)
        assertEquals(listOf("first", "second", "third"), delivered.map { it.payload.toString(Charsets.UTF_8) })
        assertTrue(receiver.receive(retry.encode(), 6).isEmpty())
    }

    @Test
    fun bootstrapAndRttRetransmissionTimeoutsMatchUpstream() {
        val session = LdnReliableSession()
        val opening = session.open("leader A".toByteArray(), 0)!!
        assertTrue(session.poll(199).isEmpty())
        assertEquals(opening.sequence, session.poll(200).single().sequence)
        session.receive(control(0xfff1), 201)
        session.noteRtt(10.0)
        val next = session.send("rfu".toByteArray(), 210)
        assertTrue(session.poll(256).isEmpty())
        assertEquals(next.sequence, session.poll(257).single().sequence)
    }

    @Test
    fun malformedAndNonInitializedOpeningAreIgnoredAndResetClearsState() {
        val session = LdnReliableSession(maxInflight = 2)
        assertTrue(session.receive(byteArrayOf(1, 2), 0).isEmpty())
        val plain = LdnReliableEmission(0xfff0, RELIABLE_FLAGS_DATA, 0xfff0, byteArrayOf(1)).encode()
        assertTrue(session.receive(plain, 0).isEmpty())
        assertFalse(session.peerOpened)
        session.open(byteArrayOf(1), 0)
        session.send(byteArrayOf(2), 1)
        session.reset()
        assertEquals(0, session.inflight)
        assertFalse(session.localOpened)
        assertFalse(session.peerOpened)
    }

    private fun control(ack: Int): ByteArray = LdnReliableEmission(
        0xfff0,
        RELIABLE_FLAGS_CONTROL,
        0xfff0,
        byteArrayOf(0, 1, (ack ushr 8).toByte(), ack.toByte()) + ByteArray(16),
    ).encode()
}
