package com.dedhapp3n.pokeldn.usb

import com.hoho.android.usbserial.driver.SerialTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UsbSerialTransportTest {
    @Test
    fun partialTimeoutResumesOnlyTheUnsentSuffixWithAFreshTimeout() {
        val payload = ByteArray(142) { it.toByte() }
        val writes = mutableListOf<Pair<ByteArray, Int>>()
        var attempt = 0

        writeSerialFully(payload, 1_000) { remaining, timeout ->
            writes += remaining.copyOf() to timeout
            if (attempt++ == 0) throw SerialTimeoutException("partial", 128)
        }

        assertEquals(listOf(142, 14), writes.map { it.first.size })
        assertEquals(listOf(1_000, 1_000), writes.map { it.second })
        assertArrayEquals(payload.copyOfRange(128, 142), writes[1].first)
    }

    @Test
    fun repeatedPartialTimeoutsAlwaysAdvanceWithoutDuplicatingBytes() {
        val payload = ByteArray(142) { (it xor 0x55).toByte() }
        val writes = mutableListOf<ByteArray>()
        var attempt = 0

        writeSerialFully(payload, 1_000) { remaining, _ ->
            writes += remaining.copyOf()
            when (attempt++) {
                0 -> throw SerialTimeoutException("first partial", 64)
                1 -> throw SerialTimeoutException("second partial", 64)
            }
        }

        assertEquals(listOf(142, 78, 14), writes.map { it.size })
        assertArrayEquals(payload.copyOfRange(64, 142), writes[1])
        assertArrayEquals(payload.copyOfRange(128, 142), writes[2])
    }

    @Test
    fun zeroProgressTimeoutIsStillFatal() {
        val error = assertThrows(SerialTimeoutException::class.java) {
            writeSerialFully(ByteArray(142), 1_000) { _, _ ->
                throw SerialTimeoutException("no progress", 0)
            }
        }

        assertEquals(0, error.bytesTransferred)
    }
}
