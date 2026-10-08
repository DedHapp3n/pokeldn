package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Esp32ProtocolTest {
    @Test
    fun helloEncodingMatchesUpstreamVector() {
        assertEquals("06011bdf05a500", Esp32Protocol.helloFrame.toHex())
    }

    @Test
    fun cobsRoundTripsUpstreamBoundaryLengths() {
        for (size in listOf(0, 1, 253, 254, 255, 256, 509, 1600)) {
            for (input in listOf(ByteArray(size), ByteArray(size) { 1 }, ByteArray(size) { it.toByte() })) {
                val encoded = Esp32Protocol.cobsEncode(input)
                assertTrue(encoded.none { it.toInt() == 0 })
                assertArrayEquals(input, Esp32Protocol.cobsDecode(encoded))
            }
        }
    }

    @Test
    fun validUpstreamInfoResponseIsParsed() {
        val serial = ScriptedSerial { INFO_FRAME }
        val info = Esp32Client(serial).hello()

        assertEquals(1, info.protocolVersion)
        assertEquals("02:11:22:33:44:55", info.stationMac.formatMac())
        assertEquals("02:66:77:88:99:aa", info.accessPointMac.formatMac())
        assertEquals(3, info.chipRevision)
        assertEquals("pokeldn-radio esp32 version=1.4.0 idf=v6.1", info.firmwareText)
        assertEquals("1.4.0", info.firmwareVersion)
        assertEquals(2, serial.writes.size)
        serial.writes.forEach { assertArrayEquals(Esp32Protocol.helloFrame, it) }
    }

    @Test
    fun creditBeforeInfoIsAccepted() {
        val serial = ScriptedSerial { CREDIT_ZERO_FRAME + INFO_FRAME }
        assertEquals(1, Esp32Client(serial).hello().protocolVersion)
    }

    @Test
    fun readerStartingInMiddleOfExistingFrameResynchronizesAtDelimiter() {
        val reader = Esp32FrameReader()
        val stream = INFO_FRAME.copyOfRange(12, INFO_FRAME.size) + CREDIT_ZERO_FRAME + INFO_FRAME
        val frames = reader.feed(stream)

        assertEquals(listOf(Esp32Protocol.MSG_CREDIT, Esp32Protocol.MSG_INFO), frames.map { it.type })
        assertEquals(1, reader.preSynchronizationRejected)
    }

    @Test
    fun partialCobsFrameThenDelimiterDoesNotHideCreditAndInfo() {
        val reader = Esp32FrameReader()
        val partial = byteArrayOf(5, 0x81.toByte(), 1, 0)
        val frames = reader.feed(partial + CREDIT_ZERO_FRAME + INFO_FRAME)

        assertEquals(listOf(Esp32Protocol.MSG_CREDIT, Esp32Protocol.MSG_INFO), frames.map { it.type })
        assertEquals(1, reader.preSynchronizationRejected)
    }

    @Test
    fun bootTextBeforeFramedTrafficIsDiscardedAtFirstDelimiter() {
        val reader = Esp32FrameReader()
        val frames = reader.feed("ets Jun  8 2016 rst:0x1\r\n".toByteArray() + byteArrayOf(0) + INFO_FRAME)

        assertEquals(listOf(Esp32Protocol.MSG_INFO), frames.map { it.type })
        assertEquals(1, reader.preSynchronizationRejected)
    }

    @Test
    fun multipleCreditFramesBeforeInfoAreAccepted() {
        val serial = ScriptedSerial { CREDIT_ZERO_FRAME + CREDIT_ZERO_FRAME + CREDIT_ZERO_FRAME + INFO_FRAME }
        assertEquals(1, Esp32Client(serial).hello().protocolVersion)
    }

    @Test
    fun framesSplitAcrossSingleByteUsbReadsAreReassembled() {
        val serial = ScriptedSerial(maxReadSize = 1) { CREDIT_ZERO_FRAME + INFO_FRAME }
        assertEquals("1.4.0", Esp32Client(serial).hello().firmwareVersion)
    }

    @Test
    fun multipleFramesInOneUsbReadAreAllDecoded() {
        val reader = Esp32FrameReader()
        val frames = reader.feed(CREDIT_ZERO_FRAME + CREDIT_ZERO_FRAME + INFO_FRAME)
        assertEquals(
            listOf(Esp32Protocol.MSG_CREDIT, Esp32Protocol.MSG_CREDIT, Esp32Protocol.MSG_INFO),
            frames.map { it.type },
        )
    }

    @Test
    fun malformedPreSyncDataDoesNotPoisonFollowingInfo() {
        val reader = Esp32FrameReader()
        val malformed = byteArrayOf(0x7F, 1, 2, 3, 0)
        val frames = reader.feed(malformed + INFO_FRAME)

        assertEquals(listOf(Esp32Protocol.MSG_INFO), frames.map { it.type })
        assertEquals(1, reader.preSynchronizationRejected)
        assertEquals(0, reader.synchronizedMalformedCount)
    }

    @Test
    fun oneRejectedPreSyncCandidateDoesNotBecomeTheHelloFailure() {
        val failure = assertThrows(Esp32HelloTimeoutException::class.java) {
            Esp32Client(
                ScriptedSerial { _ -> byteArrayOf(5, 1, 2, 0) },
                helloAttempts = 1,
                helloTimeoutMs = 2,
            ).hello()
        }
        assertTrue(failure.message!!.contains("No INFO"))
    }

    @Test
    fun drainAndHelloSharePartialFrameStateLikeUpstreamReader() {
        val split = 4
        val serial = ScriptedSerial(initial = CREDIT_ZERO_FRAME.copyOfRange(0, split)) { write ->
            if (write == 1) CREDIT_ZERO_FRAME.copyOfRange(split, CREDIT_ZERO_FRAME.size) + INFO_FRAME
            else INFO_FRAME
        }
        assertEquals("1.4.0", Esp32Client(serial).hello().firmwareVersion)
    }

    @Test
    fun synchronizationInfoIsNotParsedUntilFinalHello() {
        val serial = ScriptedSerial { write ->
            if (write == 1) Esp32Protocol.encodeFrame(Esp32Protocol.MSG_INFO, byteArrayOf()) else INFO_FRAME
        }
        assertEquals(1, Esp32Client(serial).hello().protocolVersion)
    }

    @Test
    fun incompleteResponseIsReported() {
        val serial = ScriptedSerial { _ -> byteArrayOf(0) + INFO_FRAME.copyOf(INFO_FRAME.size - 1) }
        assertThrows(Esp32IncompleteResponseException::class.java) {
            Esp32Client(serial, helloAttempts = 1, helloTimeoutMs = 2).hello()
        }
    }

    @Test
    fun readTimeoutRetriesHelloThenReportsFailure() {
        val serial = ScriptedSerial { _ -> byteArrayOf() }
        assertThrows(Esp32HelloTimeoutException::class.java) {
            Esp32Client(serial, helloAttempts = 2, helloTimeoutMs = 2).hello()
        }
        assertEquals(2, serial.writes.size)
    }

    @Test
    fun silentSerialCannotLeaveHelloRunningIndefinitely() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<Esp32Info> {
                Esp32Client(
                    ScriptedSerial { _ -> byteArrayOf() },
                    helloAttempts = 2,
                    helloTimeoutMs = 5,
                    verificationTimeoutMs = 5,
                    drainMaxMs = 5,
                ).hello()
            }
            val failure = assertThrows(ExecutionException::class.java) {
                future.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is Esp32HelloTimeoutException)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun continuousDrainTrafficCannotKeepHelloRunningIndefinitely() {
        val serial = object : SerialIo {
            var writes = 0

            override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
                if (writes > 0) return 0
                CREDIT_ZERO_FRAME.copyInto(buffer)
                return CREDIT_ZERO_FRAME.size
            }

            override fun write(bytes: ByteArray, timeoutMillis: Int) {
                writes++
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<Esp32Info> {
                Esp32Client(
                    serial,
                    helloAttempts = 1,
                    helloTimeoutMs = 5,
                    verificationTimeoutMs = 5,
                    drainMaxMs = 5,
                ).hello()
            }
            val failure = assertThrows(ExecutionException::class.java) {
                future.get(1, TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is Esp32HelloTimeoutException)
            assertEquals(1, serial.writes)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun malformedResponseIsReported() {
        val corrupt = INFO_FRAME.copyOf().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 0x40).toByte() }
        val error = assertThrows(Esp32ChecksumException::class.java) {
            Esp32Client(
                ScriptedSerial { _ -> CREDIT_ZERO_FRAME + corrupt },
                helloAttempts = 1,
                helloTimeoutMs = 2,
            ).hello()
        }
        assertTrue(error.message!!.contains("checksum"))
    }

    @Test
    fun repeatedMalformedTrafficAfterSynchronizationIsReported() {
        val malformed = byteArrayOf(0, 5, 1, 2, 0, 5, 3, 4, 0)
        val error = assertThrows(Esp32MalformedFrameException::class.java) {
            Esp32Client(
                ScriptedSerial { _ -> malformed },
                helloAttempts = 1,
                helloTimeoutMs = 2,
            ).hello()
        }
        assertTrue(error.message!!.contains("2 malformed frames"))
    }

    @Test
    fun shortInfoPayloadIsRejected() {
        val shortInfo = Esp32Protocol.encodeFrame(Esp32Protocol.MSG_INFO, byteArrayOf(1, 2, 3))
        assertThrows(Esp32IncompleteResponseException::class.java) {
            Esp32Client(ScriptedSerial { _ -> shortInfo }).hello()
        }
    }

    @Test
    fun incompatibleProtocolVersionIsRejectedWithBoardInfo() {
        val incompatible = INFO_PAYLOAD.copyOf().also { it[0] = 2 }
        val error = assertThrows(Esp32IncompatibleProtocolException::class.java) {
            Esp32Client(ScriptedSerial { _ -> Esp32Protocol.encodeFrame(Esp32Protocol.MSG_INFO, incompatible) }).hello()
        }
        assertEquals(2, error.info.protocolVersion)
        assertEquals("1.4.0", error.info.firmwareVersion)
    }

    @Test
    fun bootTextAndBadFrameAreSkippedBeforeValidInfo() {
        val noise = "ets Jun  8 2016 rst:0x1\r\n\u0000".toByteArray()
        val malformed = byteArrayOf(2, 1, 0)
        val info = Esp32Client(ScriptedSerial { _ -> noise + malformed + INFO_FRAME }).hello()
        assertEquals("1.4.0", info.firmwareVersion)
    }

    private class ScriptedSerial(
        private val maxReadSize: Int = Int.MAX_VALUE,
        initial: ByteArray = byteArrayOf(),
        private val response: (Int) -> ByteArray,
    ) : SerialIo {
        val writes = mutableListOf<ByteArray>()
        private var pending = initial

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            if (pending.isEmpty()) return 0
            val count = minOf(buffer.size, pending.size, maxReadSize)
            pending.copyInto(buffer, endIndex = count)
            pending = pending.copyOfRange(count, pending.size)
            return count
        }

        override fun write(bytes: ByteArray, timeoutMillis: Int) {
            writes.add(bytes.copyOf())
            pending += response(writes.size)
        }
    }

    companion object {
        private val INFO_PAYLOAD = byteArrayOf(1) +
            "0211223344550266778899aa03".hexToBytes() +
            "pokeldn-radio esp32 version=1.4.0 idf=v6.1".toByteArray()
        private val INFO_FRAME = "3e81010211223344550266778899aa03706f6b656c646e2d726164696f20657370333220".hexToBytes() +
            "76657273696f6e3d312e342e30206964663d76362e31357fb9b400".hexToBytes()
        private val CREDIT_ZERO_FRAME = "028b010101049e76140100".hexToBytes()
    }
}

private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
