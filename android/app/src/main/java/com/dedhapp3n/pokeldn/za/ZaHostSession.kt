package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.ldn.LdnParticipant
import com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram
import com.dedhapp3n.pokeldn.ldn.PiaCrypto
import com.dedhapp3n.pokeldn.ldn.PiaHeader
import com.dedhapp3n.pokeldn.ldn.PiaNonceSequence
import com.dedhapp3n.pokeldn.ldn.constantId
import com.dedhapp3n.pokeldn.ldn.ipBytes
import com.dedhapp3n.pokeldn.ldn.message
import com.dedhapp3n.pokeldn.ldn.u16
import com.dedhapp3n.pokeldn.ldn.zstdCompress
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.CRC32

internal data class ZaHostStatus(
    val phase: String = "WAITING FOR Z-A",
    val participant: Boolean = false,
    val netStatuses: Int = 0,
    val sessionJoins: Int = 0,
    val update0Ack: Boolean = false,
    val update1Ack: Boolean = false,
    val protocol10Open: Boolean = false,
    val protocol11Open: Boolean = false,
    val selectionSent: Int = 0,
    val outstanding10: Int = 0,
    val outstanding11: Int = 0,
    val leavePhase: String? = null,
    val lastError: String? = null,
)

/** The read-only subset of current upstream za.host.HostSession. One seated console. */
internal class ZaHostSession(
    private val ssid: ByteArray,
    private val hostMac: ByteArray,
    private val hostIp: String,
    private val code: String,
    private val identity: ByteArray,
    private val selection: ByteArray,
    private val onGameMessage: (ByteArray) -> Unit,
    private val random: SecureRandom = SecureRandom(),
    hostVariable: Int? = null,
) {
    private val crypto = PiaCrypto(ssid, ZaNetworkParameters.GAME_KEY, ZaNetworkParameters.PIA_VERSION)
    private val nonces = PiaNonceSequence(ByteArray(8).also(random::nextBytes))
    private val hostConstant = constantId(hostMac)
    private val networkId = CRC32().apply { update(ssid, 1, 15) }.value
    private val hostVar = hostVariable ?: random.nextInt(0xffef) + 2
    private val stream10 = ZaReliableStream(10)
    private val stream11 = ZaReliableStream(11)
    private val pending = ArrayDeque<LdnPiaDatagram>()
    private val scheduled = mutableListOf<Pair<Long, ByteArray>>()
    private var guest: LdnParticipant? = null
    private var join: Join? = null
    private var netAcked = false
    private var propertyAcked = false
    private var nextNet = 0L
    private var nextProperty = Long.MAX_VALUE
    private var nextUpdate = Long.MAX_VALUE
    private var nextRtt = Long.MAX_VALUE
    private var update0Ack = false
    private var update1Ack = false
    private var hostPacket = 1
    private var meshPacket = 1
    private var netSequence = 2
    private var leavePhase: String? = null
    private var leaveSince = 0L
    private var leaveNext = 0L
    private var handoverSpan = 4000L
    var departed = false
        private set
    var status = ZaHostStatus()
        private set

    init {
        ZaReadOnlyPolicy.requireSafeApplicationMessage(10, identity)
        ZaReadOnlyPolicy.requireSafeApplicationMessage(11, identity)
        ZaReadOnlyPolicy.requireSafeApplicationMessage(10, selection)
        ZaReadOnlyPolicy.requireSafeApplicationMessage(11, ZaReference.syncMessage(identity))
    }

    fun participantJoined(value: LdnParticipant, now: Long) {
        if (guest?.mac?.contentEquals(value.mac) == true) return
        guest = value
        nextNet = now
        status = status.copy(phase = "Z-A CONNECTED", participant = true)
    }

    fun receive(packet: ByteArray, sourceIp: String, now: Long) {
        if (sourceIp != guest?.ipAddress || departed) return
        val decoded = try { crypto.decode(packet, sourceIp) } catch (error: Exception) {
            status = status.copy(lastError = error.message)
            return
        }
        for (item in decoded.messages) {
            when (item.protocol) {
                1 -> when (item.payload.getOrNull(1)?.u()) {
                    0x12 -> {
                        if (leavePhase == "status" && item.payload.size >= 8 && item.payload.u32(4) == netSequence.toLong()) enterLeave("handover", now)
                        else netAcked = true
                    }
                    0x51 -> propertyAcked = true
                }
                13 -> onSession(item.payload, now)
                3 -> if (item.payload.firstOrNull()?.u() == 0 && join != null) {
                    val reply = item.payload.copyOf(21).also { it[0] = 1 }
                    send(3, reply, 1)
                }
                10, 11 -> {
                    val stream = if (item.protocol == 10) stream10 else stream11
                    stream.receive(item.payload, now).forEach { delivery ->
                        if (item.protocol == 10 && delivery.payload.size >= 2) {
                            onGameMessage(delivery.payload)
                            if (ZaReadOnlyPolicy.shouldCloseForInbound(10, delivery.payload)) leave(now)
                        }
                    }
                    status = status.copy(protocol10Open = stream10.opened, protocol11Open = stream11.opened)
                }
            }
        }
    }

    fun tick(now: Long): List<LdnPiaDatagram> {
        if (departed) return drain()
        if (leavePhase != null) {
            leaveTick(now)
            return drain()
        }
        if (guest == null) return drain()
        if (!netAcked && now >= nextNet) {
            send(1, netStatus(), 0, establishing = true, footer = false, packetId = 0)
            nextNet = now + 456
            status = status.copy(netStatuses = status.netStatuses + 1)
        }
        if (join != null && now >= nextUpdate) {
            sendUpdate(if (update0Ack) 1 else 0)
            nextUpdate = now + 1000
        }
        if (join != null && !propertyAcked && now >= nextProperty) {
            send(1, netProperty(), 0, establishing = true, footer = false, packetId = 0)
            nextProperty = now + 500
        }
        if (join != null && now >= nextRtt) {
            // Upstream sends a 21-byte type-0 clock request every 310 ms.
            val micros = now * 1000
            val rtt = byteArrayOf(0) + be64(now * 19_200) + be64(micros) + byteArrayOf(0, 0) + u16(hostVar)
            send(3, rtt, 1)
            nextRtt = now + 310
        }
        if (join != null) {
            for (stream in listOf(stream10, stream11)) {
                stream.poll(now).forEach { emit(stream, it) }
            }
            val ready = scheduled.filter { it.first <= now }
            scheduled.removeAll(ready.toSet())
            ready.forEach { (_, data) ->
                ZaReadOnlyPolicy.requireSafeApplicationMessage(10, data)
                stream10.send(data, now).forEach { emit(stream10, it) }
                status = status.copy(selectionSent = status.selectionSent + 1)
            }
            status = status.copy(outstanding10 = stream10.outstanding, outstanding11 = stream11.outstanding)
        }
        return drain()
    }

    fun leave(now: Long) {
        if (leavePhase != null || departed) return
        if (join == null) { departed = true; return }
        enterLeave("migration", now)
    }

    private fun onSession(payload: ByteArray, now: Long) {
        when (payload.firstOrNull()?.u()) {
            0 -> {
                val parsed = parseJoin(payload) ?: return
                val first = join == null
                join = parsed
                if (!first && (update0Ack || update1Ack)) return
                send(13, joinResponse(parsed), parsed.sourceVar)
                sendUpdate(0)
                nextUpdate = now + 1000
                status = status.copy(phase = "ESTABLISHING SESSION", sessionJoins = status.sessionJoins + 1)
                if (first) {
                    stream10.open(identity, now).forEach { emit(stream10, it) }
                    stream11.open(identity, now).forEach { emit(stream11, it) }
                    stream11.send(ZaReference.syncMessage(identity), now).forEach { emit(stream11, it) }
                    send(1, netProperty(), 0, establishing = true, footer = false, packetId = 0)
                    nextProperty = now + 500
                    nextRtt = now + 190
                }
            }
            // Type-6 ACK: type, eight-byte guest constant ID, big-endian update sequence, 0x0001.
            // The constant ID belongs to this console; it is never a protocol literal.
            6 -> if (payload.size >= 15) {
                val sequence = payload.u32(9).toInt()
                if (sequence == 0 && !update0Ack) { update0Ack = true; nextUpdate = now + 1150 }
                if (sequence == 1 && !update1Ack) {
                    update1Ack = true; nextUpdate = Long.MAX_VALUE
                    scheduled += (now + 60) to selection
                    scheduled += (now + 120) to selection
                    status = status.copy(phase = "READING POKÉMON")
                }
                status = status.copy(update0Ack = update0Ack, update1Ack = update1Ack)
            }
            3 -> if (payload.size >= 15) {
                send(13, byteArrayOf(4) + ByteArray(4).also(random::nextBytes) + payload.copyOfRange(5, 15), join?.sourceVar ?: return)
            }
            10 -> if (leavePhase == "migration" && payload.size == 21 && join?.let {
                payload.copyOfRange(1, 9).contentEquals(it.sourceConstant) && payload.u16(9) == it.sourceVar
            } == true) enterLeave("status", now)
        }
    }

    private fun sendUpdate(sequence: Int) {
        val joined = join ?: return
        send(13, sessionUpdate(joined, sequence), 1)
    }

    private fun emit(stream: ZaReliableStream, emission: com.dedhapp3n.pokeldn.ldn.LdnReliableEmission) {
        send(stream.protocol, stream.frame(emission), if (stream.protocol == 10) join!!.sourceVar else 1,
            flags = emission.piaMessageFlags)
    }

    private fun send(protocol: Int, payload: ByteArray, destination: Int, flags: Int? = null,
                     establishing: Boolean = false, footer: Boolean = true, packetId: Int? = null, source: Int = hostVar) {
        val peer = guest ?: return
        val plain = message(protocol, payload, flags)
        val compressed = zstdCompress(plain)
        val useCompression = compressed.size < plain.size
        val body = (if (useCompression) compressed else plain) + (if (footer && join != null) u16(join!!.sourceVar) else byteArrayOf())
        val padding = (-body.size).mod(16)
        val packet = crypto.encode(body + ByteArray(padding) { 0xff.toByte() }, hostIp,
            PiaHeader(destination, source,
                packetId ?: if (destination == 1) meshPacket.also { meshPacket = if (it == 0xffff) 1 else it + 1 }
                else hostPacket.also { hostPacket = if (it == 0xffff) 1 else it + 1 },
                nonces.take(), (padding shl 4) or (if (useCompression) 1 else 0) or (if (establishing) 2 else 0),
                if (footer && join != null) 2 else 0))
        pending += LdnPiaDatagram(peer.ipAddress, packet)
    }

    private fun netStatus(migrating: Boolean = false): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(1, 0x11, 0, 0x58))
        write(be32(netSequence)); write(u16(hostVar)); write(hostConstant); write(be64(networkId))
        write(if (migrating) 2 else 1); write(u16(4)); write(if (migrating) 1 else 0)
        listOf(hostIp, guest!!.ipAddress).forEachIndexed { rank, ip ->
            write(byteArrayOf(0, rank.toByte(), 0, 0)); write(ipBytes(ip)); write(ByteArray(12)); write(u16(12345))
        }
        repeat(2) { write(byteArrayOf(0, 0xff.toByte(), 0, 0)); write(ByteArray(18)) }
    }.toByteArray()

    private fun netProperty(): ByteArray {
        val app = ZaAdvertisementBuilder.buildApplicationData(code, numPlayers = 2)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(1, 0x50)); write(u16(app.size)); write(be32(1)); write(be64(networkId))
            write(u16(2)); write(u16(4)); write(ByteArray(6)); write(u16(1)); write(byteArrayOf(2, 1))
            write(be32(0x5c)); write(be32(20)); write(app)
        }.toByteArray()
    }

    private fun joinResponse(value: Join): ByteArray = byteArrayOf(2, 13, 7, 1) + ByteArray(4) +
        ByteArray(4).also(random::nextBytes) + hostConstant + u16(hostVar) + value.sourceConstant + u16(value.sourceVar) +
        byteArrayOf(1, 0, 1, 0, 0)

    private fun sessionUpdate(value: Join, sequence: Int): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(5)); write(u16(sequence)); write(byteArrayOf(1, 0, 0, 3))
        write(hostConstant); write(u16(hostVar)); write(byteArrayOf(2, 0)); write(u16(1)); write(u16(sequence)); write(ByteArray(4))
        write(station(hostConstant, hostVar, hostIp, 0, byteArrayOf(6) + ByteArray(31),
            listOf(Player(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + ByteArray(8), 1, byteArrayOf(0x20)))))
        write(station(value.sourceConstant, value.sourceVar, value.ip, 1, value.token, value.players))
    }.toByteArray()

    private fun station(constant: ByteArray, variable: Int, ip: String, index: Int, token: ByteArray, players: List<Player>) =
        ByteArrayOutputStream().apply {
            write(constant); write(u16(variable)); write(ipBytes(ip)); write(u16(12345)); write(index)
            write(u16(if (index == 0) 0 else 1)); write(ByteArray(2)); write(token)
            write(1); write(1); write(ByteArray(2))
            players.forEach { write(it.id); write(be32(it.name.size)); write(it.encoding); write(it.name) }
        }.toByteArray()

    private fun parseJoin(payload: ByteArray): Join? {
        return try {
        if (payload.size < 90 || payload[0].u() != 0) return null
        val count = payload[1].u()
        val protocols = (0 until count).map { payload[2 + it * 2].u() to payload[3 + it * 2].u() }
        if (protocols != listOf(1 to 0, 3 to 5, 5 to 1, 6 to 0, 9 to 1, 10 to 3, 11 to 4, 12 to 4, 13 to 7, 15 to 0)) return null
        var at = 2 + count * 2
        if (payload[at].u() != 0 || payload[at + 1].u() != 6) return null
        at += 2 + 4
        val sourceConstant = payload.copyOfRange(at, at + 8); at += 8
        val sourceVar = payload.u16(at); at += 2 + 2
        val token = payload.copyOfRange(at, at + 32); at += 32
        val destinationConstant = payload.copyOfRange(at, at + 8); at += 8
        val destinationVar = payload.u16(at); at += 2
        val playersCount = payload[at++].u(); at++
        if (payload[at++].u() != 0) return null
        val ip = payload.copyOfRange(at, at + 4).joinToString(".") { it.u().toString() }; at += 4
        at += 2
        val players = (0 until playersCount).map {
            val id = payload.copyOfRange(at, at + 16); at += 16
            val length = payload.u32(at).toInt(); at += 4
            val encoding = payload[at++].u()
            require(length in 0..40)
            val name = payload.copyOfRange(at, at + length); at += length
            Player(id, encoding, name)
        }
        require(at == payload.size && destinationConstant.contentEquals(hostConstant) && destinationVar == hostVar)
        require(ip == guest?.ipAddress && players.isNotEmpty())
        Join(sourceConstant, sourceVar, token, ip, players)
        } catch (_: Exception) { null }
    }

    private fun enterLeave(phase: String, now: Long) {
        leavePhase = phase; leaveSince = now; leaveNext = now
        if (phase == "status") netSequence++
        status = status.copy(phase = "FINISHING", leavePhase = phase)
    }

    private fun leaveTick(now: Long) {
        when (leavePhase) {
            "migration" -> if (now - leaveSince >= 5000) enterLeave("status", now)
            "status" -> if (now - leaveSince >= 4000) { handoverSpan = 2000; enterLeave("handover", now) }
            "handover" -> if (now - leaveSince >= handoverSpan) {
                departed = true; leavePhase = null; status = status.copy(phase = "COMPLETE", leavePhase = "done"); return
            }
        }
        if (now < leaveNext) return
        when (leavePhase) {
            "migration" -> {
                val joined = join ?: return
                send(13, byteArrayOf(9) + hostConstant + u16(hostVar) + byteArrayOf(0) + ipBytes(hostIp) + u16(12345) +
                    joined.sourceConstant + u16(joined.sourceVar) + byteArrayOf(0, 1), joined.sourceVar)
                leaveNext = now + 1000
            }
            "status" -> { send(1, netStatus(true), 0, establishing = true, footer = false, packetId = 0, source = 0); leaveNext = now + 500 }
            "handover" -> { send(1, byteArrayOf(1, 0x40, 0, 0), 0, establishing = true, footer = false, packetId = 0, source = 0); leaveNext = now + 300 }
        }
    }

    private fun drain() = buildList { while (pending.isNotEmpty()) add(pending.removeFirst()) }
    private data class Player(val id: ByteArray, val encoding: Int, val name: ByteArray)
    private data class Join(val sourceConstant: ByteArray, val sourceVar: Int, val token: ByteArray, val ip: String, val players: List<Player>)
    private fun Byte.u() = toInt() and 255
    private fun ByteArray.u16(at: Int) = (this[at].u() shl 8) or this[at + 1].u()
    private fun ByteArray.u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun be64(value: Long) = byteArrayOf((value ushr 56).toByte(), (value ushr 48).toByte(), (value ushr 40).toByte(), (value ushr 32).toByte(),
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
