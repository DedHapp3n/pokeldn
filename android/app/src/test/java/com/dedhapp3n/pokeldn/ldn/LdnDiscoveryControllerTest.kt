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
        controller.stop()
    }

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
