package com.dedhapp3n.pokeldn.esp32

import android.hardware.usb.UsbDevice
import com.dedhapp3n.pokeldn.usb.SerialTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

class Esp32RadioSessionTest {
    @Test
    fun accessPointPayloadMatchesUpstreamLayout() {
        val expected = byteArrayOf(11) + "021122334455".hex() +
            SSID.toByteArray() + ByteArray(16) { it.toByte() } + byteArrayOf(7, 0)

        assertArrayEquals(expected, config().toPayload())
    }

    @Test
    fun statusParserRequiresAndReadsMode() {
        assertEquals(0, Esp32Status.parse("mode=0 channel=11".toByteArray()).mode)
        assertEquals(3, Esp32Status.parse("heap=1 mode=3 stations=0".toByteArray()).mode)
        assertThrows(Esp32ProtocolException::class.java) {
            Esp32Status.parse("channel=11".toByteArray())
        }
    }

    @Test
    fun baudTransitionUsesResultThenChangesPortAndVerifiesAgain() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            val states = mutableListOf<Esp32RadioPhase>()
            runtime.addStateListener { states += it.phase }

            val info = runtime.verifyAndPrepare()

            assertEquals("1.5.0", info.firmwareVersion)
            assertEquals(listOf(921_600), serial.baudChanges)
            assertEquals(4, serial.commandCount(Esp32Protocol.CMD_HELLO))
            assertArrayEquals(
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(921_600).array(),
                serial.lastCommand(Esp32Protocol.CMD_BAUD).payload,
            )
            assertEquals(Esp32RadioPhase.READY, runtime.state.phase)
            assertTrue(Esp32RadioPhase.SWITCHING_BAUD in states)
        }
    }

    @Test
    fun resultForAnotherCommandDoesNotSatisfyRequest() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            serial.overrideResultCommand = Esp32Protocol.CMD_STOP

            assertThrows(Esp32ProtocolException::class.java) { runtime.setChannel(11) }
        }
    }

    @Test
    fun channelCommandUsesUpstreamPayload() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            runtime.setChannel(11)
            assertArrayEquals(byteArrayOf(11), serial.lastCommand(Esp32Protocol.CMD_CHANNEL).payload)
        }
    }

    @Test
    fun rawAdvertisementUsesRawTxAndIsOwnedByTheOperation() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val operation = runtime.startAccessPoint(config())
            val advertisement = ByteArray(24) { it.toByte() }

            assertTrue(runtime.sendRaw(operation, advertisement))
            await { serial.commandCount(Esp32Protocol.CMD_RAW_TX) == 1 }
            assertArrayEquals(advertisement, serial.lastCommand(Esp32Protocol.CMD_RAW_TX).payload)

            runtime.stopAccessPoint(operation)
            assertThrows(IllegalStateException::class.java) {
                runtime.sendRaw(operation, advertisement)
            }
        }
    }

    @Test
    fun unsolicitedEventDoesNotConsumeStatusReply() {
        val serial = FakeSerial().apply { eventBeforeStatus = linkFrame(up = true) }
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val events = mutableListOf<Esp32RadioEvent>()
            runtime.addEventListener { events += it }

            assertEquals(0, runtime.status().mode)
            assertTrue(events.any { it is Esp32RadioEvent.Link })
        }
    }

    @Test
    fun commandTimeoutIsBounded() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            serial.respondToStatus = false

            assertThrows(Esp32ProtocolException::class.java) { runtime.status() }
        }
    }

    @Test
    fun creditWindowBlocksAndCreditWakesWriterWithoutBusyPolling() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val operation = runtime.startAccessPoint(config())
            repeat(100) { assertTrue(runtime.sendEthernet(operation, ByteArray(200) { it.toByte() })) }

            await { runtime.creditSnapshot().queued > 0 }
            val blocked = runtime.creditSnapshot()
            assertTrue(blocked.flowEnabled)
            assertTrue(blocked.written - blocked.credited <= Esp32RadioSession.FLOW_WINDOW)
            val writesBeforeCredit = serial.commandCount(Esp32Protocol.CMD_ETH_TX)
            Thread.sleep(30)
            assertEquals(writesBeforeCredit, serial.commandCount(Esp32Protocol.CMD_ETH_TX))

            serial.emitCredit(serial.consumedSinceHello())
            await { serial.commandCount(Esp32Protocol.CMD_ETH_TX) > writesBeforeCredit }
            assertTrue(serial.commandCount(Esp32Protocol.CMD_ETH_TX) > writesBeforeCredit)
        }
    }

    @Test
    fun acceptedEthernetCommandPublishesOnlyAfterSerialWrite() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val operation = runtime.startAccessPoint(config())
            val events = mutableListOf<Esp32RadioEvent>()
            runtime.addOperationEventListener(operation) { events += it }

            assertTrue(runtime.sendEthernet(operation, ByteArray(64)))
            await { events.any { it is Esp32RadioEvent.EthernetCommandWritten } }
            assertEquals(1, serial.commandCount(Esp32Protocol.CMD_ETH_TX))
        }
    }

    @Test
    fun aliveStartsAndStopsOnceForOperation() {
        val serial = FakeSerial()
        val scheduler = FakeAliveScheduler()
        session(serial, scheduler).use { runtime ->
            runtime.verifyAndPrepare()
            val operation = runtime.startAccessPoint(config())
            assertEquals(1, scheduler.scheduled)

            scheduler.fire()
            await { serial.commandCount(Esp32Protocol.CMD_ALIVE) == 1 }
            runtime.stopAccessPoint(operation)

            assertEquals(1, scheduler.cancelled)
            scheduler.fire()
            Thread.sleep(20)
            assertEquals(1, serial.commandCount(Esp32Protocol.CMD_ALIVE))
        }
    }

    @Test
    fun stopAndIdleStatusReturnSameConnectionToReady() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val first = runtime.startAccessPoint(config())
            runtime.stopAccessPoint(first)
            val afterFirst = runtime.creditSnapshot()
            assertEquals(Esp32RadioPhase.READY, runtime.state.phase)
            assertEquals(921_600, serial.configuredBaudRate)

            val second = runtime.startAccessPoint(config())
            runtime.stopAccessPoint(second)
            val afterSecond = runtime.creditSnapshot()
            assertEquals(Esp32RadioPhase.READY, runtime.state.phase)
            assertEquals(2, serial.commandCount(Esp32Protocol.CMD_AP_START))
            assertEquals(2, serial.commandCount(Esp32Protocol.CMD_STOP))
            assertEquals(4, serial.commandCount(Esp32Protocol.CMD_HELLO))
            assertTrue(afterSecond.written > afterFirst.written)
            assertEquals(0, serial.connectCalls)
        }
    }

    @Test
    fun failedStopNeverPublishesReady() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val operation = runtime.startAccessPoint(config())
            serial.respondToStop = false

            assertThrows(Esp32ProtocolException::class.java) { runtime.stopAccessPoint(operation) }
            assertEquals(Esp32RadioPhase.CLEANUP_REQUIRED, runtime.state.phase)
        }
    }

    @Test
    fun failureBeforeApStartStillAttemptsFullCleanup() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            serial.respondToStatus = false

            assertThrows(Esp32ProtocolException::class.java) { runtime.startAccessPoint(config()) }
            assertEquals(1, serial.commandCount(Esp32Protocol.CMD_STOP))
            assertEquals(Esp32RadioPhase.CLEANUP_REQUIRED, runtime.state.phase)
        }
    }

    @Test
    fun failedApStartResultStopsAndConfirmsIdle() {
        val serial = FakeSerial().apply { apStartResultCode = 0x102 }
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()

            assertThrows(Esp32ProtocolException::class.java) { runtime.startAccessPoint(config()) }
            assertEquals(1, serial.commandCount(Esp32Protocol.CMD_STOP))
            assertEquals(Esp32RadioPhase.READY, runtime.state.phase)
        }
    }

    @Test
    fun operationFailureStillStopsAndConfirmsIdle() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            assertEquals("1.5.0", runtime.verifyAndPrepare().firmwareVersion)

            assertThrows(IllegalStateException::class.java) {
                runtime.withAccessPoint(config()) { throw IllegalStateException("cancelled") }
            }

            assertEquals(1, serial.commandCount(Esp32Protocol.CMD_STOP))
            assertEquals(0, runtime.status().mode)
            assertEquals(Esp32RadioPhase.READY, runtime.state.phase)
        }
    }

    @Test
    fun completedGenerationCannotReceiveNextOperationEvents() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val first = runtime.startAccessPoint(config())
            val firstEvents = mutableListOf<Esp32RadioEvent>()
            runtime.addOperationEventListener(first) { firstEvents += it }
            runtime.stopAccessPoint(first)

            val second = runtime.startAccessPoint(config())
            val secondEvents = mutableListOf<Esp32RadioEvent>()
            runtime.addOperationEventListener(second) { secondEvents += it }
            serial.emit(Esp32Protocol.MSG_RX_ETH, byteArrayOf(1, 2, 3))
            await { secondEvents.any { it is Esp32RadioEvent.Ethernet } }

            assertFalse(firstEvents.any { it is Esp32RadioEvent.Ethernet })
            assertTrue(secondEvents.any { it is Esp32RadioEvent.Ethernet })
            runtime.stopAccessPoint(second)
        }
    }

    @Test
    fun allRequiredUnsolicitedEventsAreTyped() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val events = mutableListOf<Esp32RadioEvent>()
            runtime.addEventListener { events += it }
            serial.emitCredit(7)
            serial.emit(Esp32Protocol.MSG_LINK, byteArrayOf(1, 0x34, 0x12) + ByteArray(6))
            serial.emit(Esp32Protocol.MSG_RX_ETH, byteArrayOf(1))
            serial.emit(Esp32Protocol.MSG_TX_DONE, byteArrayOf(2))
            serial.emit(Esp32Protocol.MSG_STA_JOINED, ByteArray(6) + byteArrayOf(3, -1, 1))
            serial.emit(Esp32Protocol.MSG_STA_LEFT, ByteArray(6) + byteArrayOf(4, 0))
            serial.emit(0xFE, byteArrayOf(9))
            await { events.size >= 7 }

            assertTrue(events.any { it is Esp32RadioEvent.Credit })
            assertTrue(events.any { it is Esp32RadioEvent.Link })
            assertTrue(events.any { it is Esp32RadioEvent.Ethernet })
            assertTrue(events.any { it is Esp32RadioEvent.TxDone })
            assertTrue(events.any { it is Esp32RadioEvent.StationJoined })
            assertTrue(events.any { it is Esp32RadioEvent.StationLeft })
            assertTrue(events.any { it is Esp32RadioEvent.Unknown })
        }
    }

    @Test
    fun managementAndLdnControlFramesAreParsedForDiscovery() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val events = mutableListOf<Esp32RadioEvent>()
            runtime.addEventListener { events += it }
            val source = "021122334455".hex()
            val action = byteArrayOf(0xd0.toByte(), 0, 0, 0) + ByteArray(6) { 0xff.toByte() } +
                source + ByteArray(6) { 0xff.toByte() } + byteArrayOf(0, 0) +
                "7f0022aa04000101".hex()
            serial.emit(Esp32Protocol.MSG_RX_MGMT, byteArrayOf(6, -42) + action)
            val ethernet = ByteArray(6) { 0xff.toByte() } + source +
                byteArrayOf(0x88.toByte(), 0xb7.toByte(), 1, 2, 3)
            serial.emit(Esp32Protocol.MSG_RX_ETH, ethernet)
            await {
                events.any { it is Esp32RadioEvent.ManagementFrame } &&
                    events.any { it is Esp32RadioEvent.LdnControlEthernet }
            }

            val management = events.filterIsInstance<Esp32RadioEvent.ManagementFrame>().single()
            assertEquals(6, management.channel)
            assertEquals(-42, management.rssi)
            assertEquals(13, management.subtype)
            assertTrue(management.isLdnAction)
            assertArrayEquals(source, management.source)
            val control = events.filterIsInstance<Esp32RadioEvent.LdnControlEthernet>().single()
            assertArrayEquals(source, control.source)
            assertArrayEquals(byteArrayOf(1, 2, 3), control.payload)
        }
    }

    @Test
    fun protectedQosDataTraceFromRxMgmtExposesDirectionPeerAndCcmpKeyId() {
        val serial = FakeSerial()
        session(serial).use { runtime ->
            runtime.verifyAndPrepare()
            val events = mutableListOf<Esp32RadioEvent>()
            runtime.addEventListener { events += it }
            val bssid = "021122334455".hex()
            val peer = "0a0b0c0d0e0f".hex()
            val destination = "ffffffffffff".hex()
            val frame = ByteArray(40).also {
                it[0] = 0x88.toByte() // QoS data
                it[1] = 0x41 // ToDS + Protected
                bssid.copyInto(it, 4)
                peer.copyInto(it, 10)
                destination.copyInto(it, 16)
                it[29] = 0xa0.toByte() // CCMP extended IV, key ID 2
            }
            serial.emit(Esp32Protocol.MSG_RX_MGMT, byteArrayOf(6, -37) + frame)
            await { events.any { it is Esp32RadioEvent.ManagementFrame } }

            val data = events.filterIsInstance<Esp32RadioEvent.ManagementFrame>().single()
            assertEquals(2, data.frameType)
            assertEquals(8, data.subtype)
            assertTrue(data.toDs)
            assertFalse(data.fromDs)
            assertTrue(data.protectedFrame)
            assertEquals(2, data.ccmpKeyId)
            assertArrayEquals(bssid, data.target)
            assertArrayEquals(peer, data.source)
            assertEquals(40, data.frame.size)
        }
    }

    @Test
    fun aliveCapabilityMatchesUpstreamMinimumFirmware() {
        assertFalse(Esp32RadioSession.supportsAlive("1.3.9"))
        assertTrue(Esp32RadioSession.supportsAlive("1.4.0"))
        assertTrue(Esp32RadioSession.supportsAlive("1.4.0-dev"))
        assertTrue(Esp32RadioSession.supportsAlive("1.5.0"))
        assertFalse(Esp32RadioSession.supportsAlive("invalid"))
    }

    private fun session(
        serial: FakeSerial,
        scheduler: AliveScheduler = FakeAliveScheduler(),
    ) = Esp32RadioSession(
        serial = serial,
        aliveScheduler = scheduler,
        commandTimeoutMillis = 60,
        accessPointTimeoutMillis = 60,
        stopTimeoutMillis = 60,
    )

    private fun config() = Esp32AccessPointConfig(
        channel = 11,
        bssid = "021122334455".hex(),
        ssid = SSID,
        key = ByteArray(16) { it.toByte() },
    )

    private class FakeAliveScheduler : AliveScheduler {
        var scheduled = 0
        var cancelled = 0
        private var task: (() -> Unit)? = null

        override fun schedule(periodMillis: Long, task: () -> Unit): ScheduledHandle {
            assertEquals(Esp32RadioSession.ALIVE_INTERVAL_MS, periodMillis)
            scheduled++
            this.task = task
            return ScheduledHandle {
                cancelled++
                this.task = null
            }
        }

        fun fire() = task?.invoke()
        override fun close() = Unit
    }

    private class FakeSerial : SerialTransport {
        private val monitor = Object()
        private val incoming = ArrayDeque<Byte>()
        private val writes = mutableListOf<Esp32Frame>()
        private var consumed = 0L
        override var configuredBaudRate: Int? = 115_200
        var overrideResultCommand: Int? = null
        var respondToStatus = true
        var respondToStop = true
        var apStartResultCode = 0
        var eventBeforeStatus: ByteArray? = null
        var connectCalls = 0
        val baudChanges = mutableListOf<Int>()
        private var mode = 0

        override fun connect(device: UsbDevice, baudRate: Int) {
            connectCalls++
            configuredBaudRate = baudRate
        }

        override fun setBaudRate(baudRate: Int) {
            baudChanges += baudRate
            configuredBaudRate = baudRate
        }

        override fun disconnect() {
            configuredBaudRate = null
            synchronized(monitor) { monitor.notifyAll() }
        }

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int = synchronized(monitor) {
            if (incoming.isEmpty()) monitor.wait(timeoutMillis.toLong())
            var count = 0
            while (incoming.isNotEmpty() && count < buffer.size) buffer[count++] = incoming.removeFirst()
            count
        }

        override fun write(bytes: ByteArray, timeoutMillis: Int) {
            val frame = Esp32Protocol.decodeFrame(bytes.copyOf(bytes.size - 1))
            synchronized(writes) { writes += frame }
            if (frame.type == Esp32Protocol.CMD_HELLO) consumed = 0
            else consumed += bytes.size
            when (frame.type) {
                Esp32Protocol.CMD_HELLO -> {
                    emit(Esp32Protocol.MSG_INFO, INFO_PAYLOAD)
                    emitCredit(0)
                }
                Esp32Protocol.CMD_BAUD,
                Esp32Protocol.CMD_CHANNEL -> emitResult(overrideResultCommand ?: frame.type)
                Esp32Protocol.CMD_STATUS -> if (respondToStatus) {
                    eventBeforeStatus?.let(::emitBytes)
                    emit(Esp32Protocol.MSG_STATUS, "mode=$mode channel=11".toByteArray())
                }
                Esp32Protocol.CMD_AP_START -> {
                    mode = 3
                    emitResult(overrideResultCommand ?: frame.type, apStartResultCode)
                    if (apStartResultCode == 0) emitBytes(linkFrame(up = true))
                }
                Esp32Protocol.CMD_STOP -> if (respondToStop) {
                    mode = 0
                    emitResult(overrideResultCommand ?: frame.type)
                }
            }
            if (frame.type != Esp32Protocol.CMD_HELLO && frame.type != Esp32Protocol.CMD_ETH_TX) {
                emitCredit(consumed)
            }
        }

        fun commandCount(type: Int): Int = synchronized(writes) { writes.count { it.type == type } }
        fun lastCommand(type: Int): Esp32Frame = synchronized(writes) { writes.last { it.type == type } }
        fun consumedSinceHello(): Long = consumed

        fun emitCredit(value: Long) {
            val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
            emit(Esp32Protocol.MSG_CREDIT, payload)
        }

        fun emit(type: Int, payload: ByteArray) = emitBytes(Esp32Protocol.encodeFrame(type, payload))

        private fun emitResult(command: Int, code: Int = 0) {
            val payload = byteArrayOf(command.toByte()) +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(code).array()
            emit(Esp32Protocol.MSG_RESULT, payload)
        }

        private fun emitBytes(bytes: ByteArray) = synchronized(monitor) {
            bytes.forEach(incoming::addLast)
            monitor.notifyAll()
        }
    }

    companion object {
        private const val SSID = "0123456789abcdef0123456789abcdef"
        private val INFO_PAYLOAD = byteArrayOf(1) + ByteArray(6) { it.toByte() } +
            ByteArray(6) { (it + 6).toByte() } + byteArrayOf(3) +
            "version=1.5.0 target=esp32 idf=6.1".toByteArray()

        private fun linkFrame(up: Boolean): ByteArray = Esp32Protocol.encodeFrame(
            Esp32Protocol.MSG_LINK,
            byteArrayOf(if (up) 1 else 0, 0, 0) + ByteArray(6),
        )

        private fun await(timeoutMillis: Long = 1_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(2)
            assertTrue("Condition was not met within ${timeoutMillis}ms", condition())
        }
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
