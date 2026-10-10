package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram
import com.dedhapp3n.pokeldn.ldn.LdnPiaHost

internal data class FrlgHostLinkSnapshot(
    val rfuConnected: Boolean,
    val rfuReady: Boolean,
    val linkPlayerExchanged: Boolean,
    val cartridge: FrlgCartridge?,
    val gift: FrlgGiftSnapshot?,
    val detail: String?,
)

/** Operation scoped adapter between PIA Reliable protocol 10 and the FRLG RFU leader. */
internal class FrlgHostLink(
    private val pia: LdnPiaHost,
    parentSessionId: ByteArray,
    private val runWalkThroughWalls: Boolean = false,
) {
    private val rfu = FrlgRfuLeader(parentSessionId)
    private val link = FrlgLinkPlayerExchange()
    private var connectSequence: Int? = null
    private var localReliableOpened = false
    private var detail: String? = null
    private var gift: FrlgMysteryGiftEngine? = null
    private var disconnectSent = false

    fun tick(nowMillis: Long): List<LdnPiaDatagram> {
        pia.drainReliableDeliveries().forEach { delivery ->
            when (val event = rfu.receive(delivery.payload)) {
                "connect" -> {
                    connectSequence = delivery.sequence
                    detail = "RFU connection request received"
                }
                "child_ni_complete" -> detail = "RFU identity received"
                "uni" -> {
                    val activeGift = gift
                    if (activeGift != null) activeGift.receive(rfu.childCommand) else {
                        link.receive(rfu.childCommand)
                        ensureGift()
                    }
                    detail = gift?.snapshot()?.detail ?: when (link.stage) {
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
        if (gift?.disconnectRequested == true && !disconnectSent) {
            disconnectSent = true
            val payload = rfu.disconnect() ?: return emptyList()
            gift?.markDisconnected()
            return listOf(pia.sendReliable(payload, nowMillis))
        }
        if (pia.reliableOutstanding >= 6) return emptyList()
        val parentWords = if (rfu.state == FrlgRfuState.UNI) {
            gift?.tick() ?: link.tick().also { ensureGift() }
        } else null
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
        cartridge = gift?.snapshot()?.cartridge ?: link.child?.cartridge,
        gift = gift?.snapshot(),
        detail = gift?.snapshot()?.detail ?: detail,
    )

    fun close(nowMillis: Long): LdnPiaDatagram? = rfu.disconnect()?.let { pia.sendReliable(it, nowMillis) }

    private fun ensureGift() {
        if (!runWalkThroughWalls || gift != null || link.stage != FrlgLinkStage.ESTABLISHED) return
        link.child?.cartridge?.let { gift = FrlgMysteryGiftEngine(it, link.standbyCount ?: 0) }
    }
}
