package com.dedhapp3n.pokeldn.frlg

import com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram
import com.dedhapp3n.pokeldn.ldn.LdnPiaHost

internal data class FrlgHostLinkSnapshot(
    val rfuConnected: Boolean,
    val rfuReady: Boolean,
    val linkPlayerExchanged: Boolean,
    val cartridge: FrlgCartridge?,
    val gift: FrlgGiftSnapshot?,
    val operationStage: FrlgGiftStage?,
    val linkExchange: FrlgLinkExchangeSnapshot,
    val echoBacklog: Int,
    val echoPeak: Int,
    val echoDropped: Int,
    val echoCoalesced: Int,
    val rfuUniIn: Long,
    val rfuUniOut: Long,
    val reliableOutstanding: Int,
    val linkWireInit: Long,
    val linkWireFragments: Long,
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
    private var linkWireInit = 0L
    private var linkWireFragments = 0L
    private var peerDepartureStage: FrlgGiftStage? = null

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
        val linkPlayerWirePhase = gift == null && link.stage == FrlgLinkStage.SENDING_HOST
        val parentWords = if (rfu.state == FrlgRfuState.UNI) {
            gift?.tick() ?: link.tick().also { ensureGift() }
        } else null
        val payload = rfu.tick(parentWords) ?: return emptyList()
        val datagram = if (!localReliableOpened) {
            localReliableOpened = true
            pia.openReliable(payload, nowMillis)
        } else pia.sendReliable(payload, nowMillis)
        if (datagram != null && parentWords != null && linkPlayerWirePhase) {
            when (parentWords[0] and FrlgRfu.MASK) {
                FrlgRfu.SEND_BLOCK_INIT -> linkWireInit++
                FrlgRfu.SEND_BLOCK -> linkWireFragments++
            }
        }
        return listOfNotNull(datagram)
    }

    fun snapshot(): FrlgHostLinkSnapshot {
        val giftSnapshot = gift?.snapshot()
        return FrlgHostLinkSnapshot(
            rfuConnected = rfu.state != FrlgRfuState.WAIT_CONNECT && rfu.state != FrlgRfuState.DISCONNECTED,
            rfuReady = rfu.state == FrlgRfuState.UNI,
            linkPlayerExchanged = link.stage == FrlgLinkStage.ESTABLISHED,
            cartridge = giftSnapshot?.cartridge ?: link.child?.cartridge,
            gift = giftSnapshot,
            operationStage = giftSnapshot?.stage ?: peerDepartureStage,
            linkExchange = link.snapshot(),
            echoBacklog = rfu.echoBacklog,
            echoPeak = rfu.echoBacklogPeak,
            echoDropped = rfu.echoDropped,
            echoCoalesced = rfu.echoCoalesced,
            rfuUniIn = rfu.uniIn,
            rfuUniOut = rfu.uniOut,
            reliableOutstanding = pia.reliableOutstanding,
            linkWireInit = linkWireInit,
            linkWireFragments = linkWireFragments,
            detail = giftSnapshot?.detail ?: detail,
        )
    }

    fun onPeerLeft() {
        val activeGift = gift
        if (activeGift != null) {
            activeGift.onPeerLeft()
            detail = activeGift.snapshot().detail
        } else {
            peerDepartureStage = FrlgGiftStage.FAILED
            detail = "Console disconnected before the operation completed"
        }
    }

    fun close(nowMillis: Long): LdnPiaDatagram? = rfu.disconnect()?.let { pia.sendReliable(it, nowMillis) }

    private fun ensureGift() {
        if (!runWalkThroughWalls || gift != null || !link.readyForGift) return
        link.child?.cartridge?.let {
            gift = FrlgMysteryGiftEngine(it, link.standbyCount ?: 0, echoStartupStandby = false)
        }
    }
}
