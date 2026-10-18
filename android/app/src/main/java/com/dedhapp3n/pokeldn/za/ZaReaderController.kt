package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.esp32.Esp32RadioEvent
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.ldn.LdnParticipant
import com.dedhapp3n.pokeldn.ldn.LdnPiaUdpTransport
import com.dedhapp3n.pokeldn.ldn.LdnRawGroupDecoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class ZaReaderState(
    val code: String,
    val phase: String,
    val snapshot: ZaSnapshot,
    val host: ZaHostStatus = ZaHostStatus(),
    val current: ZaPokemonInfo? = null,
    val detail: String? = null,
    val terminal: Boolean = false,
)

/** Owns only the Z-A AP's read-only event loop; FRLG's discovery controller is untouched. */
internal class ZaReaderController(
    private val radio: Esp32RadioSession,
    private val operation: Esp32RadioOperation,
    private val network: ZaLdnNetwork,
    private val host: ZaHostSession,
    private val store: ZaSnapshotStore,
    initial: ZaSnapshot,
    private val onState: (ZaReaderState) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val transport = LdnPiaUdpTransport(network.authentication.hostIp, network.accessPoint.bssid)
    private val rawGroup = LdnRawGroupDecoder(network.accessPoint.wlanKey)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "za-reader").apply { isDaemon = true } }
    private var scheduled: ScheduledFuture<*>? = null
    private var subscription: AutoCloseable? = null
    private var running = false
    private var finishing = false
    private var lastAdvertisement = 0L
    private var lastPublished = 0L
    private var peer: LdnParticipant? = null
    private var snapshot = initial
    private var current: ZaPokemonInfo? = null
    private var state = ZaReaderState(initial.linkCode.orEmpty(), "PREPARING NETWORK", initial)

    fun start() = synchronized(lock) {
        check(!running)
        running = true
        subscription = radio.addOperationEventListener(operation, ::onEvent)
        onState(state.copy(phase = "WAITING FOR Z-A"))
        scheduled = scheduler.scheduleAtFixedRate({
            try { tick() } catch (error: Throwable) { fail(error) }
        }, 0, 17, TimeUnit.MILLISECONDS)
    }

    fun finish() = synchronized(lock) {
        if (!running || finishing) return@synchronized
        finishing = true
        snapshot = store.finish(snapshot)
        host.leave(System.currentTimeMillis())
        publish("FINISHING", force = true)
    }

    private fun tick() = synchronized(lock) {
        if (!running) return@synchronized
        val now = System.currentTimeMillis()
        if (now - lastAdvertisement >= 100) {
            check(radio.sendRaw(operation, network.authentication.currentAdvertisementFrame()))
            lastAdvertisement = now
        }
        sendDatagrams(host.tick(now))
        if (host.departed && finishing) {
            stopLocked()
            state = state.copy(phase = "COMPLETE", snapshot = snapshot, host = host.status, terminal = true)
            onState(state)
        } else if (now - lastPublished > 1000 && host.status != state.host) {
            publish(host.status.phase)
        }
    }

    private fun onEvent(event: Esp32RadioEvent) = synchronized(lock) {
        if (!running) return@synchronized
        try {
            when (event) {
                is Esp32RadioEvent.LdnControlEthernet -> {
                    val result = network.authentication.process(event.source, event.payload)
                    val response = event.source + event.target + byteArrayOf(0x88.toByte(), 0xb7.toByte()) + result.response
                    check(radio.sendEthernet(operation, response))
                    result.participant?.let { participant ->
                        peer = participant
                        host.participantJoined(participant, System.currentTimeMillis())
                        publish("Z-A CONNECTED", force = true)
                    }
                }
                is Esp32RadioEvent.Ethernet -> acceptEthernet(event.frame)
                is Esp32RadioEvent.ManagementFrame -> {
                    if (event.frameType == 2 && event.protectedFrame &&
                        peer?.mac?.contentEquals(event.source) == true && !event.toDs && !event.fromDs) {
                        runCatching { rawGroup.decode(event.frame).ethernet }.getOrNull()?.let(::acceptEthernet)
                    }
                    if (event.frameType == 0 && event.subtype in setOf(10, 12) && peer?.mac?.contentEquals(event.source) == true) {
                        finish()
                    }
                }
                is Esp32RadioEvent.StationLeft -> if (peer?.mac?.contentEquals(event.mac) == true) finish()
                else -> Unit
            }
        } catch (error: Throwable) { fail(error) }
    }

    private fun acceptEthernet(frame: ByteArray) {
        val (datagram, reply) = transport.receive(frame)
        if (reply != null) check(radio.sendEthernet(operation, reply))
        if (datagram != null) {
            host.receive(datagram.payload, datagram.sourceIp, System.currentTimeMillis())
            sendDatagrams(host.tick(System.currentTimeMillis()))
            if (host.status.leavePhase != null && !finishing) finish()
            else if (host.status.phase != state.phase) publish(host.status.phase, force = true)
        }
    }

    private fun sendDatagrams(datagrams: List<com.dedhapp3n.pokeldn.ldn.LdnPiaDatagram>) {
        val participant = peer ?: return
        datagrams.forEach { datagram ->
            val mac = if (datagram.destinationIp.endsWith(".255")) ByteArray(6) { 0xff.toByte() } else participant.mac
            check(radio.sendEthernet(operation, transport.frame(datagram.destinationIp, mac, datagram.payload)))
        }
    }

    fun gameMessage(message: ByteArray) = synchronized(lock) {
        if (!running || finishing) return@synchronized
        val previous = snapshot
        snapshot = store.observe(snapshot, message)
        val fresh = snapshot.pokemon.size != previous.pokemon.size
        if (fresh) current = snapshot.pokemon.lastOrNull()?.summary
        if (fresh || snapshot.trainer != previous.trainer || snapshot.lastDecodeError != previous.lastDecodeError) {
            publish(if (snapshot.trainer != null && snapshot.pokemon.isEmpty()) "TRAINER DETECTED" else "READING POKÉMON", force = true)
        }
    }

    private fun publish(phase: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublished < 1000) return
        lastPublished = now
        state = ZaReaderState(snapshot.linkCode.orEmpty(), phase, snapshot, host.status, current, snapshot.lastDecodeError, false)
        onState(state)
    }

    private fun fail(error: Throwable) = synchronized(lock) {
        if (!running) return@synchronized
        runCatching { snapshot = store.finish(snapshot) }
        stopLocked()
        state = state.copy(phase = "FAILED", snapshot = snapshot, detail = error.message, terminal = true)
        onState(state)
        onFailure(error)
    }

    private fun stopLocked() {
        running = false
        scheduled?.cancel(false); scheduled = null
        subscription?.close(); subscription = null
        scheduler.shutdownNow()
    }

    override fun close() = synchronized(lock) { stopLocked() }
}
