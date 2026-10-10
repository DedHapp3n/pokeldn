package com.dedhapp3n.pokeldn.ldn

import com.dedhapp3n.pokeldn.esp32.Esp32RadioEvent
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnDiscoveryControllerTest {
    @Test
    fun advertisementStartsOnceAndStopsBeforeSubscriberDetaches() {
        val scheduler = FakeScheduler()
        val order = mutableListOf<String>()
        var sent = 0
        var listener: ((Esp32RadioEvent) -> Unit)? = null
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(7),
            advertisementFrame = { ByteArray(24) },
            sendRaw = { _, _ -> sent++; true },
            subscribe = { _, callback ->
                listener = callback
                AutoCloseable { order += "unsubscribe"; listener = null }
            },
            scheduler = scheduler.apply { cancellationOrder = order },
        )

        controller.start()
        assertEquals(1, sent)
        assertEquals(1, scheduler.scheduled)
        scheduler.fire()
        assertEquals(2, sent)

        controller.stop()
        assertEquals(listOf("cancel", "unsubscribe"), order)
        scheduler.fire()
        assertEquals(2, sent)
        assertEquals(null, listener)
    }

    @Test
    fun discoveryEventsAreCountedAndOldControllerCannotReceiveNextRun() {
        val firstTransport = FakeTransport()
        val firstSnapshots = mutableListOf<LdnDiscoverySnapshot>()
        val first = firstTransport.controller(1, firstSnapshots::add)
        first.start()
        firstTransport.emit(Esp32RadioEvent.StationJoined(ByteArray(6), 1, 0, true))
        assertTrue(first.currentSnapshot().stationDetected)
        firstTransport.emit(Esp32RadioEvent.StationLeft(ByteArray(6), 3))
        assertFalse(first.currentSnapshot().stationDetected)
        first.stop()

        val secondTransport = FakeTransport()
        val secondSnapshots = mutableListOf<LdnDiscoverySnapshot>()
        val second = secondTransport.controller(2, secondSnapshots::add)
        second.start()
        firstTransport.emit(Esp32RadioEvent.Ethernet(byteArrayOf(1)))
        secondTransport.emit(
            Esp32RadioEvent.LdnControlEthernet(ByteArray(6), ByteArray(6), byteArrayOf(2))
        )

        assertEquals(2, first.currentSnapshot().discoveryActivityCount)
        assertEquals(1, second.currentSnapshot().ldnControlFrames)
        assertFalse(second.currentSnapshot().stationDetected)
        second.stop()
    }

    @Test
    fun authenticationResponseRegistersParticipantInCurrentOperation() {
        val transport = FakeTransport()
        val sent = mutableListOf<ByteArray>()
        val participant = LdnParticipant(
            1,
            "169.254.33.2",
            "0a0b0c0d0e0f".hex(),
            "Console".toByteArray(),
            88,
            0,
        )
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(3),
            advertisementFrame = { ByteArray(24) },
            sendRaw = { _, _ -> true },
            sendEthernet = { _, frame -> sent += frame; true },
            authenticate = { _, _ -> LdnAuthenticationOutcome(byteArrayOf(9, 8), 0, true, participant) },
            subscribe = transport::subscribe,
            scheduler = FakeScheduler(),
        )
        controller.start()
        transport.emit(
            Esp32RadioEvent.LdnControlEthernet(
                "021122334455".hex(),
                participant.mac,
                byteArrayOf(1),
            )
        )

        assertEquals(1, sent.size)
        assertArrayEquals("0a0b0c0d0e0f02112233445588b70908".hex(), sent.single())
        assertEquals(1, controller.currentSnapshot().authenticationRequests)
        assertEquals(1, controller.currentSnapshot().authenticationResponses)
        assertTrue(controller.currentSnapshot().participantRegistered)
        assertTrue(controller.currentSnapshot().participantEverRegistered)
        controller.stop()
    }

    @Test
    fun schedulerRepeatedlyTransmitsUpdatedAdvertisementAfterRegistration() {
        val transport = FakeTransport()
        val scheduler = FakeScheduler()
        val frames = mutableListOf<ByteArray>()
        val participant = LdnParticipant(
            1, "169.254.33.2", "0a0b0c0d0e0f".hex(), "Console".toByteArray(), 88, 0,
        )
        var registered = false
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(31),
            advertisementFrame = { ByteArray(24) { if (registered) 2 else 1 } },
            sendRaw = { _, frame -> frames += frame; true },
            sendEthernet = { _, _ -> true },
            authenticate = { _, _ ->
                registered = true
                LdnAuthenticationOutcome(byteArrayOf(1), 0, true, participant)
            },
            subscribe = transport::subscribe,
            scheduler = scheduler,
        )

        controller.start()
        transport.emit(Esp32RadioEvent.StationJoined(participant.mac, 1, 0, true))
        transport.emit(Esp32RadioEvent.LdnControlEthernet("021122334455".hex(), participant.mac, byteArrayOf(1)))
        scheduler.fire()
        scheduler.fire()

        assertEquals(3, frames.size)
        assertEquals(1, frames.first()[0].toInt())
        assertTrue(frames.drop(1).all { it[0].toInt() == 2 })
        val snapshot = controller.currentSnapshot()
        assertEquals(2, snapshot.registeredAdvertisementsSent)
        assertEquals(1, snapshot.participantIndex)
        assertEquals("169.254.33.2", snapshot.participantIp)
        assertArrayEquals(participant.mac, snapshot.registeredParticipant!!.mac)
        controller.stop()
    }

    @Test
    fun stationLeavePreservesPreDisconnectPiaBoundaryAndCounters() {
        val transport = FakeTransport()
        val participant = LdnParticipant(
            1, "169.254.33.2", "0a0b0c0d0e0f".hex(), "Console".toByteArray(), 88, 0,
        )
        val pia = LdnPiaHost(
            ssid = ByteArray(16) { it.toByte() },
            hostMac = "021122334455".hex(),
            networkNumber = 33,
            maxParticipants = 6,
            randomBytes = { size -> ByteArray(size) { size.toByte() } },
        )
        pia.participantJoined(participant, 0)
        pia.tick(0)
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(4),
            advertisementFrame = { ByteArray(24) },
            sendRaw = { _, _ -> true },
            authenticate = { _, _ -> LdnAuthenticationOutcome(byteArrayOf(1), 0, true, participant) },
            removeParticipant = { participant },
            piaHost = pia,
            subscribe = transport::subscribe,
            scheduler = FakeScheduler(),
        )
        controller.start()
        transport.emit(Esp32RadioEvent.LdnControlEthernet(ByteArray(6), participant.mac, byteArrayOf(1)))
        transport.emit(Esp32RadioEvent.TxDone(ByteArray(12).also { it[8] = 1 }))
        transport.emit(Esp32RadioEvent.TxDone(ByteArray(12)))
        val beforeLeave = controller.currentSnapshot()
        transport.emit(Esp32RadioEvent.StationLeft(participant.mac, 3))

        val final = controller.currentSnapshot()
        assertEquals(LdnPiaStage.NET_PROBING, final.piaStage)
        assertEquals(beforeLeave.piaNetRequests, final.piaNetRequests)
        assertFalse(final.participantRegistered)
        assertTrue(final.participantEverRegistered)
        assertEquals(1, final.ethernetTxAcknowledged)
        assertEquals(1, final.ethernetTxUnacknowledged)
        assertEquals(beforeLeave.firstEthernetTxAcknowledged, final.firstEthernetTxAcknowledged)
        controller.stop()
    }

    @Test
    fun piaTransmissionCountersSeparateGenerationQueueSerialAndFirmwareBoundaries() {
        val transport = FakeTransport()
        val participant = LdnParticipant(
            1, "169.254.33.2", "0a0b0c0d0e0f".hex(), "Console".toByteArray(), 88, 0,
        )
        val pia = LdnPiaHost(
            ssid = ByteArray(16) { it.toByte() }, hostMac = "021122334455".hex(),
            networkNumber = 33, maxParticipants = 6,
            randomBytes = { size -> ByteArray(size) { size.toByte() } },
        )
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(5), advertisementFrame = { ByteArray(24) },
            sendRaw = { _, _ -> true }, sendEthernet = { _, _ -> true },
            authenticate = { _, _ -> LdnAuthenticationOutcome(byteArrayOf(1), 0, true, participant) },
            piaHost = pia,
            piaTransport = LdnPiaUdpTransport("169.254.33.1", "021122334455".hex()),
            subscribe = transport::subscribe, scheduler = FakeScheduler(),
        )
        controller.start()
        transport.emit(Esp32RadioEvent.LdnControlEthernet("021122334455".hex(), participant.mac, byteArrayOf(1)))
        transport.emit(Esp32RadioEvent.EthernetCommandWritten(150))
        transport.emit(Esp32RadioEvent.TxDone(ByteArray(12).also { it[8] = 1 }))
        transport.emit(Esp32RadioEvent.TxDone(ByteArray(12)))
        transport.emit(Esp32RadioEvent.TxDone(ByteArray(12) { if (it in 4..7) -1 else 0 }))

        val snapshot = controller.currentSnapshot()
        assertEquals(2, snapshot.piaDatagramsGenerated)
        assertEquals(3, snapshot.ethernetFramesSubmitted)
        assertEquals(3, snapshot.ethernetFramesAccepted)
        assertEquals(1, snapshot.ethernetCommandsWritten)
        assertEquals(2, snapshot.ethernetTxCompleted)
        assertEquals(1, snapshot.ethernetTxAcknowledged)
        assertEquals(1, snapshot.ethernetTxUnacknowledged)
        assertEquals(true, snapshot.firstEthernetTxAcknowledged)
        controller.stop()
    }

    @Test
    fun rawDataTracesPreservePostRegistrationPeerEvidence() {
        val transport = FakeTransport()
        val participant = LdnParticipant(
            1, "169.254.33.2", "0a0b0c0d0e0f".hex(), "Console".toByteArray(), 88, 0,
        )
        val controller = LdnDiscoveryController(
            operation = Esp32RadioOperation(6), advertisementFrame = { ByteArray(24) },
            sendRaw = { _, _ -> true }, sendEthernet = { _, _ -> true },
            authenticate = { _, _ -> LdnAuthenticationOutcome(byteArrayOf(1), 0, true, participant) },
            removeParticipant = { participant }, subscribe = transport::subscribe,
            scheduler = FakeScheduler(),
        )
        controller.start()
        transport.emit(dataTrace("111111111111".hex(), protected = false))
        transport.emit(Esp32RadioEvent.LdnControlEthernet(ByteArray(6), participant.mac, byteArrayOf(1)))
        transport.emit(dataTrace(participant.mac, protected = true, keyId = 2))
        transport.emit(dataTrace("222222222222".hex(), protected = true, keyId = 1))
        transport.emit(Esp32RadioEvent.StationLeft(participant.mac, 3))

        val snapshot = controller.currentSnapshot()
        assertEquals(3, snapshot.rawDataTraces)
        assertEquals(2, snapshot.rawDataAfterRegistration)
        assertEquals(1, snapshot.rawPeerDataAfterRegistration)
        assertEquals(1, snapshot.rawProtectedPeerDataAfterRegistration)
        val first = snapshot.firstPeerDataTrace!!
        assertTrue(first.toDs)
        assertFalse(first.fromDs)
        assertTrue(first.protectedFrame)
        assertArrayEquals(participant.mac, first.sourceMac)
        assertArrayEquals("021122334455".hex(), first.targetMac)
        assertEquals(40, first.frameLength)
        assertEquals(2, first.ccmpKeyId)
        controller.stop()
    }

    private fun dataTrace(source: ByteArray, protected: Boolean, keyId: Int? = null) =
        Esp32RadioEvent.ManagementFrame(
            channel = 6,
            rssi = -40,
            frameType = 2,
            subtype = 8,
            target = "021122334455".hex(),
            source = source,
            bssid = "ffffffffffff".hex(),
            isLdnAction = false,
            frame = ByteArray(40),
            toDs = true,
            fromDs = false,
            protectedFrame = protected,
            ccmpKeyId = keyId,
        )

    private class FakeTransport {
        private var listener: ((Esp32RadioEvent) -> Unit)? = null

        fun subscribe(
            @Suppress("UNUSED_PARAMETER") operation: Esp32RadioOperation,
            callback: (Esp32RadioEvent) -> Unit,
        ): AutoCloseable {
            listener = callback
            return AutoCloseable { listener = null }
        }

        fun controller(generation: Long, snapshots: (LdnDiscoverySnapshot) -> Unit) =
            LdnDiscoveryController(
                operation = Esp32RadioOperation(generation),
                advertisementFrame = { ByteArray(24) },
                sendRaw = { _, _ -> true },
                subscribe = ::subscribe,
                scheduler = FakeScheduler(),
                onSnapshot = snapshots,
            )

        fun emit(event: Esp32RadioEvent) = listener?.invoke(event)
    }

    private class FakeScheduler : LdnAdvertisementScheduler {
        var scheduled = 0
        var cancellationOrder: MutableList<String>? = null
        private var task: (() -> Unit)? = null

        override fun schedule(periodMillis: Long, task: () -> Unit): LdnScheduledHandle {
            assertEquals(LdnAdvertisementBuilder.ADVERTISEMENT_INTERVAL_MS, periodMillis)
            scheduled++
            this.task = task
            return LdnScheduledHandle {
                cancellationOrder?.add("cancel")
                this.task = null
            }
        }

        fun fire() = task?.invoke()
        override fun close() = Unit
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
