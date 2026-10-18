package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.ldn.LdnReliableDelivery
import com.dedhapp3n.pokeldn.ldn.LdnReliableEmission
import com.dedhapp3n.pokeldn.ldn.LdnReliableSession
import com.dedhapp3n.pokeldn.ldn.RELIABLE_FLAGS_CONTROL

/** A Z-A game stream. Protocol 11's station prefix is outside its declared frame length. */
internal class ZaReliableStream(val protocol: Int) {
    init { require(protocol == 10 || protocol == 11) }
    private val link = LdnReliableSession(bootstrapRetransmitMillis = 500, retransmitLimit = 4)
    val opened: Boolean get() = link.peerOpened
    val outstanding: Int get() = link.outstanding
    val inflight: Int get() = link.inflight

    fun open(message: ByteArray, now: Long): List<LdnReliableEmission> =
        link.open(wrap(message), now)?.let(::listOf).orEmpty()

    fun send(message: ByteArray, now: Long): List<LdnReliableEmission> = listOf(link.send(wrap(message), now))

    fun receive(wire: ByteArray, now: Long): List<LdnReliableDelivery> {
        if (protocol == 10) return link.receive(wire, now)
        if (wire.size < 8) return emptyList()
        val declared = u16(wire, 1)
        if (wire[0].toInt() == RELIABLE_FLAGS_CONTROL) {
            // A joiner's ACK for the host's broadcast stream occupies station entry 0.
            if (wire.size < 8 + 4 + 2 + 18) return emptyList()
            val ack = byteArrayOf(0, 1) + wire.copyOfRange(14, 32)
            val normalized = wire.copyOfRange(0, 8) + ack
            put16(normalized, 1, ack.size)
            link.receive(normalized, now)
            return emptyList()
        }
        if (wire.size < 8 + declared + 4) return emptyList()
        val normalized = wire.copyOf()
        put16(normalized, 1, declared + 4)
        return link.receive(normalized, now).mapNotNull { delivery ->
            if (delivery.payload.size < 4) null else delivery.copy(payload = delivery.payload.copyOfRange(4, delivery.payload.size))
        }
    }

    fun poll(now: Long): List<LdnReliableEmission> = link.poll(now)

    fun frame(emission: LdnReliableEmission): ByteArray {
        if (protocol == 10) return emission.encode()
        if (emission.flags == RELIABLE_FLAGS_CONTROL) {
            val next = u16(emission.payload, 2)
            val mask = emission.payload.copyOfRange(4, 20)
            val body = byteArrayOf(0, 0, 0, 2, 0, 4) +
                byteArrayOf(0xff.toByte(), 0xf0.toByte()) + ByteArray(16) +
                byteArrayOf((next ushr 8).toByte(), next.toByte()) + mask +
                (byteArrayOf(0xff.toByte(), 0xf0.toByte()) + ByteArray(16)).let { it + it }
            val frame = emission.copy(payload = body).encode()
            put16(frame, 1, body.size - 4)
            return frame
        }
        val frame = emission.encode().also { it[7] = 3 }
        put16(frame, 1, emission.payload.size - 4)
        return frame
    }

    private fun wrap(message: ByteArray): ByteArray = if (protocol == 11) byteArrayOf(0, 0, 0, 2) + message else message
    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    private fun put16(bytes: ByteArray, at: Int, value: Int) { bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte() }
}
