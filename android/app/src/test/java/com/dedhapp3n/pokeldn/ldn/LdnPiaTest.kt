package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnPiaTest {
    private val ssid = "5c42961f018902911a2f1c9548c8e9c4".hex()
    private val hostMac = "3ca9abf73c06".hex()
    private val consoleMac = "3c3300609493".hex()
    private val participant = LdnParticipant(1, "169.254.88.2", consoleMac, "EMU".toByteArray(), 88, 0)

    @Test
    fun upstreamPiaFixturesAdvanceNetJoinResponseAndFinalAck() {
        val host = host()
        host.participantJoined(participant, 0)
        val probes = host.tick(0)
        assertEquals(listOf("169.254.88.255", participant.ipAddress), probes.map { it.destinationIp })
        assertArrayEquals(UPSTREAM_NET_PROBE.hex(), probes.first().payload)
        val probe = PiaCrypto(ssid).decode(probes.first().payload, "169.254.88.1")
        assertEquals(1, probe.messages.single().protocol)
        assertEquals(0x11, probe.messages.single().payload[1].toInt() and 0xff)
        assertEquals(6, readU16(probe.messages.single().payload, 27))
        assertArrayEquals("0808080808080808".hex(), probe.header.nonce)
        assertEquals(LdnPiaStage.NET_PROBING, host.snapshot().stage)

        host.receive(NET_RESPONSE.hex(), participant.ipAddress, 10)
        assertEquals(LdnPiaStage.NET_CONNECTED, host.snapshot().stage)

        val responses = host.receive(SESSION_JOIN.hex(), participant.ipAddress, 20)
        assertEquals(3, responses.size)
        assertEquals(listOf(participant.ipAddress, "169.254.88.255", participant.ipAddress), responses.map { it.destinationIp })
        val crypto = PiaCrypto(ssid)
        val decoded = responses.map { crypto.decode(it.payload, "169.254.88.1") }
        assertEquals(listOf(2, 5, 5), decoded.map { it.messages.single().payload[0].toInt() and 0xff })
        assertArrayEquals(UPSTREAM_RESPONSE.hex(), decoded[0].messages.single().payload)
        assertArrayEquals(UPSTREAM_UPDATE.hex(), decoded[1].messages.single().payload)
        assertArrayEquals("0808080808080809".hex(), decoded[0].header.nonce)
        assertArrayEquals("080808080808080a".hex(), decoded[1].header.nonce)
        assertArrayEquals(decoded[1].header.nonce, decoded[2].header.nonce)
        assertEquals(LdnPiaStage.SESSION_RESPONSE_SENT, host.snapshot().stage)
        assertEquals(1, host.snapshot().sessionRequests)
        assertEquals(1, host.snapshot().sessionResponses)

        host.receive(SESSION_ACK.hex(), participant.ipAddress, 30)
        assertEquals(LdnPiaStage.ESTABLISHED, host.snapshot().stage)
        assertEquals("PIA session established", host.snapshot().detail)
    }

    @Test
    fun sessionAcceptanceRetriesAndCleanupClearsPiaIdentity() {
        val host = host()
        host.participantJoined(participant, 0)
        host.receive(SESSION_JOIN.hex(), participant.ipAddress, 20)
        assertTrue(host.tick(269).isEmpty())
        assertEquals(3, host.tick(270).size)

        host.reset()
        assertEquals(LdnPiaStage.WAITING, host.snapshot().stage)
        assertEquals(0, host.snapshot().sessionRequests)
        assertTrue(host.tick(1_000).isEmpty())
    }

    @Test
    fun activePropertyUpdateMatchesVendoredUpstreamPayload() {
        val host = host()
        host.participantJoined(participant, 0)
        host.receive(SESSION_JOIN.hex(), participant.ipAddress, 20)
        host.receive(SESSION_ACK.hex(), participant.ipAddress, 30)
        host.activateApplicationData(ACTIVE_APP_DATA.hex(), 31)

        val packet = host.tick(31).single()
        val decoded = PiaCrypto(ssid).decode(packet.payload, host.hostIp)
        assertEquals(participant.ipAddress, packet.destinationIp)
        assertEquals(1, decoded.messages.single().protocol)
        assertArrayEquals(UPSTREAM_PROPERTY_UPDATE.hex(), decoded.messages.single().payload)
        assertEquals(3, decoded.header.flags and 3)
        assertEquals(1, host.snapshot().propertyUpdates)
    }

    @Test
    fun nativeNonceSequenceIncrementsAndWrapsForWholeSession() {
        val sequence = PiaNonceSequence("ffffffffffffffff".hex())
        assertArrayEquals("ffffffffffffffff".hex(), sequence.take())
        assertArrayEquals("0000000000000000".hex(), sequence.take())
        assertArrayEquals("0000000000000001".hex(), sequence.take())
    }

    @Test
    fun minimumUdpTransportConsumesOneDatagramAndAnswersArp() {
        val hostTransport = LdnPiaUdpTransport("169.254.88.1", hostMac)
        val consoleTransport = LdnPiaUdpTransport("169.254.88.2", consoleMac)
        val frame = consoleTransport.frame("169.254.88.1", hostMac, byteArrayOf(1, 2, 3))
        val (datagram, reply) = hostTransport.receive(frame)
        assertEquals(null, reply)
        assertEquals("169.254.88.2", datagram!!.sourceIp)
        assertArrayEquals(consoleMac, datagram.sourceMac)
        assertArrayEquals(byteArrayOf(1, 2, 3), datagram.payload)

        val request = hostMac + consoleMac + "08060001080006040001".hex() + consoleMac +
            "a9fe5802".hex() + ByteArray(6) + "a9fe5801".hex()
        val (_, arp) = hostTransport.receive(request)
        requireNotNull(arp)
        assertArrayEquals(consoleMac, arp.copyOfRange(0, 6))
        assertArrayEquals(hostMac, arp.copyOfRange(6, 12))
        assertEquals(2, ((arp[20].toInt() and 0xff) shl 8) or (arp[21].toInt() and 0xff))
    }

    @Test
    fun upstreamNetProbeHasExactUdpIpv4AndEthernetFraming() {
        val probe = host().apply { participantJoined(participant, 0) }.tick(0).first().payload
        val transport = LdnPiaUdpTransport("169.254.88.1", hostMac)

        assertArrayEquals(UPSTREAM_BROADCAST_ETHERNET.hex(), transport.frame("169.254.88.255", ByteArray(6) { -1 }, probe))
        assertArrayEquals(UPSTREAM_UNICAST_ETHERNET.hex(), transport.frame(participant.ipAddress, consoleMac, probe))
    }

    private fun host() = LdnPiaHost(
        ssid = ssid,
        hostMac = hostMac,
        networkNumber = 88,
        maxParticipants = 6,
        randomBytes = { size -> ByteArray(size) { size.toByte() } },
    )

    companion object {
        private const val UPSTREAM_NET_PROBE =
            "32ab98649083000000c600000008080808080808083bb8bcaddd0046979b5106850cc40b1aeb119971fa6e9e375747bb4bc04a6e040d9788a96aea29e4fd9b10045250c96460dac19bfdd75a0e2506093dad9fd214cb3e89c3199342253a6632874846bc09b6b805594a5d8244"
        private const val UPSTREAM_BROADCAST_ETHERNET =
            "ffffffffffff3ca9abf73c060800450000890001000040117566a9fe5801a9fe58ff303930390075f9bf$UPSTREAM_NET_PROBE"
        private const val UPSTREAM_UNICAST_ETHERNET =
            "3c33006094933ca9abf73c060800450000890002000040117662a9fe5801a9fe5802303930390075fabc$UPSTREAM_NET_PROBE"
        private const val NET_RESPONSE =
            "32ab9864902000c6c493000002000000000000000325d8ae50ea0efcd60afe97e6ab475d80612f5a64cc553d08"
        private const val SESSION_JOIN =
            "32ab986490f000c6c493000102000000000000000147ee274e1ac56efc1c51e33c43274192f2908a4357b8e5bc63caa3c475996a07aea9ad04d83dde9a58b043334a1721342acf4017567aba5a4dec0213dcfb496e5d3f6c5ed0a07decc1f7ece8692e13432518265931ebe0a334c667f73e20f4e1fa02c63afc517db5b36a0f17cfceb1d9a1404913126471acfbdaf6c1f13d709dcdc1a0936ad6b4fc"
        private const val SESSION_ACK =
            "32ab986490b000c6c4930002020000000000000002376190c00cb5caf9a01377f4b0e06c711d48ec966f1c1368d8a7da7af78a0a83f4336e00dcabbc0c"
        private const val UPSTREAM_RESPONSE =
            "020d07010000000004040404ab3c06f7a93c000000c63c33006094930000c4930100010000"
        private const val UPSTREAM_UPDATE =
            "05000001000003ab3c06f7a93c000000c602000001000000000000ab3c06f7a93c000000c6a9fe580130390000000000000000000000000000000000000000000000000000000000000000000000000001010000000000000000000100000000000000000000000701504f4b454c444e3c33006094930000c493a9fe580230390100010000000000000000000000000000000000000000000000000000000000000000000001010000000000000000000100000000000000000000000301454d55"
        private const val ACTIVE_APP_DATA =
            "005c1600580000000000000000000000000000000001020000000701504f4b454c444e00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006346606c642e74673d6140524c5e712323752a6c4b7123232323234e502723232323232323"
        private const val UPSTREAM_PROPERTY_UPDATE =
            "0150007a00000001000000001e1d14d900020006000000000000570f01010000005c0000001e$ACTIVE_APP_DATA"
    }
}

private fun readU16(value: ByteArray, offset: Int): Int =
    ((value[offset].toInt() and 0xff) shl 8) or (value[offset + 1].toInt() and 0xff)

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
