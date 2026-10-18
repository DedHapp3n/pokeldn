package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.ldn.LdnParticipant
import com.dedhapp3n.pokeldn.ldn.PiaCrypto
import com.dedhapp3n.pokeldn.ldn.PiaHeader
import com.dedhapp3n.pokeldn.ldn.message
import com.dedhapp3n.pokeldn.ldn.u16
import org.junit.Assert.*
import org.junit.Test

class ZaHostSessionTest {
    private val ssid = "9e9c14c2238b018697293395cba1e6d2".hex()
    private val mac = "02007f000002".hex()
    private val crypto = PiaCrypto(ssid, ZaNetworkParameters.GAME_KEY, 16)
    private fun fixture(name: String) = javaClass.getResourceAsStream("/za/$name.bin")!!.use { it.readBytes() }
    private fun host() = ZaHostSession(ssid, mac, "127.0.0.2", "00000000", fixture("identity10"), fixture("selection"), {}, hostVariable = 0xefb0)
    private fun payloads(datagrams: List<com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram>) = datagrams.flatMap {
        crypto.decode(it.payload, "127.0.0.2").messages.map { message -> message.protocol to message.payload }
    }
    private fun inbound(protocol: Int, body: ByteArray, destination: Int = 0): ByteArray {
        val raw = message(protocol, body) + u16(0xefb0)
        val pad = (-raw.size).mod(16)
        return crypto.encode(raw + ByteArray(pad) { 0xff.toByte() }, "127.0.0.3",
            PiaHeader(destination, 0x5ad2, 1, ByteArray(8), pad shl 4, 2))
    }

    @Test fun currentUpstreamNetAndSessionOpening() {
        val host = host()
        host.participantJoined(LdnParticipant(1, "127.0.0.3", "02007f000003".hex(), byteArrayOf(32), 6, 0), 0)
        assertArrayEquals(fixture("net_status"), payloads(host.tick(0)).single().second)
        host.receive(inbound(13, fixture("join")), "127.0.0.3", 100)
        val opening = payloads(host.tick(100))
        assertTrue(opening.any { it.first == 13 && it.second.first().toInt() == 2 })
        assertArrayEquals(fixture("update0"), opening.single { it.first == 13 && it.second.first().toInt() == 5 }.second)
        assertArrayEquals(fixture("net_property"), opening.single { it.first == 1 && it.second[1].toInt() == 0x50 }.second)
        assertTrue(opening.any { it.first == 10 && it.second.size > 8 && it.second.copyOfRange(8, 10).contentEquals(byteArrayOf(0x14, 0)) })
        val broadcast = opening.filter { it.first == 11 }.map { it.second }
        // Upstream bundles these two protocol-11 messages in one PIA datagram. Android
        // currently sends two datagrams, but the inner Reliable frames must be identical.
        assertEquals(2, broadcast.size)
        assertArrayEquals("0f006afff0fff00300000002".hex() + fixture("identity10"), broadcast[0])
        assertArrayEquals("070009fff1fff00300000002".hex() + fixture("identity11b"), broadcast[1])
    }

    @Test fun updateAcknowledgementsGateSelectionAndPickStartsSafeLeave() {
        val observed = mutableListOf<ZaGameMessageObservation>()
        val host = ZaHostSession(ssid, mac, "127.0.0.2", "00000000", fixture("identity10"), fixture("selection"), observed::add,
            hostVariable = 0xefb0)
        host.participantJoined(LdnParticipant(1, "127.0.0.3", "02007f000003".hex(), byteArrayOf(32), 6, 0), 0)
        host.tick(0)
        host.receive(inbound(13, fixture("join")), "127.0.0.3", 100)
        host.tick(100)
        host.receive(inbound(13, "067f00030000020000000000000001".hex()), "127.0.0.3", 150)
        val after0 = payloads(host.tick(1300))
        assertArrayEquals(fixture("update1"), after0.single { it.first == 13 && it.second.first().toInt() == 5 }.second)
        host.receive(inbound(13, "067f00030000020000000000010001".hex()), "127.0.0.3", 1400)
        assertFalse(payloads(host.tick(1459)).any { it.first == 10 && it.second.copyOfRange(8, 10).contentEquals(byteArrayOf(1, 0)) })
        assertTrue(payloads(host.tick(1460)).any { it.first == 10 && it.second.copyOfRange(8, 10).contentEquals(byteArrayOf(1, 0)) })
        assertTrue(payloads(host.tick(1520)).any { it.first == 10 && it.second.copyOfRange(8, 10).contentEquals(byteArrayOf(1, 0)) })
        assertEquals(2, host.status.selectionSent)
        val pick = fixture("preview").also { it[it.lastIndex] = 0 }
        val stream = byteArrayOf(0x0f, 0x01, 0x62, 0xff.toByte(), 0xf0.toByte(), 0xff.toByte(), 0xf0.toByte(), 0) + pick
        host.receive(inbound(10, stream, 0xefb0), "127.0.0.3", 1600)
        assertEquals(1, observed.size)
        assertArrayEquals(pick, observed.single().payload)
        assertEquals(10, observed.single().protocol)
        assertEquals("migration", host.status.leavePhase)
        val leaving = payloads(host.tick(1600))
        assertTrue(leaving.any { it.first == 13 && it.second.first().toInt() == 9 })
        assertFalse(leaving.any { it.first == 10 && it.second.size > 10 && it.second[8].toInt() == 1 && it.second[9].toInt() in listOf(1, 2, 4) })
        assertTrue(payloads(host.tick(6600)).any { it.first == 1 && it.second[1].toInt() == 0x11 })
        assertTrue(payloads(host.tick(10600)).any { it.first == 1 && it.second[1].toInt() == 0x40 })
        host.tick(12600)
        assertTrue(host.departed)
        assertEquals("done", host.status.leavePhase)
    }

    @Test fun updateAcknowledgementsAcceptDifferentGuestConstantIds() {
        for (guestConstant in listOf("7f00030000020000", "1122334455667788")) {
            val host = host()
            host.participantJoined(LdnParticipant(1, "127.0.0.3", "02007f000003".hex(), byteArrayOf(32), 6, 0), 0)
            host.tick(0)
            val join = fixture("join").also { guestConstant.hex().copyInto(it, 28) }
            host.receive(inbound(13, join), "127.0.0.3", 100)
            host.tick(100)
            val ack0 = byteArrayOf(6) + guestConstant.hex() + byteArrayOf(0, 0, 0, 0, 0, 1)
            host.receive(inbound(13, ack0), "127.0.0.3", 150)
            assertTrue(host.status.update0Ack)
            assertFalse(host.status.update1Ack)
            assertFalse(payloads(host.tick(1299)).any { it.first == 13 && it.second.firstOrNull()?.toInt() == 5 && it.second[2].toInt() == 1 })
            val update1 = payloads(host.tick(1300)).single { it.first == 13 && it.second.firstOrNull()?.toInt() == 5 }.second
            assertEquals(1, update1[2].toInt())
            val ack1 = byteArrayOf(6) + guestConstant.hex() + byteArrayOf(0, 0, 0, 1, 0, 1)
            host.receive(inbound(13, ack1), "127.0.0.3", 1400)
            assertTrue(host.status.update1Ack)
            assertEquals("READING POKÉMON", host.status.phase)
            assertEquals(0, host.status.selectionSent)
            host.tick(1459)
            assertEquals(0, host.status.selectionSent)
            host.tick(1460)
            assertEquals(1, host.status.selectionSent)
            host.tick(1520)
            assertEquals(2, host.status.selectionSent)
        }
    }

    private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
