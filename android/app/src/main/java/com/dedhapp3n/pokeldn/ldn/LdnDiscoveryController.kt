package com.dedhapp3n.pokeldn.ldn

import com.dedhapp3n.pokeldn.esp32.Esp32RadioEvent
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.esp32.formatMac
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
    val registeredParticipant: LdnParticipant? = null,
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
    private val subscribe: (Esp32RadioOperation, (Esp32RadioEvent) -> Unit) -> AutoCloseable,
    private val scheduler: LdnAdvertisementScheduler = ExecutorLdnAdvertisementScheduler(),
    private val onSnapshot: (LdnDiscoverySnapshot) -> Unit = {},
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    constructor(
        session: Esp32RadioSession,
        operation: Esp32RadioOperation,
        network: LdnDiscoveryNetwork,
        onSnapshot: (LdnDiscoverySnapshot) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ) : this(
        operation = operation,
        advertisementFrame = network.authenticationHost::currentAdvertisementFrame,
        sendRaw = session::sendRaw,
        sendEthernet = session::sendEthernet,
        authenticate = network.authenticationHost::process,
        removeParticipant = network.authenticationHost::removeParticipant,
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
                } catch (error: Throwable) {
                    fail(error)
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
        val removedParticipant = if (event is Esp32RadioEvent.StationLeft) {
            removeParticipant(event.mac)
        } else null
        val next = synchronized(lock) {
            if (!running) return
            if (event is Esp32RadioEvent.StationJoined) associatedStations += event.mac.formatMac()
            if (event is Esp32RadioEvent.StationLeft) associatedStations -= event.mac.formatMac()
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
                    registeredParticipant = outcome.participant ?: snapshot.registeredParticipant,
                    latestActivity = if (outcome.participant != null) {
                        "Participant registered: ${outcome.participant.mac.formatMac()}"
                    } else {
                        "Authentication response sent (status ${outcome.statusCode})"
                    },
                )
                snapshot
            }
        } catch (error: Throwable) {
            fail(error)
            return true
        }
        onSnapshot(sent)
        return true
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
            if (!running && scheduled == null && subscription == null) return
            running = false
            val pair = scheduled to subscription
            scheduled = null
            subscription = null
            pair
        }
        resources.first?.cancel()
        resources.second?.close()
        scheduler.close()
    }

    override fun close() = stop()

    companion object {
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
    }
}
