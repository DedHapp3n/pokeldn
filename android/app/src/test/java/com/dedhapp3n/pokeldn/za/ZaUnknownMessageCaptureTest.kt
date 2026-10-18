package com.dedhapp3n.pokeldn.za

import org.junit.Assert.*
import org.junit.Test

class ZaUnknownMessageCaptureTest {
    @Test fun preservesReceiveOrderIncludingSamePayloadOnNewSequence() {
        val first = observation(10, 7, 100, byteArrayOf(0x66, 0x01, 1))
        val repeat = observation(10, 8, 120, byteArrayOf(0x66, 0x01, 1))
        val different = observation(10, 9, 140, byteArrayOf(0x66, 0x01, 2))
        val capture = ZaUnknownMessageCapture().record(first).record(repeat).record(different)
        assertEquals(3, capture.received)
        assertEquals(2, capture.uniquePayloads)
        assertEquals(listOf(7, 8, 9), capture.messages.map { it.sequence })
        assertEquals(120, capture.messages[1].lastReceivedAtMillis)
        assertArrayEquals(different.payload, capture.messages.last().payload)
        assertTrue(capture.export().contains("payload=660101"))
        assertTrue(capture.export().contains("channel=Reliable"))
    }

    @Test fun classifiesDocumentedMessagesOnly() {
        assertTrue(ZaUnknownMessageCapture.isKnown(observation(10, 1, 1, ByteArray(1211).also { it[0] = 1 })))
        assertTrue(ZaUnknownMessageCapture.isKnown(observation(11, 1, 1, ByteArray(9).also { it[0] = 0x14; it[1] = 3 })))
        assertFalse(ZaUnknownMessageCapture.isKnown(observation(10, 1, 1, byteArrayOf(0x66, 0x01))))
        assertFalse(ZaUnknownMessageCapture.isKnown(observation(10, 1, 1, byteArrayOf(0x66))))
        assertFalse(ZaUnknownMessageCapture.isKnown(observation(11, 1, 1, byteArrayOf(0x01, 0x00))))
    }

    private fun observation(protocol: Int, sequence: Int, time: Long, payload: ByteArray) = ZaGameMessageObservation(
        protocol, sequence, 0, time, "169.254.1.2", "1122334455667788", payload,
    )
}
