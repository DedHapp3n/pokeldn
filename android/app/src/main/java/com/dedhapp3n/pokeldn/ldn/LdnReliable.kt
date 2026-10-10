package com.dedhapp3n.pokeldn.ldn

internal const val RELIABLE_FLAGS_DATA = 0x07
internal const val RELIABLE_FLAGS_INIT = 0x0f
internal const val RELIABLE_FLAGS_CONTROL = 0x00
internal val FRLG_RELIABLE_METADATA =
    ("4a002a005801004c656166477265656e5f65" + "00".repeat(28)).hexBytes()

internal data class LdnReliableEmission(
    val sequence: Int,
    val flags: Int,
    val acknowledgement: Int,
    val payload: ByteArray,
    val retransmitted: Boolean = false,
) {
    val piaMessageFlags: Int?
        get() = when {
            flags == RELIABLE_FLAGS_CONTROL -> 0x40
            retransmitted -> 0x20
            else -> null
        }

    fun encode(): ByteArray = byteArrayOf(flags.toByte()) + u16be(payload.size) +
        u16be(sequence) + u16be(acknowledgement) + byteArrayOf(0) + payload
}

internal data class LdnReliableDelivery(val sequence: Int, val flags: Int, val payload: ByteArray)

internal class LdnReliableSession(
    startSequence: Int = 0xfff0,
    private val acknowledgementPeriodMillis: Long = 33,
    private val maxInflight: Int = 128,
    private val bootstrapRetransmitMillis: Long = 200,
    private val retransmitLimit: Int = 2,
) {
    private data class Sent(
        val flags: Int,
        val payload: ByteArray,
        var sentAt: Long,
        var retransmits: Int = 0,
        var acknowledged: Boolean = false,
    )

    private val start = startSequence and 0xffff
    private var nextSend = start
    private var sendWindow = start
    private var nextReceive = start
    private var nextDelivery = start
    private val unacknowledged = linkedMapOf<Int, Sent>()
    private val receivedOutOfOrder = mutableSetOf<Int>()
    private val deliveryBuffer = mutableMapOf<Int, LdnReliableDelivery>()
    private val rttSamples = ArrayDeque<Double>()
    private var acknowledgementOwed = false
    private var nextAcknowledgementMillis: Long? = null
    private var peerGap: Int? = null
    private var peerGapReports = 0

    var localOpened = false
        private set
    var peerOpened = false
        private set

    val inflight: Int get() = unacknowledged.size
    val outstanding: Int get() = unacknowledged.values.count { !it.acknowledged }
    val receiveNext: Int get() = nextReceive

    fun open(payload: ByteArray, nowMillis: Long, flags: Int = RELIABLE_FLAGS_DATA): LdnReliableEmission? {
        if (localOpened) return null
        localOpened = true
        return queue(payload, flags or RELIABLE_FLAGS_INIT, nowMillis)
    }

    fun send(payload: ByteArray, nowMillis: Long, flags: Int = RELIABLE_FLAGS_DATA): LdnReliableEmission {
        check(localOpened) { "Reliable stream has not been opened" }
        check(inflight < maxInflight) { "Reliable send window is full" }
        require(flags and 1 != 0) { "Reliable data must carry AppData" }
        return queue(payload, flags, nowMillis)
    }

    fun receive(wire: ByteArray, nowMillis: Long): List<LdnReliableDelivery> {
        val frame = decode(wire) ?: return emptyList()
        if (frame.flags and 1 == 0) {
            val (ack, mask) = parseBulkAck(frame.payload) ?: return emptyList()
            acknowledge(ack, mask, nowMillis)
            return emptyList()
        }
        if (!peerOpened && frame.sequence == start && frame.flags and 8 == 0) return emptyList()
        if (frame.flags and 8 != 0) peerOpened = true
        noteReceived(frame.sequence)
        acknowledgementOwed = true
        if (nextAcknowledgementMillis == null) nextAcknowledgementMillis = nowMillis + acknowledgementPeriodMillis
        return deliver(frame)
    }

    fun poll(nowMillis: Long): List<LdnReliableEmission> {
        val out = mutableListOf<LdnReliableEmission>()
        val rto = retransmitTimeout()
        unacknowledged.entries
            .filter { (sequence, sent) ->
                if (sent.acknowledged) false else {
                    val fastGap = sequence == peerGap && peerGapReports >= 1 &&
                        (sent.retransmits == 0 || nowMillis - sent.sentAt >= fastRetransmitGap())
                    fastGap || nowMillis - sent.sentAt >= rto
                }
            }
            .take(retransmitLimit)
            .forEach { (sequence, sent) ->
                sent.sentAt = nowMillis
                sent.retransmits++
                out += emission(sequence, sent.flags, sent.payload, true)
                if (sequence == peerGap) peerGapReports = 0
            }
        if (nextAcknowledgementMillis?.let { nowMillis >= it } == true &&
            (acknowledgementOwed || receivedOutOfOrder.isNotEmpty())) {
            out += LdnReliableEmission(start, RELIABLE_FLAGS_CONTROL, sendLow(), acknowledgementPayload())
            acknowledgementOwed = false
            nextAcknowledgementMillis = if (receivedOutOfOrder.isEmpty()) null else nowMillis + acknowledgementPeriodMillis
        }
        return out
    }

    fun noteRtt(milliseconds: Double) {
        if (milliseconds < 0) return
        rttSamples += milliseconds
        while (rttSamples.size > 7) rttSamples.removeFirst()
    }

    fun reset() {
        nextSend = start; sendWindow = start; nextReceive = start; nextDelivery = start
        unacknowledged.clear(); receivedOutOfOrder.clear(); deliveryBuffer.clear(); rttSamples.clear()
        acknowledgementOwed = false; nextAcknowledgementMillis = null
        localOpened = false; peerOpened = false
        peerGap = null; peerGapReports = 0
    }

    private fun queue(payload: ByteArray, flags: Int, nowMillis: Long): LdnReliableEmission {
        val sequence = nextSend
        nextSend = (nextSend + 1) and 0xffff
        unacknowledged[sequence] = Sent(flags, payload.copyOf(), nowMillis)
        return emission(sequence, flags, payload)
    }

    private fun emission(sequence: Int, flags: Int, payload: ByteArray, retransmitted: Boolean = false) =
        LdnReliableEmission(sequence, flags, sendLow(), payload.copyOf(), retransmitted)

    private fun acknowledge(ack: Int, mask: ByteArray, nowMillis: Long) {
        val bits = maskBitSet(mask)
        unacknowledged.forEach { (sequence, sent) ->
            val acknowledged = sequenceBefore(sequence, ack) ||
                (((sequence - ack - 1) and 0xffff) < 128 && bits((sequence - ack - 1) and 0xffff))
            if (acknowledged && !sent.acknowledged) {
                if (sent.retransmits == 0) noteRtt((nowMillis - sent.sentAt).toDouble())
                sent.acknowledged = true
            }
        }
        while (unacknowledged[sendWindow]?.acknowledged == true) {
            unacknowledged.remove(sendWindow)
            sendWindow = (sendWindow + 1) and 0xffff
        }
        val hasMask = mask.any { it.toInt() != 0 }
        if (hasMask && unacknowledged[ack]?.acknowledged == false) {
            if (peerGap == ack) peerGapReports++ else {
                peerGap = ack
                peerGapReports = 1
            }
        } else {
            peerGap = null
            peerGapReports = 0
        }
    }

    private fun noteReceived(sequence: Int) {
        when {
            sequence == nextReceive -> {
                nextReceive = (nextReceive + 1) and 0xffff
                while (receivedOutOfOrder.remove(nextReceive)) nextReceive = (nextReceive + 1) and 0xffff
            }
            sequenceBefore(nextReceive, sequence) -> receivedOutOfOrder += sequence
        }
    }

    private fun deliver(frame: LdnReliableEmission): List<LdnReliableDelivery> {
        if (frame.sequence == nextDelivery) {
            val ready = mutableListOf(LdnReliableDelivery(frame.sequence, frame.flags, frame.payload.copyOf()))
            nextDelivery = (nextDelivery + 1) and 0xffff
            while (true) {
                val buffered = deliveryBuffer.remove(nextDelivery) ?: break
                ready += buffered
                nextDelivery = (nextDelivery + 1) and 0xffff
            }
            return ready
        }
        if (sequenceBefore(nextDelivery, frame.sequence)) {
            deliveryBuffer.putIfAbsent(frame.sequence, LdnReliableDelivery(frame.sequence, frame.flags, frame.payload.copyOf()))
        }
        return emptyList()
    }

    private fun acknowledgementPayload(): ByteArray {
        val mask = ByteArray(16)
        receivedOutOfOrder.forEach { sequence ->
            val bit = (sequence - nextReceive - 1) and 0xffff
            if (bit < 128) mask[bit ushr 3] = (mask[bit ushr 3].toInt() or (1 shl (bit and 7))).toByte()
        }
        return byteArrayOf(0, 1) + u16be(nextReceive) + mask
    }

    private fun retransmitTimeout(): Long {
        if (rttSamples.isEmpty()) return bootstrapRetransmitMillis
        val sorted = rttSamples.sorted()
        return (33.0 + 1.4 * sorted[sorted.size / 2]).toLong()
    }

    private fun fastRetransmitGap(): Long = if (rttSamples.isEmpty()) 25 else
        maxOf(25, rttSamples.sorted()[rttSamples.size / 2].toLong())

    private fun sendLow() = if (unacknowledged.isEmpty()) nextSend else sendWindow

    companion object {
        fun decode(wire: ByteArray): LdnReliableEmission? {
            if (wire.size < 8) return null
            val size = readU16be(wire, 1)
            if (size > wire.size - 8) return null
            return LdnReliableEmission(
                readU16be(wire, 3), wire[0].toInt() and 0xff, readU16be(wire, 5),
                wire.copyOfRange(8, 8 + size),
            )
        }

        fun parseBulkAck(payload: ByteArray): Pair<Int, ByteArray>? = if (payload.size < 4) null else
            readU16be(payload, 2) to payload.copyOfRange(4, minOf(payload.size, 20)).copyOf(16)

        private fun maskBitSet(mask: ByteArray): (Int) -> Boolean = { bit ->
            mask[bit ushr 3].toInt() and (1 shl (bit and 7)) != 0
        }
    }
}

private fun sequenceBefore(left: Int, right: Int): Boolean {
    val distance = (right - left) and 0xffff
    return distance != 0 && distance < 0x8000
}

private fun readU16be(data: ByteArray, offset: Int) =
    ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

private fun u16be(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
private fun String.hexBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
