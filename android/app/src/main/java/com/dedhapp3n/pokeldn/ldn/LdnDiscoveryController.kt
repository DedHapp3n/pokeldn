package com.dedhapp3n.pokeldn.ldn

import com.dedhapp3n.pokeldn.esp32.Esp32RadioEvent
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.esp32.formatMac
import com.dedhapp3n.pokeldn.frlg.FrlgHostLink
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class LdnDiscoverySnapshot(
    val advertisementsSent: Long = 0,
    val discoveryActivityCount: Long = 0,
    val managementFrames: Long = 0,
    val ldnActionFrames: Long = 0,
    val ldnControlFrames: Long = 0,
    val ethernetFrames: Long = 0,
    val stationJoins: Long = 0,
    val stationLeaves: Long = 0,
    val stationDetected: Boolean = false,
    val authenticationRequests: Long = 0,
    val authenticationResponses: Long = 0,
    val authenticationFailures: Long = 0,
    val participantRegistered: Boolean = false,
    val participantEverRegistered: Boolean = false,
    val registeredParticipant: LdnParticipant? = null,
    val piaStage: LdnPiaStage = LdnPiaStage.WAITING,
    val piaNetRequests: Long = 0,
    val piaSessionRequests: Long = 0,
    val piaSessionResponses: Long = 0,
    val reliableEstablished: Boolean = false,
    val reliableFramesReceived: Long = 0,
    val reliableFramesSent: Long = 0,
    val rfuConnected: Boolean = false,
    val rfuReady: Boolean = false,
    val linkPlayerExchanged: Boolean = false,
    val detectedCartridge: String? = null,
    val giftStage: com.dedhapp3n.pokeldn.frlg.FrlgGiftStage? = null,
    val latestActivity: String? = null,
)

internal fun interface LdnScheduledHandle {
    fun cancel()
}

internal interface LdnAdvertisementScheduler : AutoCloseable {
    fun schedule(periodMillis: Long, task: () -> Unit): LdnScheduledHandle
}

