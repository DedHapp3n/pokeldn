package com.dedhapp3n.pokeldn.ldn

import com.dedhapp3n.pokeldn.esp32.Esp32RadioEvent
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
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
            advertisementFrame = ByteArray(24),
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

    private class FakeTransport {
        private var listener: ((Esp32RadioEvent) -> Unit)? = null

        fun controller(generation: Long, snapshots: (LdnDiscoverySnapshot) -> Unit) =
            LdnDiscoveryController(
                operation = Esp32RadioOperation(generation),
                advertisementFrame = ByteArray(24),
                sendRaw = { _, _ -> true },
                subscribe = { _, callback ->
                    listener = callback
                    AutoCloseable { listener = null }
                },
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
