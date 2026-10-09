package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

enum class Esp32RadioPhase {
    DISCONNECTED,
    CONNECTED,
    VERIFIED,
    SWITCHING_BAUD,
    READY,
    STARTING_RADIO,
    RADIO_ACTIVE,
    STOPPING,
    CLEANUP_REQUIRED,
    ERROR,
}

data class Esp32RadioState(
    val phase: Esp32RadioPhase = Esp32RadioPhase.DISCONNECTED,
    val detail: String? = null,
    val generation: Long? = null,
    val baudRate: Int? = null,
)

data class Esp32Status(val mode: Int, val text: String) {
    companion object {
        fun parse(payload: ByteArray): Esp32Status {
            val text = payload.toString(Charsets.UTF_8)
            val mode = Regex("(?:^|\\s)mode=(-?\\d+)(?:\\s|$)").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: throw Esp32ProtocolException("STATUS did not contain a valid mode: $text")
            return Esp32Status(mode, text)
        }
    }
}

sealed interface Esp32RadioEvent {
    data class Credit(val consumedBytes: Long) : Esp32RadioEvent
    data class Link(val up: Boolean, val reason: Int, val mac: ByteArray) : Esp32RadioEvent
    data class Ethernet(val frame: ByteArray) : Esp32RadioEvent
    data class LdnControlEthernet(
        val target: ByteArray,
        val source: ByteArray,
        val payload: ByteArray,
    ) : Esp32RadioEvent
    data class ManagementFrame(
        val channel: Int,
        val rssi: Int,
        val frameType: Int,
        val subtype: Int,
        val target: ByteArray,
        val source: ByteArray,
        val bssid: ByteArray,
        val isLdnAction: Boolean,
        val frame: ByteArray,
    ) : Esp32RadioEvent
    data class TxDone(val payload: ByteArray) : Esp32RadioEvent
    data class StationJoined(
        val mac: ByteArray,
        val associationId: Int,
        val keyResult: Int,
        val portOpened: Boolean,
    ) : Esp32RadioEvent
    data class StationLeft(val mac: ByteArray, val reason: Int) : Esp32RadioEvent
    data class Status(val status: Esp32Status) : Esp32RadioEvent
    data class Unknown(val type: Int, val payload: ByteArray) : Esp32RadioEvent
}

data class Esp32CreditSnapshot(
    val written: Long,
    val credited: Long,
    val writtenOff: Long,
    val flowEnabled: Boolean,
    val queued: Int,
)

data class Esp32AccessPointConfig(
    val channel: Int,
    val bssid: ByteArray,
    val ssid: String,
    val key: ByteArray,
    val maxStations: Int = 7,
    val flags: Int = 0,
    val flags2: Int = 0,
    val power: Int = 0,
) {
    fun toPayload(): ByteArray {
        require(channel in 1..14) { "Wi-Fi channel must be between 1 and 14" }
        require(bssid.size == 6) { "BSSID must be six bytes" }
        val ssidBytes = ssid.toByteArray(Charsets.US_ASCII)
        require(ssidBytes.size == 32) { "LDN SSID must be 32 ASCII bytes" }
        require(key.size == 16) { "LDN key must be 16 bytes" }
        require(maxStations in 1..255 && flags in 0..255 && flags2 in 0..255 && power in 0..255)
        val tail = when {
            power != 0 -> byteArrayOf(flags2.toByte(), power.toByte())
            flags2 != 0 -> byteArrayOf(flags2.toByte())
            else -> byteArrayOf()
        }
        return byteArrayOf(channel.toByte()) + bssid + ssidBytes + key +
            byteArrayOf(maxStations.toByte(), flags.toByte()) + tail
    }
}

class Esp32RadioOperation internal constructor(val generation: Long)

internal fun interface ScheduledHandle { fun cancel() }

internal interface AliveScheduler : AutoCloseable {
    fun schedule(periodMillis: Long, task: () -> Unit): ScheduledHandle
}

