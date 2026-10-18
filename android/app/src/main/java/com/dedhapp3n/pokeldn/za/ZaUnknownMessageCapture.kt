package com.dedhapp3n.pokeldn.za

internal data class ZaGameMessageObservation(
    val protocol: Int,
    val sequence: Int,
    val flags: Int,
    val receivedAtMillis: Long,
    val senderIp: String,
    val senderStation: String,
    val payload: ByteArray,
)

internal data class ZaUnknownMessage(
    val order: Long,
    val protocol: Int,
    val sequence: Int,
    val flags: Int,
    val firstReceivedAtMillis: Long,
    val lastReceivedAtMillis: Long,
    val senderIp: String,
    val senderStation: String,
    val payload: ByteArray,
) {
    val messageId: String get() = if (payload.size < 2) "<missing>" else "%02X%02X".format(payload[0].u(), payload[1].u())
    val channel: String get() = if (protocol == 11) "Broadcast Reliable" else "Reliable"
}

internal data class ZaUnknownMessageCapture(
    val received: Long = 0,
    val messages: List<ZaUnknownMessage> = emptyList(),
) {
    val uniquePayloads: Int get() = messages.indices.count { index ->
        messages.take(index).none { previous ->
            previous.protocol == messages[index].protocol && previous.senderStation == messages[index].senderStation &&
                previous.payload.contentEquals(messages[index].payload)
        }
    }
    val largestPayload: Int get() = messages.maxOfOrNull { it.payload.size } ?: 0
    val capturedBytes: Long get() = messages.sumOf { it.payload.size.toLong() }
    val uniqueBytes: Long get() = messages.indices.filter { index ->
        messages.take(index).none { previous ->
            previous.protocol == messages[index].protocol && previous.senderStation == messages[index].senderStation &&
                previous.payload.contentEquals(messages[index].payload)
        }
    }.sumOf { messages[it].payload.size.toLong() }

    fun record(observation: ZaGameMessageObservation): ZaUnknownMessageCapture {
        val item = ZaUnknownMessage(
            order = received + 1,
            protocol = observation.protocol,
            sequence = observation.sequence,
            flags = observation.flags,
            firstReceivedAtMillis = observation.receivedAtMillis,
            lastReceivedAtMillis = observation.receivedAtMillis,
            senderIp = observation.senderIp,
            senderStation = observation.senderStation,
            payload = observation.payload.copyOf(),
        )
        return copy(received = received + 1, messages = messages + item)
    }

    fun export(): String = buildString {
        appendLine("Z-A passive unknown game-message capture")
        appendLine("received=$received")
        appendLine("uniquePayloads=$uniquePayloads")
        appendLine("capturedBytes=$capturedBytes")
        appendLine("uniqueBytes=$uniqueBytes")
        appendLine("largestPayload=$largestPayload")
        messages.forEach { item ->
            appendLine()
            appendLine("order=${item.order}")
            appendLine("protocol=${item.protocol}")
            appendLine("channel=${item.channel}")
            appendLine("messageId=${item.messageId}")
            appendLine("length=${item.payload.size}")
            appendLine("sequence=${item.sequence}")
            appendLine("flags=0x%02X".format(item.flags))
            appendLine("firstTimestampMillis=${item.firstReceivedAtMillis}")
            appendLine("lastTimestampMillis=${item.lastReceivedAtMillis}")
            appendLine("senderIp=${item.senderIp}")
            appendLine("senderStation=${item.senderStation}")
            appendLine("payload=${item.payload.joinToString("") { "%02X".format(it.u()) }}")
        }
    }.trimEnd()

    companion object {
        fun isKnown(observation: ZaGameMessageObservation): Boolean {
            val payload = observation.payload
            if (payload.size < 2) return false
            return when ((payload[0].u() shl 8) or payload[1].u()) {
                0x1400 -> observation.protocol in setOf(10, 11) && payload.size == 106
                0x1402 -> observation.protocol == 11 && payload.size == 4
                0x1403 -> observation.protocol == 11 && payload.size == 9
                0x0100 -> observation.protocol == 10 && payload.size == 1211
                0x0101 -> observation.protocol == 10 && payload.size == 354
                0x0102, 0x0104, 0x0200 -> observation.protocol == 10 && payload.size == 5
                0x0103 -> observation.protocol == 10 && payload.size == 6
                else -> false
            }
        }
    }
}

private fun Byte.u() = toInt() and 0xff
