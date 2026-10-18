package com.dedhapp3n.pokeldn.za

/** Outbound game messages allowed during an inspector session, before PIA framing. */
internal object ZaReadOnlyPolicy {
    fun requireSafeApplicationMessage(protocol: Int, message: ByteArray) {
        require(message.size >= 2) { "Empty Z-A message" }
        val id = ((message[0].toInt() and 255) shl 8) or (message[1].toInt() and 255)
        when (id) {
            0x1400 -> require((protocol == 10 || protocol == 11) && message.size == 106) { "Identity uses Reliable" }
            0x1403 -> require(protocol == 11 && message.size == 9) { "Identity sync uses Broadcast Reliable" }
            0x0100 -> require(protocol == 10 && message.size == 1211) { "Selection uses Reliable" }
            0x0101 -> require(protocol == 10 && message.size == 354 && ZaMessages.preview(message).shown) {
                "Only a non-pick preview may be sent"
            }
            else -> throw IllegalArgumentException("Unsafe Z-A application message ${id.toString(16)}")
        }
    }

    fun shouldCloseForInbound(protocol: Int, message: ByteArray): Boolean {
        if (protocol != 10 || message.size < 2) return false
        return when (((message[0].toInt() and 255) shl 8) or (message[1].toInt() and 255)) {
            0x0101 -> message.lastOrNull()?.toInt() == 0 // console chose a Pokémon
            0x0102, 0x0104, 0x0200 -> true
            else -> false
        }
    }
}