private class ExecutorLdnAdvertisementScheduler : LdnAdvertisementScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ldn-advertisement").apply { isDaemon = true }
    }

    override fun schedule(periodMillis: Long, task: () -> Unit): LdnScheduledHandle {
        val future: ScheduledFuture<*> = executor.scheduleAtFixedRate(
            task,
            periodMillis,
            periodMillis,
            TimeUnit.MILLISECONDS,
        )
        return LdnScheduledHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

class LdnDiscoveryController internal constructor(
    private val operation: Esp32RadioOperation,
    private val advertisementFrame: () -> ByteArray,
    private val sendRaw: (Esp32RadioOperation, ByteArray) -> Boolean,
    private val sendEthernet: (Esp32RadioOperation, ByteArray) -> Boolean = { _, _ -> true },
    private val authenticate: (ByteArray, ByteArray) -> LdnAuthenticationOutcome? = { _, _ -> null },
    private val removeParticipant: (ByteArray) -> LdnParticipant? = { null },
    private val piaHost: LdnPiaHost? = null,
    private val piaTransport: LdnPiaUdpTransport? = null,
    private val frlgHost: FrlgHostLink? = null,
    private val subscribe: (Esp32RadioOperation, (Esp32RadioEvent) -> Unit) -> AutoCloseable,
    private val scheduler: LdnAdvertisementScheduler = ExecutorLdnAdvertisementScheduler(),
    private val onSnapshot: (LdnDiscoverySnapshot) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    constructor(
        session: Esp32RadioSession,
        operation: Esp32RadioOperation,
        network: LdnDiscoveryNetwork,
        walkThroughWalls: Boolean = false,
        onSnapshot: (LdnDiscoverySnapshot) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) : this(
        operation = operation,
        advertisementFrame = network.authenticationHost::currentAdvertisementFrame,
        sendRaw = session::sendRaw,
        sendEthernet = session::sendEthernet,
        authenticate = network.authenticationHost::process,
        removeParticipant = network.authenticationHost::removeParticipant,
        piaHost = network.piaHost,
        piaTransport = LdnPiaUdpTransport(network.piaHost.hostIp, network.accessPoint.bssid),
        frlgHost = FrlgHostLink(network.piaHost, network.parentSessionId, walkThroughWalls),
        subscribe = session::addOperationEventListener,
        onSnapshot = onSnapshot,
        onFailure = onFailure,
    )

    private val lock = Any()
    private var running = false
    private var failed = false
    private var snapshot = LdnDiscoverySnapshot()
    private var subscription: AutoCloseable? = null
    private var scheduled: LdnScheduledHandle? = null
    private var protocolScheduled: LdnScheduledHandle? = null
    private val associatedStations = mutableSetOf<String>()

    fun start(): LdnDiscoverySnapshot {
        synchronized(lock) {
            check(!running) { "LDN advertisement is already running" }
            running = true
        }
        try {
            subscription = subscribe(operation, ::onEvent)
            transmitAdvertisement()
            scheduled = scheduler.schedule(LdnAdvertisementBuilder.ADVERTISEMENT_INTERVAL_MS) {
                try {
                    transmitAdvertisement()
                    transmitPia()
                } catch (error: Throwable) {
                    fail(error)
                }
            }
            if (frlgHost != null) {
                protocolScheduled = scheduler.schedule(FRLG_FRAME_INTERVAL_MS) {
                    try { transmitFrlg() } catch (error: Throwable) { fail(error) }
                }
            }
            return synchronized(lock) { snapshot }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun currentSnapshot(): LdnDiscoverySnapshot = synchronized(lock) { snapshot }

    private fun transmitAdvertisement() {
        val next = synchronized(lock) {
            if (!running) return
            if (!sendRaw(operation, advertisementFrame())) return
            snapshot = snapshot.copy(advertisementsSent = snapshot.advertisementsSent + 1)
            snapshot
        }
        if (next.advertisementsSent == 1L || next.advertisementsSent % 10L == 0L) onSnapshot(next)
    }

    private fun onEvent(event: Esp32RadioEvent) {
        if (event is Esp32RadioEvent.LdnControlEthernet && handleAuthentication(event)) return
        if (event is Esp32RadioEvent.Ethernet && handleIpTraffic(event.frame)) return
        val piaBeforeLeave = if (event is Esp32RadioEvent.StationLeft) piaHost?.snapshot() else null
        val removedParticipant = if (event is Esp32RadioEvent.StationLeft) {
            removeParticipant(event.mac)
        } else null
        val next = synchronized(lock) {
            if (!running) return
            if (event is Esp32RadioEvent.StationJoined) associatedStations += event.mac.formatMac()
            if (event is Esp32RadioEvent.StationLeft) associatedStations -= event.mac.formatMac()
            if (event is Esp32RadioEvent.StationLeft) piaHost?.participantLeft(event.mac)
            snapshot = when (event) {
                is Esp32RadioEvent.ManagementFrame -> snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    managementFrames = snapshot.managementFrames + if (event.frameType == 0) 1 else 0,
                    ldnActionFrames = snapshot.ldnActionFrames + if (event.isLdnAction) 1 else 0,
                    latestActivity = if (event.isLdnAction) {
                        "Nintendo LDN action frame received"
                    } else if (event.frameType == 2) {
                        "802.11 data trace received"
                    } else {
                        "802.11 ${managementSubtypeName(event.subtype)} received"
                    },
                )
                is Esp32RadioEvent.LdnControlEthernet -> snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    ldnControlFrames = snapshot.ldnControlFrames + 1,
                    latestActivity = "LDN authentication/control frame received",
                )
                is Esp32RadioEvent.Ethernet -> snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    ethernetFrames = snapshot.ethernetFrames + 1,
                    latestActivity = "Ethernet frame received",
                )
                is Esp32RadioEvent.StationJoined -> snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    stationJoins = snapshot.stationJoins + 1,
                    stationDetected = associatedStations.isNotEmpty(),
                    latestActivity = "Station associated: ${event.mac.formatMac()}",
                )
                is Esp32RadioEvent.StationLeft -> snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    stationLeaves = snapshot.stationLeaves + 1,
                    stationDetected = associatedStations.isNotEmpty(),
                    participantRegistered = if (removedParticipant != null) false else snapshot.participantRegistered,
                    registeredParticipant = if (removedParticipant != null) null else snapshot.registeredParticipant,
                    piaStage = deepestPiaStage(snapshot.piaStage, piaBeforeLeave?.stage),
                    piaNetRequests = maxOf(snapshot.piaNetRequests, piaBeforeLeave?.netRequestsSent ?: 0),
                    piaSessionRequests = maxOf(snapshot.piaSessionRequests, piaBeforeLeave?.sessionRequests ?: 0),
                    piaSessionResponses = maxOf(snapshot.piaSessionResponses, piaBeforeLeave?.sessionResponses ?: 0),
                    reliableEstablished = snapshot.reliableEstablished || piaBeforeLeave?.reliableEstablished == true,
                    reliableFramesReceived = maxOf(snapshot.reliableFramesReceived, piaBeforeLeave?.reliableFramesReceived ?: 0),
                    reliableFramesSent = maxOf(snapshot.reliableFramesSent, piaBeforeLeave?.reliableFramesSent ?: 0),
                    latestActivity = "Station left: ${event.mac.formatMac()}",
                )
                else -> return
            }
            snapshot
        }
        if (event is Esp32RadioEvent.StationJoined || event is Esp32RadioEvent.StationLeft ||
            next.discoveryActivityCount == 1L || next.discoveryActivityCount % 10L == 0L) {
            onSnapshot(next)
        }
    }

    private fun handleAuthentication(event: Esp32RadioEvent.LdnControlEthernet): Boolean {
        val outcome = try {
            authenticate(event.source, event.payload) ?: return false
        } catch (error: Throwable) {
            fail(error)
            return true
        }
        val received = synchronized(lock) {
            if (!running) return true
            snapshot = snapshot.copy(
                discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                ldnControlFrames = snapshot.ldnControlFrames + 1,
                authenticationRequests = snapshot.authenticationRequests + 1,
                latestActivity = "Authentication request received",
            )
            snapshot
        }
        onSnapshot(received)
        val response = ethernetFrame(event.source, event.target, outcome.response)
        val sent = try {
            synchronized(lock) {
                if (!running) return true
                if (!sendEthernet(operation, response)) {
                    throw IllegalStateException("ESP32 transmit queue rejected the authentication response")
                }
                snapshot = snapshot.copy(
                    authenticationResponses = snapshot.authenticationResponses + 1,
                    authenticationFailures = snapshot.authenticationFailures + if (outcome.statusCode == 0) 0 else 1,
                    participantRegistered = snapshot.participantRegistered || outcome.participant != null,
                    participantEverRegistered = snapshot.participantEverRegistered || outcome.participant != null,
                    registeredParticipant = outcome.participant ?: snapshot.registeredParticipant,
                    latestActivity = if (outcome.participant != null) {
                        "Participant registered: ${outcome.participant.mac.formatMac()}"
                    } else {
                        "Authentication response sent (status ${outcome.statusCode})"
                    },
                )
                outcome.participant?.let { piaHost?.participantJoined(it, System.currentTimeMillis()) }
                snapshot
            }
        } catch (error: Throwable) {
            fail(error)
            return true
        }
        onSnapshot(sent)
        transmitPia()
        return true
    }

    private fun handleIpTraffic(frame: ByteArray): Boolean {
        val transport = piaTransport ?: return false
        val (datagram, immediateReply) = try { transport.receive(frame) } catch (_: Exception) { return false }
        if (datagram == null && immediateReply == null) return false
        try {
            synchronized(lock) {
                if (!running) return true
                if (immediateReply != null && !sendEthernet(operation, immediateReply)) {
                    throw IllegalStateException("ESP32 transmit queue rejected the ARP response")
                }
                val replies = if (datagram == null) emptyList() else {
                    piaHost?.receive(datagram.payload, datagram.sourceIp, System.currentTimeMillis()).orEmpty()
                }
                sendPiaDatagrams(replies)
                updatePiaSnapshotLocked()
                snapshot = snapshot.copy(
                    discoveryActivityCount = snapshot.discoveryActivityCount + 1,
                    ethernetFrames = snapshot.ethernetFrames + 1,
                    latestActivity = snapshot.piaStage.activity(snapshot.latestActivity),
                )
            }
            onSnapshot(currentSnapshot())
        } catch (error: Throwable) {
            fail(error)
        }
        return true
    }

    private fun transmitPia() {
        val next = synchronized(lock) {
            if (!running || piaHost == null) return
            sendPiaDatagrams(piaHost.tick(System.currentTimeMillis()))
            updatePiaSnapshotLocked()
            snapshot
        }
        if (next.piaNetRequests == 1L || next.piaSessionResponses == 1L || next.piaStage == LdnPiaStage.ESTABLISHED) {
            onSnapshot(next)
        }
    }

    private fun transmitFrlg() {
        val next = synchronized(lock) {
            if (!running || frlgHost == null) return
            val now = System.currentTimeMillis()
            sendPiaDatagrams(piaHost?.tick(now).orEmpty())
            sendPiaDatagrams(frlgHost.tick(now))
            updatePiaSnapshotLocked()
            updateFrlgSnapshotLocked()
            snapshot
        }
        if (next.rfuConnected || next.linkPlayerExchanged) onSnapshot(next)
    }

    private fun sendPiaDatagrams(datagrams: List<LdnPiaDatagram>) {
        val transport = piaTransport ?: return
        val participant = snapshot.registeredParticipant
        datagrams.forEach { datagram ->
            val mac = if (datagram.destinationIp.endsWith(".255")) ByteArray(6) { 0xff.toByte() }
            else participant?.takeIf { it.ipAddress == datagram.destinationIp }?.mac ?: return@forEach
            if (!sendEthernet(operation, transport.frame(datagram.destinationIp, mac, datagram.payload))) {
                throw IllegalStateException("ESP32 transmit queue rejected a PIA packet")
            }
        }
    }

    private fun updatePiaSnapshotLocked() {
        val pia = piaHost?.snapshot() ?: return
        snapshot = snapshot.copy(
            piaStage = pia.stage,
            piaNetRequests = pia.netRequestsSent,
            piaSessionRequests = pia.sessionRequests,
            piaSessionResponses = pia.sessionResponses,
            reliableEstablished = pia.reliableEstablished,
            reliableFramesReceived = pia.reliableFramesReceived,
            reliableFramesSent = pia.reliableFramesSent,
            latestActivity = pia.detail ?: pia.stage.activity(snapshot.latestActivity),
        )
    }

    private fun updateFrlgSnapshotLocked() {
        val link = frlgHost?.snapshot() ?: return
        snapshot = snapshot.copy(
            rfuConnected = link.rfuConnected,
            rfuReady = link.rfuReady,
            linkPlayerExchanged = link.linkPlayerExchanged,
            detectedCartridge = link.cartridge?.let { "${it.game} / ${it.language}" },
            giftStage = link.gift?.stage,
            latestActivity = link.detail ?: snapshot.latestActivity,
        )
    }

    private fun fail(error: Throwable) {
        val report = synchronized(lock) {
            if (!running || failed) false else {
                failed = true
                true
            }
        }
        if (!report) return
        stop()
        onFailure(error)
    }

    fun stop() {
        val resources = synchronized(lock) {
            if (!running && scheduled == null && protocolScheduled == null && subscription == null) return
            running = false
            val pair = scheduled to subscription
            scheduled = null
            val protocol = protocolScheduled
            protocolScheduled = null
            subscription = null
            Triple(pair.first, protocol, pair.second)
        }
        resources.first?.cancel()
        resources.second?.cancel()
        resources.third?.close()
        piaHost?.reset()
        scheduler.close()
    }

    override fun close() = stop()

    companion object {
        private const val FRLG_FRAME_INTERVAL_MS = 17L
        private fun ethernetFrame(target: ByteArray, source: ByteArray, payload: ByteArray): ByteArray {
            require(target.size == 6 && source.size == 6)
            return target + source + byteArrayOf(0x88.toByte(), 0xb7.toByte()) + payload
        }

        private fun managementSubtypeName(subtype: Int): String = when (subtype) {
            0 -> "association request"
            2 -> "reassociation request"
            10 -> "disassociation"
            11 -> "authentication"
            12 -> "deauthentication"
            13 -> "action frame"
            else -> "management frame subtype $subtype"
        }

        private fun LdnPiaStage.activity(previous: String?): String = when (this) {
            LdnPiaStage.NET_PROBING -> "PIA network negotiation started"
            LdnPiaStage.NET_CONNECTED -> "PIA network response received"
            LdnPiaStage.SESSION_JOIN_RECEIVED -> "PIA connection request received"
            LdnPiaStage.SESSION_RESPONSE_SENT -> "PIA response sent"
            LdnPiaStage.ESTABLISHED -> "PIA session established"
            LdnPiaStage.FAILED -> "PIA session failed"
            LdnPiaStage.WAITING -> previous ?: "Waiting for PIA negotiation"
        }
    }
}

private fun deepestPiaStage(current: LdnPiaStage, candidate: LdnPiaStage?): LdnPiaStage {
    if (candidate == null) return current
    fun LdnPiaStage.rank() = when (this) {
        LdnPiaStage.WAITING -> 0
        LdnPiaStage.NET_PROBING -> 1
        LdnPiaStage.NET_CONNECTED -> 2
        LdnPiaStage.SESSION_JOIN_RECEIVED -> 3
        LdnPiaStage.SESSION_RESPONSE_SENT -> 4
        LdnPiaStage.ESTABLISHED -> 5
        LdnPiaStage.FAILED -> 6
    }
    return if (candidate.rank() > current.rank()) candidate else current
}
