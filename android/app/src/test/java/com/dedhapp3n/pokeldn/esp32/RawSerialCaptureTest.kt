package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSerialCaptureTest {
    @Test
    fun idleCaptureDoesNotWriteAndFormatsHexAsciiAndDelimiters() {
        val serial = CaptureSerial(byteArrayOf(0x41, 0, 0x7E, 0x1F))
        val result = RawSerialCapture(serial, maxBytes = 4, captureDurationMs = 20).capture(RawCaptureMode.IDLE)

        assertEquals(0, serial.writes.size)
        assertEquals(1, result.delimiterCount)
        assertTrue(result.limitReached)
        assertTrue(result.displayText().contains("41 00 7E 1F"))
        assertTrue(result.displayText().contains("|A.~.|"))
    }

    @Test
    fun helloCaptureWritesExactlyOneUpstreamHelloAndKeepsRawBytesUnmodified() {
        val raw = byteArrayOf(2, 0x8B.toByte(), 0, 0xFF.toByte())
        val serial = CaptureSerial(raw)
        val result = RawSerialCapture(serial, maxBytes = raw.size, captureDurationMs = 20)
            .capture(RawCaptureMode.ONE_HELLO)

        assertEquals(1, serial.writes.size)
        assertArrayEquals(Esp32Protocol.helloFrame, serial.writes.single())
        assertArrayEquals(raw, result.bytes)
    }

    private class CaptureSerial(bytes: ByteArray) : SerialIo {
        private var pending = bytes
        val writes = mutableListOf<ByteArray>()

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            if (pending.isEmpty()) return 0
            val count = minOf(buffer.size, pending.size)
            pending.copyInto(buffer, endIndex = count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }

        override fun write(bytes: ByteArray, timeoutMillis: Int) {
            writes += bytes.copyOf()
        }
    }
}
