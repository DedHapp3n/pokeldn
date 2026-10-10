package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram
import com.dedhapp3n.pokeldn.ldn.LdnPiaHost

internal data class FrlgHostLinkSnapshot(
    val rfuConnected: Boolean,
    val rfuReady: Boolean,
    val linkPlayerExchanged: Boolean,
    val cartridge: FrlgCartridge?,
    val detail: String?,
)

/** Operation scoped adapter between PIA Reliable protocol 10 and the FRLG RFU leader. */
internal class FrlgHostLink(
    private val pia: LdnPiaHost,
    parentSessionId: ByteArray,
) {
    private val rfu = FrlgRfuLeader(parentSessionId)
    private val link = FrlgLinkPlayerExchange()
    private var connectSequence: Int? = null
    private var localReliableOpened = false
    private var detail: String? = null

    fun tick(nowMillis: Long): List<LdnPiaDatagram> {
        pia.drainReliableDeliveries().forEach { delivery ->
            when (val event = rfu.receive(delivery.payload)) {
                "connect" -> {
                    connectSequence = delivery.sequence
                    detail = "RFU connection request received"
                }
                "child_ni_complete" -> detail = "RFU identity received"
                "uni" -> {
                    link.receive(rfu.childCommand)
                    detail = when (link.stage) {
                        FrlgLinkStage.ESTABLISHED -> "LinkPlayer exchanged"
                        FrlgLinkStage.FAILED -> link.error
                        else -> "RFU connected"
                    }
                }
                "disconnect" -> detail = "RFU disconnected"
                else -> if (event != null && detail == null) detail = event
            }
        }
        val connect = connectSequence ?: return emptyList()
        if (!pia.reliableReceiveAcknowledgementSent(connect)) return emptyList()
        val parentWords = if (rfu.state == FrlgRfuState.UNI) link.tick() else null
        val payload = rfu.tick(parentWords) ?: return emptyList()
        val datagram = if (!localReliableOpened) {
            localReliableOpened = true
            pia.openReliable(payload, nowMillis)
        } else pia.sendReliable(payload, nowMillis)
        return listOfNotNull(datagram)
    }

    fun snapshot() = FrlgHostLinkSnapshot(
        rfuConnected = rfu.state != FrlgRfuState.WAIT_CONNECT && rfu.state != FrlgRfuState.DISCONNECTED,
        rfuReady = rfu.state == FrlgRfuState.UNI,
        linkPlayerExchanged = link.stage == FrlgLinkStage.ESTABLISHED,
        cartridge = link.child?.cartridge,
        detail = detail,
    )

    fun close(nowMillis: Long): LdnPiaDatagram? = rfu.disconnect()?.let { pia.sendReliable(it, nowMillis) }
}
