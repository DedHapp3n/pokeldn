package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSerialCaptureTest {
    @Test
    fun validCreditStreamHasUpstreamCompatibleStatistics() {
        val first = "028b010101049e76140100".hexToBytes()
        val second = creditFrame(1024)
        val analysis = analyze(first + second)

        assertEquals(first.size + second.size, analysis.totalBytes)
        assertEquals(2, analysis.delimiterCount)
        assertEquals(2, analysis.candidateCount)
        assertEquals(2, analysis.validCobsFrames)
        assertEquals(2, analysis.validCrcFrames)
        assertEquals(2, analysis.creditFrames)
        assertEquals(0, analysis.infoFrames)
        assertEquals(0, analysis.malformedCandidates)
        assertEquals(mapOf(Esp32Protocol.MSG_CREDIT to 2), analysis.frameTypes)
    }

    @Test
    fun multipleValidFrameTypesCountCreditAndStructurallyValidInfo() {
        val infoPayload = byteArrayOf(Esp32Protocol.PROTOCOL_VERSION.toByte()) +
            ByteArray(6) { it.toByte() } + ByteArray(6) { (it + 6).toByte() } + byteArrayOf(3) +
            "pokeldn-radio esp32 version=test".toByteArray()
        val bytes = creditFrame(7) + Esp32Protocol.encodeFrame(Esp32Protocol.MSG_INFO, infoPayload) +
            Esp32Protocol.encodeFrame(0x83, "log".toByteArray())
        val analysis = analyze(bytes)

        assertEquals(3, analysis.validCrcFrames)
        assertEquals(1, analysis.creditFrames)
        assertEquals(1, analysis.infoFrames)
        assertEquals(1, analysis.frameTypes[0x83])
        assertEquals(0, analysis.malformedCandidates)
    }

    @Test
    fun fragmentedInputProducesSameStatistics() {
        val bytes = creditFrame(99) + creditFrame(100)
        val analyzer = PassiveProtocolAnalyzer()
        bytes.forEach { analyzer.feed(byteArrayOf(it)) }

        val analysis = analyzer.snapshot()
        assertEquals(bytes.size, analysis.totalBytes)
        assertEquals(2, analysis.validCrcFrames)
        assertEquals(2, analysis.creditFrames)
        assertEquals(0, analysis.malformedCandidates)
    }

    @Test
    fun malformedRandomInputSeparatesCobsAndStructureFailures() {
        val analysis = analyze(byteArrayOf(7, 1, 0, 1, 0))

        assertEquals(2, analysis.candidateCount)
        assertEquals(1, analysis.validCobsFrames)
        assertEquals(0, analysis.validCrcFrames)
        assertEquals(1, analysis.cobsFailures)
        assertEquals(1, analysis.checksumFailures)
        assertEquals(2, analysis.malformedCandidates)
    }

    @Test
    fun crcCorruptionRemainsCobsValidButFailsChecksum() {
        val corrupted = Esp32Protocol.encodeFrame(0x83, byteArrayOf(0x41))
        corrupted[2] = (corrupted[2].toInt() xor 1).toByte()
        val analysis = analyze(corrupted)

        assertEquals(1, analysis.validCobsFrames)
        assertEquals(0, analysis.validCrcFrames)
        assertEquals(0, analysis.cobsFailures)
        assertEquals(1, analysis.checksumFailures)
        assertEquals(1, analysis.malformedCandidates)
    }

    @Test
    fun mixedMalformedAndValidInputRetainsTheValidFrame() {
        val valid = creditFrame(42)
        val analysis = analyze(byteArrayOf(0x7F, 1, 2, 0) + valid)

        assertEquals(2, analysis.candidateCount)
        assertEquals(1, analysis.validCrcFrames)
        assertEquals(1, analysis.creditFrames)
        assertEquals(1, analysis.malformedCandidates)
    }

    @Test
    fun passiveCaptureSendsNothingAndKeepsRawBytesUnmodified() {
        val raw = creditFrame(0)
        val serial = CaptureSerial(raw)
        val result = RawSerialCapture(serial, maxBytes = raw.size, captureDurationMs = 20).capture(460800)

        assertEquals(0, serial.writes.size)
        assertArrayEquals(raw, result.bytes)
        assertEquals(460800, result.baudRate)
        assertEquals(1, result.analysis.creditFrames)
        assertTrue(result.limitReached)
        assertTrue(result.displayText().contains("Baud: 460800"))
        assertTrue(result.displayText().contains("Valid CRC32 frames: 1"))
    }

    private fun creditFrame(value: Int): ByteArray = Esp32Protocol.encodeFrame(
        Esp32Protocol.MSG_CREDIT,
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()),
    )

    private fun analyze(bytes: ByteArray): PassiveProtocolAnalysis =
        PassiveProtocolAnalyzer().apply { feed(bytes) }.snapshot()

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

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