private class ExecutorAliveScheduler : AliveScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "esp32-alive").apply { isDaemon = true }
    }

    override fun schedule(periodMillis: Long, task: () -> Unit): ScheduledHandle {
        val future: ScheduledFuture<*> = executor.scheduleAtFixedRate(task, 0, periodMillis, TimeUnit.MILLISECONDS)
        return ScheduledHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

class Esp32RadioSession internal constructor(
    private val serial: SerialTransport,
    private val diagnostics: (String) -> Unit = {},
    private val aliveScheduler: AliveScheduler = ExecutorAliveScheduler(),
    private val commandTimeoutMillis: Int = COMMAND_TIMEOUT_MS,
    private val accessPointTimeoutMillis: Int = AP_START_TIMEOUT_MS,
    private val stopTimeoutMillis: Int = STOP_TIMEOUT_MS,
) : AutoCloseable {
    private data class RequestToken(@Volatile var cancelled: Boolean = false)
    private data class Outbound(
        val type: Int,
        val frame: ByteArray,
        val generation: Long? = null,
        val token: RequestToken? = null,
    )
    private data class PendingReply(
        val type: Int,
        val command: Int,
        var payload: ByteArray? = null,
    )
    private data class OperationListener(
        val generation: Long,
        val listener: (Esp32RadioEvent) -> Unit,
    )

    private val reader = Esp32FrameReader(diagnostics)
    private val outbound = ArrayDeque<Outbound>()
    private val outboundMonitor = Object()
    private val replyMonitor = Object()
    private val requestLock = Any()
    private val stateListeners = CopyOnWriteArrayList<(Esp32RadioState) -> Unit>()
    private val eventListeners = CopyOnWriteArrayList<(Esp32RadioEvent) -> Unit>()
    private val operationListeners = CopyOnWriteArrayList<OperationListener>()
    private val generationCounter = AtomicLong()
    private val linkMonitor = Object()

    @Volatile private var running = false
    @Volatile private var pendingReply: PendingReply? = null
    @Volatile private var activeGeneration: Long? = null
    @Volatile private var aliveHandle: ScheduledHandle? = null
    @Volatile private var lastLink: Pair<Long, Esp32RadioEvent.Link>? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var writerThread: Thread? = null
    @Volatile private var fatalError: Throwable? = null
    @Volatile var info: Esp32Info? = null
        private set
    @Volatile var state: Esp32RadioState = Esp32RadioState()
        private set

    private var written = 0L
    private var credited = 0L
    private var writtenOff = 0L
    private var creditSeenNanos = 0L
    private var flowEnabled = false
    private var writing = false

    fun addStateListener(listener: (Esp32RadioState) -> Unit): AutoCloseable {
        stateListeners += listener
        listener(state)
        return AutoCloseable { stateListeners -= listener }
    }

    fun addEventListener(listener: (Esp32RadioEvent) -> Unit): AutoCloseable {
        eventListeners += listener
        return AutoCloseable { eventListeners -= listener }
    }

    fun addOperationEventListener(
        operation: Esp32RadioOperation,
        listener: (Esp32RadioEvent) -> Unit,
    ): AutoCloseable {
        check(activeGeneration == operation.generation) { "Radio operation is no longer active" }
        val subscription = OperationListener(operation.generation, listener)
        operationListeners += subscription
        return AutoCloseable { operationListeners -= subscription }
    }

    fun verifyAndPrepare(fastBaudRate: Int = FAST_BAUD_RATE): Esp32Info {
        check(!running) { "ESP32 radio session is already running" }
        check(serial.configuredBaudRate == INITIAL_BAUD_RATE) {
            "ESP32 serial transport must start at $INITIAL_BAUD_RATE baud"
        }
        running = true
        publish(Esp32RadioPhase.CONNECTED, baudRate = serial.configuredBaudRate)
        readerThread = thread(name = "esp32-radio", isDaemon = true) { readLoop() }
        writerThread = thread(name = "esp32-writer", isDaemon = true) { writeLoop() }
        try {
            synchronizeHello(HELLO_ATTEMPTS, HELLO_TIMEOUT_MS)
            var verified = verifiedHello(VERIFICATION_TIMEOUT_MS)
            publish(Esp32RadioPhase.VERIFIED, baudRate = serial.configuredBaudRate)
            if (fastBaudRate != serial.configuredBaudRate) {
                publish(Esp32RadioPhase.SWITCHING_BAUD, "Switching to $fastBaudRate baud", baudRate = serial.configuredBaudRate)
                requestResult(Esp32Protocol.CMD_BAUD, int32(fastBaudRate), commandTimeoutMillis)
                awaitOutboundDrained(commandTimeoutMillis)
                serial.setBaudRate(fastBaudRate)
                synchronizeHello(HELLO_ATTEMPTS, FAST_HELLO_TIMEOUT_MS)
                verified = verifiedHello(VERIFICATION_TIMEOUT_MS)
            }
            info = verified
            publish(Esp32RadioPhase.READY, baudRate = serial.configuredBaudRate)
            return verified
        } catch (error: Throwable) {
            publish(Esp32RadioPhase.ERROR, error.message ?: error.javaClass.simpleName, baudRate = serial.configuredBaudRate)
            throw error
        }
    }

    fun status(): Esp32Status = Esp32Status.parse(
        request(Esp32Protocol.CMD_STATUS, Esp32Protocol.MSG_STATUS, timeoutMillis = commandTimeoutMillis)
    )

    fun setChannel(channel: Int) {
        require(channel in 1..14) { "Wi-Fi channel must be between 1 and 14" }
        requestResult(Esp32Protocol.CMD_CHANNEL, byteArrayOf(channel.toByte()), commandTimeoutMillis)
    }

    fun startAccessPoint(
        config: Esp32AccessPointConfig,
        linkTimeoutMillis: Int = LINK_TIMEOUT_MS,
    ): Esp32RadioOperation {
        check(state.phase == Esp32RadioPhase.READY) { "ESP32 radio is not ready" }
        val generation = generationCounter.incrementAndGet()
        val operation = Esp32RadioOperation(generation)
        activeGeneration = generation
        lastLink = null
        publish(Esp32RadioPhase.STARTING_RADIO, generation = generation, baudRate = serial.configuredBaudRate)
        try {
            val idle = status()
            check(idle.mode == MODE_IDLE) { "ESP32 is not idle (mode=${idle.mode})" }
            requestResult(Esp32Protocol.CMD_AP_START, config.toPayload(), accessPointTimeoutMillis)
            startAlive(generation)
            val link = waitForLink(generation, linkTimeoutMillis)
            if (!link.up) throw Esp32ProtocolException("ESP32 AP failed to start (reason=${link.reason})")
            publish(Esp32RadioPhase.RADIO_ACTIVE, generation = generation, baudRate = serial.configuredBaudRate)
            return operation
        } catch (error: Throwable) {
            val cleanup = cleanup(generation)
            if (cleanup != null) error.addSuppressed(cleanup)
            throw error
        }
    }

    fun sendEthernet(operation: Esp32RadioOperation, frame: ByteArray): Boolean {
        require(frame.size in 14..1600) { "Ethernet frame must be 14..1600 bytes" }
        check(state.phase == Esp32RadioPhase.RADIO_ACTIVE && activeGeneration == operation.generation) {
            "Radio operation is not active"
        }
        return enqueue(Esp32Protocol.CMD_ETH_TX, frame, operation.generation, dropWhenFull = true)
    }

    fun sendRaw(operation: Esp32RadioOperation, frame: ByteArray): Boolean {
        require(frame.size in 24..1500) { "Raw 802.11 frame must be 24..1500 bytes" }
        check(state.phase == Esp32RadioPhase.RADIO_ACTIVE && activeGeneration == operation.generation) {
            "Radio operation is not active"
        }
        return enqueue(Esp32Protocol.CMD_RAW_TX, frame, operation.generation, dropWhenFull = true)
    }

    fun stopAccessPoint(operation: Esp32RadioOperation) {
        check(activeGeneration == operation.generation) { "Radio operation is no longer active" }
        cleanup(operation.generation)?.let { throw it }
    }

    fun <T> withAccessPoint(config: Esp32AccessPointConfig, block: (Esp32RadioOperation) -> T): T {
        val operation = startAccessPoint(config)
        var operationFailure: Throwable? = null
        try {
            return block(operation)
        } catch (error: Throwable) {
            operationFailure = error
            throw error
        } finally {
            if (activeGeneration == operation.generation) {
                try {
                    stopAccessPoint(operation)
                } catch (cleanupError: Throwable) {
                    if (operationFailure != null) operationFailure.addSuppressed(cleanupError)
                    else throw cleanupError
                }
            }
        }
    }

    fun creditSnapshot(): Esp32CreditSnapshot = synchronized(outboundMonitor) {
        Esp32CreditSnapshot(written, credited, writtenOff, flowEnabled, outbound.size)
    }

    private fun cleanup(generation: Long): Esp32ProtocolException? {
        publish(Esp32RadioPhase.STOPPING, generation = generation, baudRate = serial.configuredBaudRate)
        stopAlive()
        activeGeneration = null
        synchronized(outboundMonitor) {
            outbound.removeIf { it.generation == generation }
            outboundMonitor.notifyAll()
        }
        var failure: Esp32ProtocolException? = null
        try {
            requestResult(Esp32Protocol.CMD_STOP, timeoutMillis = stopTimeoutMillis)
        } catch (error: Throwable) {
            failure = Esp32ProtocolException("ESP32 STOP failed: ${error.message}", error)
        }
        val status = try {
            status()
        } catch (error: Throwable) {
            if (failure == null) failure = Esp32ProtocolException("ESP32 idle verification failed: ${error.message}", error)
            null
        }
        val stopped = failure == null && status?.mode == MODE_IDLE
        if (!stopped && failure == null) {
            failure = Esp32ProtocolException("ESP32 cleanup reported mode=${status?.mode}")
        }
        operationListeners.removeIf { it.generation == generation }
        lastLink = null
        generationCounter.incrementAndGet()
        if (stopped) {
            publish(Esp32RadioPhase.READY, "ESP32 stopped and mode=0 confirmed", baudRate = serial.configuredBaudRate)
        } else {
            publish(Esp32RadioPhase.CLEANUP_REQUIRED, failure?.message, baudRate = serial.configuredBaudRate)
        }
        return failure
    }

    private fun startAlive(generation: Long) {
        check(aliveHandle == null) { "ALIVE scheduler already running" }
        if (!supportsAlive(info?.firmwareVersion.orEmpty())) return
        aliveHandle = aliveScheduler.schedule(ALIVE_INTERVAL_MS) {
            if (running && activeGeneration == generation &&
                state.phase in setOf(Esp32RadioPhase.STARTING_RADIO, Esp32RadioPhase.RADIO_ACTIVE)) {
                enqueue(Esp32Protocol.CMD_ALIVE, generation = generation)
            }
        }
    }

    private fun stopAlive() {
        aliveHandle?.cancel()
        aliveHandle = null
    }

    private fun waitForLink(generation: Long, timeoutMillis: Int): Esp32RadioEvent.Link {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(linkMonitor) {
            while (true) {
                lastLink?.takeIf { it.first == generation }?.let { return it.second }
                fatalError?.let { throw Esp32ProtocolException("ESP32 serial reader failed", it) }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw Esp32ProtocolException("Timed out waiting for ESP32 LINK")
                linkMonitor.wait((remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1))
            }
        }
    }

    private fun synchronizeHello(attempts: Int, timeoutMillis: Int) {
        var failure: Throwable? = null
        repeat(attempts) {
            try {
                request(Esp32Protocol.CMD_HELLO, Esp32Protocol.MSG_INFO, timeoutMillis = timeoutMillis)
                return
            } catch (error: Throwable) {
                failure = error
            }
        }
        throw Esp32ProtocolException("ESP32 HELLO synchronization failed", failure)
    }

    private fun verifiedHello(timeoutMillis: Int): Esp32Info {
        val parsed = Esp32Protocol.parseInfo(
            request(Esp32Protocol.CMD_HELLO, Esp32Protocol.MSG_INFO, timeoutMillis = timeoutMillis)
        )
        if (parsed.protocolVersion != Esp32Protocol.PROTOCOL_VERSION) {
            throw Esp32IncompatibleProtocolException(parsed)
        }
        return parsed
    }

    private fun requestResult(command: Int, payload: ByteArray = byteArrayOf(), timeoutMillis: Int) {
        val reply = request(command, Esp32Protocol.MSG_RESULT, payload, timeoutMillis)
        if (reply.size < 5 || (reply[0].toInt() and 0xff) != command) {
            throw Esp32ProtocolException("Invalid RESULT for command 0x%02X".format(command))
        }
        val code = ByteBuffer.wrap(reply, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (code != 0) throw Esp32ProtocolException(
            "ESP32 command 0x%02X failed with 0x%08X".format(command, code)
        )
    }

    private fun request(
        command: Int,
        replyType: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMillis: Int,
    ): ByteArray = synchronized(requestLock) {
        check(running) { "ESP32 radio session is not running" }
        val pending = PendingReply(replyType, command)
        val token = RequestToken()
        synchronized(replyMonitor) { pendingReply = pending }
        try {
            enqueue(command, payload, token = token)
            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            synchronized(replyMonitor) {
                while (pending.payload == null) {
                    fatalError?.let { throw Esp32ProtocolException("ESP32 serial reader failed", it) }
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        token.cancelled = true
                        synchronized(outboundMonitor) {
                            outbound.removeIf { it.token === token }
                            outboundMonitor.notifyAll()
                        }
                        throw Esp32ProtocolException(
                            "No reply 0x%02X to ESP32 command 0x%02X".format(replyType, command)
                        )
                    }
                    replyMonitor.wait((remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1))
                }
                pending.payload!!
            }
        } finally {
            synchronized(replyMonitor) {
                if (pendingReply === pending) pendingReply = null
            }
        }
    }

    private fun enqueue(
        type: Int,
        payload: ByteArray = byteArrayOf(),
        generation: Long? = null,
        token: RequestToken? = null,
        dropWhenFull: Boolean = false,
    ): Boolean {
        val frame = Esp32Protocol.encodeFrame(type, payload)
        synchronized(outboundMonitor) {
            check(running) { "ESP32 radio session is not running" }
            if (dropWhenFull && outbound.size >= QUEUE_LIMIT) return false
            outbound.addLast(Outbound(type, frame, generation, token))
            outboundMonitor.notifyAll()
        }
        return true
    }

    private fun writeLoop() {
        while (running) {
            val item = synchronized(outboundMonitor) {
                while (outbound.isEmpty() && running) outboundMonitor.wait()
                if (!running) return
                outbound.removeFirst().also { writing = true }
            }
            if (item.token?.cancelled == true ||
                (item.generation != null && item.generation != activeGeneration)) {
                synchronized(outboundMonitor) { writing = false; outboundMonitor.notifyAll() }
                continue
            }
            try {
                awaitCredit(item)
                if (item.token?.cancelled == true ||
                    (item.generation != null && item.generation != activeGeneration)) continue
                serial.write(item.frame, WRITE_TIMEOUT_MS)
                synchronized(outboundMonitor) {
                    written += item.frame.size
                    if (item.type == Esp32Protocol.CMD_HELLO) {
                        written = 0
                        credited = 0
                        writtenOff = 0
                    }
                }
            } catch (error: Throwable) {
                markFatal(error)
                return
            } finally {
                synchronized(outboundMonitor) {
                    writing = false
                    outboundMonitor.notifyAll()
                }
            }
        }
    }

    private fun awaitCredit(item: Outbound) {
        synchronized(outboundMonitor) {
            var last = credited
            var since = System.nanoTime()
            while (running && flowEnabled && !item.token.isCancelled() &&
                (item.generation == null || item.generation == activeGeneration) && (
                    if (item.type == Esp32Protocol.CMD_HELLO) written > credited
                    else written - credited + item.frame.size > FLOW_WINDOW
                )) {
                val now = System.nanoTime()
                if (credited != last) {
                    last = credited
                    since = now
                } else {
                    val quiet = now - since
                    if ((quiet > FLOW_STALL_NS && creditSeenNanos > since + FLOW_STALL_NS * 2 / 3) ||
                        quiet > FLOW_BLIND_NS) {
                        writtenOff += written - credited
                        credited = written
                        break
                    }
                }
                outboundMonitor.wait(FLOW_WAIT_MS)
            }
            if (!running) throw Esp32ProtocolException("ESP32 radio session closed")
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(4096)
        while (running) {
            try {
                val count = serial.read(buffer, READ_TIMEOUT_MS)
                if (count <= 0) continue
                for (frame in reader.feed(buffer, count)) dispatch(frame)
            } catch (error: Throwable) {
                if (running) markFatal(error)
                return
            }
        }
    }

    private fun dispatch(frame: Esp32Frame) {
        if (frame.type == Esp32Protocol.MSG_CREDIT && frame.payload.size == 4) {
            val count = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            synchronized(outboundMonitor) {
                creditSeenNanos = System.nanoTime()
                if (count <= written) {
                    writtenOff = minOf(writtenOff, written - count)
                    credited = maxOf(credited, count + writtenOff)
                    flowEnabled = true
                    outboundMonitor.notifyAll()
                }
            }
            publishEvent(Esp32RadioEvent.Credit(count))
            return
        }

        val pending = pendingReply
        if (pending != null && frame.type == pending.type &&
            (frame.type != Esp32Protocol.MSG_RESULT ||
                frame.payload.firstOrNull()?.toInt()?.and(0xff) == pending.command)) {
            synchronized(replyMonitor) {
                if (pendingReply === pending) {
                    pending.payload = frame.payload
                    replyMonitor.notifyAll()
                    return
                }
            }
        }
        publishEvent(parseEvent(frame))
    }

    private fun parseEvent(frame: Esp32Frame): Esp32RadioEvent = when (frame.type) {
        Esp32Protocol.MSG_LINK -> if (frame.payload.size >= 9) {
            Esp32RadioEvent.Link(
                frame.payload[0].toInt() != 0,
                uint16(frame.payload, 1),
                frame.payload.copyOfRange(3, 9),
            )
        } else Esp32RadioEvent.Unknown(frame.type, frame.payload)
        Esp32Protocol.MSG_RX_MGMT -> parseManagementFrame(frame.payload)
        Esp32Protocol.MSG_RX_ETH -> parseEthernetFrame(frame.payload)
        Esp32Protocol.MSG_TX_DONE -> Esp32RadioEvent.TxDone(frame.payload)
        Esp32Protocol.MSG_STA_JOINED -> if (frame.payload.size >= 9) {
            Esp32RadioEvent.StationJoined(
                frame.payload.copyOfRange(0, 6),
                frame.payload[6].toInt() and 0xff,
                frame.payload[7].toInt(),
                frame.payload[8].toInt() != 0,
            )
        } else Esp32RadioEvent.Unknown(frame.type, frame.payload)
        Esp32Protocol.MSG_STA_LEFT -> if (frame.payload.size >= 8) {
            Esp32RadioEvent.StationLeft(frame.payload.copyOfRange(0, 6), uint16(frame.payload, 6))
        } else Esp32RadioEvent.Unknown(frame.type, frame.payload)
        Esp32Protocol.MSG_STATUS -> try {
            Esp32RadioEvent.Status(Esp32Status.parse(frame.payload))
        } catch (_: Esp32ProtocolException) {
            Esp32RadioEvent.Unknown(frame.type, frame.payload)
        }
        else -> Esp32RadioEvent.Unknown(frame.type, frame.payload)
    }

    private fun parseEthernetFrame(frame: ByteArray): Esp32RadioEvent {
        if (frame.size >= ETHERNET_HEADER_SIZE && uint16BigEndian(frame, 12) == LDN_ETHERTYPE) {
            return Esp32RadioEvent.LdnControlEthernet(
                target = frame.copyOfRange(0, 6),
                source = frame.copyOfRange(6, 12),
                payload = frame.copyOfRange(ETHERNET_HEADER_SIZE, frame.size),
            )
        }
        return Esp32RadioEvent.Ethernet(frame)
    }

    private fun parseManagementFrame(payload: ByteArray): Esp32RadioEvent {
        if (payload.size < MANAGEMENT_PREFIX_SIZE) {
            return Esp32RadioEvent.Unknown(Esp32Protocol.MSG_RX_MGMT, payload)
        }
        val frame = payload.copyOfRange(MANAGEMENT_PREFIX_SIZE, payload.size)
        val frameControl = if (frame.size >= 2) uint16(frame, 0) else 0
        val type = (frameControl ushr 2) and 0x03
        val subtype = (frameControl ushr 4) and 0x0f
        fun address(offset: Int): ByteArray = if (frame.size >= offset + 6) {
            frame.copyOfRange(offset, offset + 6)
        } else byteArrayOf()
        val isLdnAction = type == IEEE80211_MANAGEMENT && subtype == IEEE80211_ACTION &&
            frame.size >= IEEE80211_HEADER_SIZE + LDN_ACTION_PREFIX.size &&
            frame.copyOfRange(
                IEEE80211_HEADER_SIZE,
                IEEE80211_HEADER_SIZE + LDN_ACTION_PREFIX.size,
            ).contentEquals(LDN_ACTION_PREFIX)
        return Esp32RadioEvent.ManagementFrame(
            channel = payload[0].toInt() and 0xff,
            rssi = payload[1].toInt(),
            frameType = type,
            subtype = subtype,
            target = address(4),
            source = address(10),
            bssid = address(16),
            isLdnAction = isLdnAction,
            frame = frame,
        )
    }

    private fun publishEvent(event: Esp32RadioEvent) {
        eventListeners.forEach { listener -> notifyListener("event", listener, event) }
        val generation = activeGeneration
        if (generation != null) operationListeners.filter { it.generation == generation }
            .forEach { notifyListener("operation event", it.listener, event) }
        if (event is Esp32RadioEvent.Link && generation != null) {
            synchronized(linkMonitor) {
                lastLink = generation to event
                linkMonitor.notifyAll()
            }
        }
    }

    private fun awaitOutboundDrained(timeoutMillis: Int) {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(outboundMonitor) {
            while (outbound.isNotEmpty() || writing) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw Esp32ProtocolException("Timed out draining ESP32 writes")
                outboundMonitor.wait((remaining / NANOS_PER_MILLISECOND).coerceAtLeast(1))
            }
        }
    }

    private fun markFatal(error: Throwable) {
        fatalError = error
        running = false
        stopAlive()
        publish(Esp32RadioPhase.ERROR, error.message ?: error.javaClass.simpleName, baudRate = serial.configuredBaudRate)
        synchronized(outboundMonitor) { outboundMonitor.notifyAll() }
        synchronized(replyMonitor) { replyMonitor.notifyAll() }
        synchronized(linkMonitor) { linkMonitor.notifyAll() }
    }

    private fun publish(
        phase: Esp32RadioPhase,
        detail: String? = null,
        generation: Long? = activeGeneration,
        baudRate: Int? = serial.configuredBaudRate,
    ) {
        state = Esp32RadioState(phase, detail, generation, baudRate)
        stateListeners.forEach { listener -> notifyListener("state", listener, state) }
    }

    private fun <T> notifyListener(kind: String, listener: (T) -> Unit, value: T) {
        try {
            listener(value)
        } catch (error: Throwable) {
            diagnostics("ESP32 $kind listener failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun close() {
        stopAlive()
        running = false
        activeGeneration = null
        operationListeners.clear()
        synchronized(outboundMonitor) { outbound.clear(); outboundMonitor.notifyAll() }
        synchronized(replyMonitor) { replyMonitor.notifyAll() }
        synchronized(linkMonitor) { linkMonitor.notifyAll() }
        writerThread?.takeIf { it !== Thread.currentThread() }?.join(500)
        readerThread?.takeIf { it !== Thread.currentThread() }?.join(500)
        aliveScheduler.close()
        publish(Esp32RadioPhase.DISCONNECTED, baudRate = serial.configuredBaudRate)
    }

    private fun RequestToken?.isCancelled(): Boolean = this?.cancelled == true

    companion object {
        const val FAST_BAUD_RATE = 921_600
        const val INITIAL_BAUD_RATE = 115_200
        const val ALIVE_INTERVAL_MS = 1_000L
        const val MODE_IDLE = 0
        const val FLOW_WINDOW = 8_192L
        private const val QUEUE_LIMIT = 512
        private const val COMMAND_TIMEOUT_MS = 3_000
        private const val AP_START_TIMEOUT_MS = 5_000
        private const val STOP_TIMEOUT_MS = 5_000
        private const val LINK_TIMEOUT_MS = 5_000
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_TIMEOUT_MS = 100
        private const val HELLO_ATTEMPTS = 5
        private const val HELLO_TIMEOUT_MS = 1_000
        private const val FAST_HELLO_TIMEOUT_MS = 500
        private const val VERIFICATION_TIMEOUT_MS = 3_000
        private const val FLOW_WAIT_MS = 50L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val ETHERNET_HEADER_SIZE = 14
        private const val LDN_ETHERTYPE = 0x88B7
        private const val MANAGEMENT_PREFIX_SIZE = 2
        private const val IEEE80211_HEADER_SIZE = 24
        private const val IEEE80211_MANAGEMENT = 0
        private const val IEEE80211_ACTION = 13
        private val LDN_ACTION_PREFIX = byteArrayOf(0x7f, 0x00, 0x22, 0xaa.toByte())
        private const val FLOW_STALL_NS = 300_000_000L
        private const val FLOW_BLIND_NS = 5_000_000_000L

        private fun int32(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        private fun uint16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun uint16BigEndian(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        internal fun supportsAlive(version: String): Boolean {
            val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(version) ?: return false
            val parts = match.groupValues.drop(1).map(String::toInt)
            return parts[0] > 1 ||
                (parts[0] == 1 && parts[1] > 4) ||
                (parts[0] == 1 && parts[1] == 4 && parts[2] >= 0)
        }
    }
}
