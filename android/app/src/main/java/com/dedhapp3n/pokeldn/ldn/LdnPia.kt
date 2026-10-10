package com.dedhapp3n.pokeldn.ldn

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

enum class LdnPiaStage {
    WAITING, NET_PROBING, NET_CONNECTED, SESSION_JOIN_RECEIVED, SESSION_RESPONSE_SENT, ESTABLISHED, FAILED
}

internal data class LdnPiaSnapshot(
    val stage: LdnPiaStage = LdnPiaStage.WAITING,
    val netRequestsSent: Long = 0,
    val sessionRequests: Long = 0,
    val sessionResponses: Long = 0,
    val reliableEstablished: Boolean = false,
    val reliableFramesReceived: Long = 0,
    val reliableFramesSent: Long = 0,
    val detail: String? = null,
)

internal data class LdnPiaDatagram(val destinationIp: String, val payload: ByteArray)

/** Minimum upstream HostPeerProtocol path: Pia Net, Session and RTT only. */
internal class LdnPiaHost(
    private val ssid: ByteArray,
    private val hostMac: ByteArray,
    networkNumber: Int,
    private val maxParticipants: Int,
    private val randomBytes: (Int) -> ByteArray = SecureRandom().let { random ->
        { size -> ByteArray(size).also(random::nextBytes) }
    },
) {
    val hostIp = "169.254.$networkNumber.1"
    val broadcastIp = "169.254.$networkNumber.255"
    private val crypto = PiaCrypto(ssid)
    private var participant: LdnParticipant? = null
    private var stage = LdnPiaStage.WAITING
    private var netRequests = 0L
    private var sessionRequests = 0L
    private var sessionResponses = 0L
    private var nextNetMillis = Long.MAX_VALUE
    private var nextSessionMillis = Long.MAX_VALUE
    private var guestVar: Int? = null
    private var join: SessionJoin? = null
    private var nextPacketId = 2
    private var nextReliablePacketId = 2
    private val reliable = LdnReliableSession(retransmitLimit = 2)
    private val reliableDeliveries = ArrayDeque<LdnReliableDelivery>()
    private var reliableFramesReceived = 0L
    private var reliableFramesSent = 0L
    private var rttTemplate: ByteArray? = null
    private var nextRttMillis = Long.MAX_VALUE
    private var rttSystemTime = 0x10000L
    private var detail: String? = null

    fun participantJoined(value: LdnParticipant, nowMillis: Long) {
        if (participant?.mac?.contentEquals(value.mac) == true) return
        participant = value
        stage = LdnPiaStage.NET_PROBING
        detail = "PIA network negotiation started"
        nextNetMillis = nowMillis
    }

    fun participantLeft(mac: ByteArray) {
        if (participant?.mac?.contentEquals(mac) == true) reset()
    }

    fun snapshot() = LdnPiaSnapshot(
        stage, netRequests, sessionRequests, sessionResponses,
        reliable.peerOpened, reliableFramesReceived, reliableFramesSent, detail,
    )

    fun tick(nowMillis: Long): List<LdnPiaDatagram> {
        val peer = participant ?: return emptyList()
        val out = mutableListOf<LdnPiaDatagram>()
        if (stage == LdnPiaStage.NET_PROBING && nowMillis >= nextNetMillis) {
            val probe = buildNetProbe(peer)
            out += LdnPiaDatagram(broadcastIp, probe)
            out += LdnPiaDatagram(peer.ipAddress, probe)
            netRequests++
            nextNetMillis = nowMillis + NET_RETRY_MS
        }
        if (join != null && stage != LdnPiaStage.ESTABLISHED && nowMillis >= nextSessionMillis) {
            out += sessionAcceptance(join!!)
            nextSessionMillis = nowMillis + SESSION_RETRY_MS
        }
        if (stage == LdnPiaStage.ESTABLISHED && rttTemplate != null && nowMillis >= nextRttMillis) {
            rttSystemTime = (rttSystemTime + 1) and Long.MAX_VALUE
            val request = rttTemplate!!.copyOf(21).also {
                it[0] = 0
                ByteBuffer.wrap(it, 8, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(rttSystemTime)
            }
            out += buildSingleMessage(3, request, SESSION_VAR, HOST_VAR, nextPacket(), guestVar)
            nextRttMillis = nowMillis + RTT_PERIOD_MS
        }
        if (stage == LdnPiaStage.ESTABLISHED) {
            reliable.poll(nowMillis).forEach { out += reliableDatagram(it) }
        }
        return out
    }

    fun receive(datagram: ByteArray, sourceIp: String, nowMillis: Long): List<LdnPiaDatagram> {
        val peer = participant ?: return emptyList()
        if (sourceIp != peer.ipAddress) return emptyList()
        val decoded = try { crypto.decode(datagram, sourceIp) } catch (error: Exception) {
            detail = "PIA packet rejected: ${error.message}"
            return emptyList()
        }
        val out = mutableListOf<LdnPiaDatagram>()
        decoded.messages.forEach { message ->
            when (message.protocol) {
                1 -> if (message.payload.size >= 8 && message.payload[1].u8() == NET_RESPONSE) {
                    stage = LdnPiaStage.NET_CONNECTED
                    nextNetMillis = Long.MAX_VALUE
                    detail = "PIA network response received"
                }
                13 -> when (message.payload.firstOrNull()?.u8()) {
                    SESSION_JOIN -> {
                        val parsed = parseSessionJoin(message.payload) ?: return@forEach
                        if (!validJoin(parsed, decoded.header, sourceIp)) return@forEach
                        join = parsed
                        guestVar = parsed.sourceVar
                        sessionRequests++
                        stage = LdnPiaStage.SESSION_JOIN_RECEIVED
                        detail = "PIA connection request received"
                        out += sessionAcceptance(parsed)
                        nextSessionMillis = nowMillis + SESSION_RETRY_MS
                    }
                    SESSION_UPDATE_ACK -> {
                        if (join != null) {
                            stage = LdnPiaStage.ESTABLISHED
                            nextSessionMillis = Long.MAX_VALUE
                            detail = "PIA session established"
                        }
                    }
                }
                3 -> if (stage == LdnPiaStage.ESTABLISHED && message.payload.size >= 16) {
                    rttTemplate = message.payload.copyOf(21)
                    if (message.payload[0].u8() == 0) {
                        val response = message.payload.copyOf(21).also { it[0] = 1 }
                        out += buildSingleMessage(3, response, SESSION_VAR, HOST_VAR, nextPacket(), guestVar)
                        if (nextRttMillis == Long.MAX_VALUE) nextRttMillis = nowMillis
                    }
                }
                10 -> if (stage == LdnPiaStage.ESTABLISHED) {
                    val deliveries = reliable.receive(message.payload, nowMillis)
                    reliableFramesReceived++
                    reliableDeliveries.addAll(deliveries)
                    if (reliable.peerOpened && detail != "Reliable protocol established") {
                        detail = "Reliable protocol established"
                    }
                }
            }
        }
        return out
    }

    fun drainReliableDeliveries(): List<LdnReliableDelivery> = buildList {
        while (reliableDeliveries.isNotEmpty()) add(reliableDeliveries.removeFirst())
    }

    fun openReliable(payload: ByteArray, nowMillis: Long): LdnPiaDatagram? =
        reliable.open(payload, nowMillis)?.let(::reliableDatagram)

    fun sendReliable(payload: ByteArray, nowMillis: Long): LdnPiaDatagram =
        reliableDatagram(reliable.send(payload, nowMillis))

    fun reset() {
        participant = null
        stage = LdnPiaStage.WAITING
        netRequests = 0
        sessionRequests = 0
        sessionResponses = 0
        nextNetMillis = Long.MAX_VALUE
        nextSessionMillis = Long.MAX_VALUE
        guestVar = null
        join = null
        rttTemplate = null
        nextRttMillis = Long.MAX_VALUE
        nextPacketId = 2
        nextReliablePacketId = 2
        reliable.reset()
        reliableDeliveries.clear()
        reliableFramesReceived = 0
        reliableFramesSent = 0
        detail = null
    }

    private fun buildNetProbe(peer: LdnParticipant): ByteArray {
        val stationSize = 22
        val body = ByteArrayOutputStream().apply {
            writeU32(2)
            writeU16(HOST_VAR)
            write(constantId(hostMac))
            writeU64(networkId().toLong() and 0xffffffffL)
            write(1)
            writeU16(maxParticipants)
            write(0)
            listOf(hostIp, peer.ipAddress).take(maxParticipants).forEachIndexed { rank, ip ->
                write(byteArrayOf(0, rank.toByte(), 0, 0))
                write(ipBytes(ip)); write(ByteArray(12)); writeU16(PIA_PORT)
            }
            repeat((maxParticipants - minOf(2, maxParticipants)).coerceAtLeast(0)) {
                write(byteArrayOf(0, 0xff.toByte(), 0, 0)); write(ByteArray(16)); writeU16(0)
            }
        }.toByteArray()
        val net = byteArrayOf(1, NET_REQUEST.toByte()) + u16(maxParticipants * stationSize) + body
        val compressed = zstdCompress(message(1, net))
        return crypto.encode(
            compressed + ByteArray((-compressed.size).mod(16)) { 0xff.toByte() },
            hostIp,
            PiaHeader(0, HOST_VAR, 0, randomBytes(8), ((-compressed.size).mod(16) shl 4) or 3, 0),
        )
    }

    private fun sessionAcceptance(value: SessionJoin): List<LdnPiaDatagram> {
        val response = buildJoinResponse(value, randomBytes(4))
        val responseNonce = randomBytes(8)
        val updateNonce = randomBytes(8)
        val update = buildSessionUpdate(value)
        val updatePacket = buildSingleMessage(
            13, update, SESSION_VAR, HOST_VAR, 1, value.sourceVar,
            compress = true, nonce = updateNonce,
        ).payload
        val responsePacket = buildSingleMessage(
            13, response, value.sourceVar, HOST_VAR, 1, value.sourceVar,
            nonce = responseNonce,
        ).payload
        sessionResponses++
        stage = LdnPiaStage.SESSION_RESPONSE_SENT
        detail = "PIA response sent"
        return listOf(
            LdnPiaDatagram(broadcastIp, updatePacket),
            LdnPiaDatagram(value.ip, updatePacket),
            LdnPiaDatagram(value.ip, responsePacket),
        )
    }

    private fun buildSingleMessage(
        protocol: Int,
        payload: ByteArray,
        destinationVar: Int,
        sourceVar: Int,
        packetId: Int,
        footerVar: Int?,
        compress: Boolean = false,
        nonce: ByteArray = randomBytes(8),
        messageFlags: Int? = null,
    ): LdnPiaDatagram {
        var body = message(protocol, payload, messageFlags)
        if (compress) body = zstdCompress(body)
        val footer = if (footerVar == null) byteArrayOf() else u16(footerVar)
        body += footer
        val pad = (-body.size).mod(16)
        body += ByteArray(pad) { 0xff.toByte() }
        val packet = crypto.encode(
            body, hostIp,
            PiaHeader(destinationVar, sourceVar, packetId, nonce, (pad shl 4) or if (compress) 1 else 0, footer.size),
        )
        return LdnPiaDatagram(participant!!.ipAddress, packet)
    }

    private fun reliableDatagram(emission: LdnReliableEmission): LdnPiaDatagram {
        reliableFramesSent++
        return buildSingleMessage(
            protocol = 10,
            payload = emission.encode(),
            destinationVar = guestVar ?: error("PIA guest identity is missing"),
            sourceVar = HOST_VAR,
            packetId = nextReliablePacket(),
            footerVar = guestVar,
            compress = emission.encode().size + (if (emission.piaMessageFlags == null) 4 else 5) >= 62,
            messageFlags = emission.piaMessageFlags,
        )
    }

    private fun buildJoinResponse(value: SessionJoin, random4: ByteArray): ByteArray {
        val sessionVersion = value.protocols.firstOrNull { it.first == 13 }?.second ?: 7
        return byteArrayOf(2, 13, sessionVersion.toByte(), 1) + ByteArray(4) + random4 +
            constantId(hostMac) + u16(HOST_VAR) + value.sourceConstantId + u16(value.sourceVar) +
            byteArrayOf(1, 0, 1, 0, 0)
    }

    private fun buildSessionUpdate(value: SessionJoin): ByteArray {
        val hostPlayer = Player(DEFAULT_PLAYER_ID, 1, HOST_NAME)
        return byteArrayOf(5, 0, 0, 1, 0, 0, 3) + constantId(hostMac) + u16(HOST_VAR) +
            byteArrayOf(2, 0, 0, 1, 0, 0) + ByteArray(4) +
            sessionStation(constantId(hostMac), HOST_VAR, hostIp, PIA_PORT, 0, 0, ByteArray(32), 1, 1, listOf(hostPlayer)) +
            sessionStation(value.sourceConstantId, value.sourceVar, value.ip, value.port, 1, 1,
                value.token, value.numPlayers, value.numParticipants, value.players)
    }

    private fun sessionStation(
        constant: ByteArray, variable: Int, ip: String, port: Int, index: Int, order: Int,
        token: ByteArray, playersCount: Int, participantsCount: Int, players: List<Player>,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(constant); writeU16(variable); write(ipBytes(ip)); writeU16(port); write(index)
        writeU16(order); write(byteArrayOf(0, 0)); write(token); write(playersCount); write(participantsCount)
        write(byteArrayOf(0, 0)); players.forEach { write(it.id); writeU32(it.name.size); write(it.encoding); write(it.name) }
    }.toByteArray()

    private fun validJoin(value: SessionJoin, header: PiaHeader, sourceIp: String): Boolean =
        value.destinationConstantId.contentEquals(constantId(hostMac)) && value.destinationVar == HOST_VAR &&
            value.sourceVar == header.source && value.ip == sourceIp && value.players.isNotEmpty()

    private fun parseSessionJoin(payload: ByteArray): SessionJoin? = try {
        var p = 1
        val count = payload[p++].u8()
        val protocols = List(count) { payload[p++].u8() to payload[p++].u8() }
        p += 2 + 4
        val sourceConstant = payload.copyOfRange(p, p + 8); p += 8
        val sourceVar = readU16(payload, p); p += 2 + 2
        val token = payload.copyOfRange(p, p + 32); p += 32
        val destinationConstant = payload.copyOfRange(p, p + 8); p += 8
        val destinationVar = readU16(payload, p); p += 2
        val playersCount = payload[p++].u8(); val participantsCount = payload[p++].u8()
        require(payload[p++].u8() == 0)
        val ip = ipString(payload.copyOfRange(p, p + 4)); p += 4
        val port = readU16(payload, p); p += 2
        val players = mutableListOf<Player>()
        repeat(playersCount) {
            val id = payload.copyOfRange(p, p + 16); p += 16
            val size = readU32(payload, p).toInt(); p += 4
            val encoding = payload[p++].u8()
            require(size in 0..40 && p + size <= payload.size)
            players += Player(id, encoding, payload.copyOfRange(p, p + size)); p += size
        }
        require(p == payload.size)
        SessionJoin(protocols, sourceConstant, sourceVar, token, destinationConstant, destinationVar,
            playersCount, participantsCount, ip, port, players)
    } catch (_: Exception) { null }

    private fun nextPacket(): Int = nextPacketId.also { nextPacketId = if (it == 0xffff) 1 else it + 1 }
    private fun nextReliablePacket(): Int = nextReliablePacketId.also {
        nextReliablePacketId = if (it == 0xffff) 1 else it + 1
    }
    private fun networkId(): Int = CRC32().apply { update(ssid, 1, 15) }.value.toInt()

    private data class Player(val id: ByteArray, val encoding: Int, val name: ByteArray)
    private data class SessionJoin(
        val protocols: List<Pair<Int, Int>>, val sourceConstantId: ByteArray, val sourceVar: Int,
        val token: ByteArray, val destinationConstantId: ByteArray, val destinationVar: Int,
        val numPlayers: Int, val numParticipants: Int, val ip: String, val port: Int, val players: List<Player>,
    )

    companion object {
        private const val PIA_PORT = 12345
        private const val HOST_VAR = 0x00c6
        private const val SESSION_VAR = 1
        private const val NET_REQUEST = 0x11
        private const val NET_RESPONSE = 0x12
        private const val SESSION_JOIN = 0
        private const val SESSION_UPDATE_ACK = 6
        private const val NET_RETRY_MS = 500L
        private const val SESSION_RETRY_MS = 250L
        private const val RTT_PERIOD_MS = 315L
        private val HOST_NAME = "POKELDN".toByteArray()
        private val DEFAULT_PLAYER_ID = "00000000000000010000000000000000".hex()
    }
}

internal data class PiaHeader(
    val destination: Int, val source: Int, val packetId: Int, val nonce: ByteArray, val flags: Int, val footer: Int,
)
internal data class PiaMessage(val protocol: Int, val payload: ByteArray)
internal data class PiaDecoded(val header: PiaHeader, val messages: List<PiaMessage>)

internal class PiaCrypto(ssid: ByteArray) {
    private val key = Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(FRLG_KEY, "AES")); doFinal(ssid)
    }
    private val networkId = CRC32().apply { update(ssid, 1, 15) }.value.toInt()

    fun encode(plaintext: ByteArray, sourceIp: String, header: PiaHeader): ByteArray {
        val nonce = nonce(sourceIp, header.nonce)
        val ciphertext = gcmCounter(plaintext, nonce)
        return pack(header) + gcmTag(ciphertext, nonce).copyOf(8) + ciphertext
    }

    fun decode(packet: ByteArray, sourceIp: String): PiaDecoded {
        require(packet.size >= 29 && packet.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a PIA packet" }
        val header = PiaHeader(readU16(packet, 6), readU16(packet, 8), readU16(packet, 10),
            packet.copyOfRange(13, 21), packet[5].u8(), packet[12].u8())
        val nonce = nonce(sourceIp, header.nonce)
        val ciphertext = packet.copyOfRange(29, packet.size)
        require(gcmTag(ciphertext, nonce).copyOf(8).contentEquals(packet.copyOfRange(21, 29))) {
            "PIA authentication failed"
        }
        var body = gcmCounter(ciphertext, nonce)
        val pad = header.flags ushr 4
        require(pad <= body.size && body.takeLast(pad).all { it == 0xff.toByte() }) { "invalid PIA padding" }
        if (pad > 0) body = body.copyOf(body.size - pad)
        require(header.footer <= body.size)
        if (header.footer > 0) body = body.copyOf(body.size - header.footer)
        if (header.flags and 1 != 0) {
            body = ZstdInputStream(ByteArrayInputStream(body)).use { it.readBytes() }
        }
        return PiaDecoded(header, parseMessages(body))
    }

    private fun nonce(ip: String, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(networkId xor readU32(ipBytes(ip), 0).toInt()).array() + nonce

    private fun pack(h: PiaHeader) = MAGIC + byteArrayOf(0x90.toByte(), h.flags.toByte()) +
        u16(h.destination) + u16(h.source) + u16(h.packetId) + byteArrayOf(h.footer.toByte()) + h.nonce

    private fun gcmCounter(input: ByteArray, nonce: ByteArray): ByteArray {
        val counter = nonce + byteArrayOf(0, 0, 0, 1)
        val output = ByteArray(input.size)
        var block = 2
        for (offset in input.indices step 16) {
            val ctr = counter.copyOf().also {
                it[12] = (block ushr 24).toByte(); it[13] = (block ushr 16).toByte()
                it[14] = (block ushr 8).toByte(); it[15] = block.toByte()
            }
            val stream = aesBlock(ctr)
            repeat(minOf(16, input.size - offset)) { output[offset + it] = (input[offset + it].toInt() xor stream[it].toInt()).toByte() }
            block++
        }
        return output
    }

    private fun gcmTag(ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val h = aesBlock(ByteArray(16))
        var state = ByteArray(16)
        for (offset in ciphertext.indices step 16) {
            val block = ciphertext.copyOfRange(offset, minOf(offset + 16, ciphertext.size)).copyOf(16)
            state = gfMultiply(xor(state, block), h)
        }
        val lengths = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(0).putLong(ciphertext.size.toLong() * 8).array()
        state = gfMultiply(xor(state, lengths), h)
        return xor(aesBlock(nonce + byteArrayOf(0, 0, 0, 1)), state)
    }

    private fun aesBlock(block: ByteArray): ByteArray = Cipher.getInstance("AES/ECB/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block)
    }

    private fun gfMultiply(x: ByteArray, y: ByteArray): ByteArray {
        val z = ByteArray(16)
        val v = y.copyOf()
        repeat(128) { bit ->
            if ((x[bit / 8].u8() and (0x80 ushr (bit % 8))) != 0) repeat(16) { z[it] = (z[it].toInt() xor v[it].toInt()).toByte() }
            val low = v[15].u8() and 1
            for (i in 15 downTo 0) v[i] = ((v[i].u8() ushr 1) or if (i > 0) ((v[i - 1].u8() and 1) shl 7) else 0).toByte()
            if (low != 0) v[0] = (v[0].u8() xor 0xe1).toByte()
        }
        return z
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(16) { (a[it].toInt() xor b[it].toInt()).toByte() }

    companion object {
        private val MAGIC = "32ab9864".hex()
        private val FRLG_KEY = "83ca7fab734c34633b10183526c1e85b".hex()
    }
}

internal data class LdnUdpDatagram(val sourceIp: String, val sourceMac: ByteArray, val payload: ByteArray)

/** Only ARP and unfragmented IPv4/UDP needed by the upstream userspace PIA transport. */
internal class LdnPiaUdpTransport(private val hostIp: String, private val hostMac: ByteArray) {
    private var identification = 0

    fun receive(frame: ByteArray): Pair<LdnUdpDatagram?, ByteArray?> {
        if (frame.size < 14) return null to null
        return when (readU16(frame, 12)) {
            0x0806 -> null to arpReply(frame)
            0x0800 -> parseUdp(frame) to null
            else -> null to null
        }
    }

    fun frame(destinationIp: String, destinationMac: ByteArray, payload: ByteArray): ByteArray {
        val src = ipBytes(hostIp); val dst = ipBytes(destinationIp)
        val udpLength = 8 + payload.size
        var udp = u16(12345) + u16(12345) + u16(udpLength) + byteArrayOf(0, 0) + payload
        val udpChecksum = checksum(src + dst + byteArrayOf(0, 17) + u16(udpLength) + udp).let { if (it == 0) 0xffff else it }
        udp = udp.copyOf().also { u16(udpChecksum).copyInto(it, 6) }
        val ip = ByteArray(20).also {
            it[0] = 0x45; u16(20 + udp.size).copyInto(it, 2); identification = (identification + 1) and 0xffff
            u16(identification).copyInto(it, 4); it[8] = 64; it[9] = 17; src.copyInto(it, 12); dst.copyInto(it, 16)
            u16(checksum(it)).copyInto(it, 10)
        }
        return destinationMac + hostMac + byteArrayOf(0x08, 0x00) + ip + udp
    }

    private fun parseUdp(frame: ByteArray): LdnUdpDatagram? {
        val ip = frame.copyOfRange(14, frame.size)
        if (ip.size < 28 || ip[0].u8() ushr 4 != 4 || ip[9].u8() != 17) return null
        val ihl = (ip[0].u8() and 15) * 4
        if (ihl < 20 || ip.size < ihl + 8 || readU16(ip, 6) and 0x3fff != 0) return null
        val total = minOf(readU16(ip, 2), ip.size)
        val udpLength = readU16(ip, ihl + 4)
        if (readU16(ip, ihl + 2) != 12345 || udpLength < 8 || ihl + udpLength > total) return null
        val destination = ipString(ip.copyOfRange(16, 20))
        if (destination != hostIp && !destination.endsWith(".255")) return null
        return LdnUdpDatagram(ipString(ip.copyOfRange(12, 16)), frame.copyOfRange(6, 12),
            ip.copyOfRange(ihl + 8, ihl + udpLength))
    }

    private fun arpReply(frame: ByteArray): ByteArray? {
        if (frame.size < 42 || readU16(frame, 20) != 1 || !frame.copyOfRange(38, 42).contentEquals(ipBytes(hostIp))) return null
        val requesterMac = frame.copyOfRange(22, 28); val requesterIp = frame.copyOfRange(28, 32)
        val arp = u16(1) + u16(0x0800) + byteArrayOf(6, 4) + u16(2) + hostMac + ipBytes(hostIp) + requesterMac + requesterIp
        return requesterMac + hostMac + byteArrayOf(0x08, 0x06) + arp
    }
}

private fun message(protocol: Int, payload: ByteArray, messageFlags: Int? = null): ByteArray {
    val flags = 6 or if (messageFlags == null) 0 else 1
    return byteArrayOf(flags.toByte()) +
        (if (messageFlags == null) byteArrayOf() else byteArrayOf(messageFlags.toByte())) +
        u16(payload.size) + byteArrayOf(protocol.toByte()) + payload
}
private fun zstdCompress(data: ByteArray): ByteArray {
    val context = ZstdCompressCtx()
    return try {
        context.setLevel(4).setContentSize(false).compress(data)
    } finally {
        context.close()
    }
}
private fun parseMessages(data: ByteArray): List<PiaMessage> {
    val out = mutableListOf<PiaMessage>(); var p = 0; var size: Int? = null; var protocol: Int? = null
    while (p < data.size) {
        val flags = data[p++].u8(); if (flags == 0xff || flags and 0xe0 != 0) break
        if (flags and 1 != 0) p++
        if (flags and 2 != 0) { if (p + 2 > data.size) break; size = readU16(data, p); p += 2 }
        if (flags and 4 != 0) { if (p >= data.size) break; protocol = data[p++].u8() }
        if (flags and 8 != 0) p++
        if (flags and 0x10 != 0) p++
        val n = size ?: break; val proto = protocol ?: break
        if (p + n > data.size) break
        out += PiaMessage(proto, data.copyOfRange(p, p + n)); p += n
    }
    require(p == data.size) { "invalid PIA message tiling" }
    return out
}
private fun constantId(mac: ByteArray) = byteArrayOf(mac[2], mac[4], mac[5], mac[3], mac[1], mac[0], 0, 0)
private fun ipBytes(ip: String) = ip.split('.').map { it.toInt().toByte() }.toByteArray()
private fun ipString(ip: ByteArray) = ip.joinToString(".") { (it.toInt() and 0xff).toString() }
private fun readU16(data: ByteArray, offset: Int) = (data[offset].u8() shl 8) or data[offset + 1].u8()
private fun readU32(data: ByteArray, offset: Int) = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
private fun Byte.u8() = toInt() and 0xff
private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun checksum(data: ByteArray): Int {
    var sum = 0L; var p = 0
    while (p < data.size) { sum += data[p].u8() shl 8; if (p + 1 < data.size) sum += data[p + 1].u8(); p += 2 }
    while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
    return sum.inv().toInt() and 0xffff
}
private fun ByteArrayOutputStream.writeU16(value: Int) = write(u16(value))
private fun ByteArrayOutputStream.writeU32(value: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
private fun ByteArrayOutputStream.writeU64(value: Long) = write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
