package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import kotlin.math.max
import kotlin.math.min

class Esp32Client(
    private val serial: SerialIo,
    private val helloAttempts: Int = HELLO_ATTEMPTS,
    private val helloTimeoutMs: Int = HELLO_TIMEOUT_MS,
    private val verificationTimeoutMs: Int = VERIFICATION_TIMEOUT_MS,
) {
    fun hello(): Esp32Info {
        drainPendingInput()
        val reader = Esp32FrameReader()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val unexpectedTypes = linkedSetOf<Int>()

        var synchronized = false
        for (attempt in 0 until helloAttempts) {
            serial.write(Esp32Protocol.helloFrame, WRITE_TIMEOUT_MS)
            if (readInfoPayload(reader, buffer, helloTimeoutMs, unexpectedTypes) != null) {
                synchronized = true
                break
            }
        }
        if (!synchronized) throw responseFailure(reader, unexpectedTypes)

        // Upstream open_serial first synchronizes past a possible board reset, then hello() sends a
        // fresh request whose INFO is parsed and checked for protocol compatibility.
        serial.write(Esp32Protocol.helloFrame, WRITE_TIMEOUT_MS)
        val payload = readInfoPayload(reader, buffer, verificationTimeoutMs, unexpectedTypes)
            ?: throw responseFailure(reader, unexpectedTypes)
        val info = Esp32Protocol.parseInfo(payload)
        if (info.protocolVersion != Esp32Protocol.PROTOCOL_VERSION) {
            throw Esp32IncompatibleProtocolException(info)
        }
        return info
    }

    private fun readInfoPayload(
        reader: Esp32FrameReader,
        buffer: ByteArray,
        timeoutMs: Int,
        unexpectedTypes: MutableSet<Int>,
    ): ByteArray? {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLISECOND
        while (System.nanoTime() < deadline) {
            val remainingMs = max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
            val count = serial.read(buffer, min(READ_SLICE_MS, remainingMs.toInt()))
            if (count <= 0) continue
            for (frame in reader.feed(buffer, count)) {
                when (frame.type) {
                    Esp32Protocol.MSG_CREDIT -> Unit
                    Esp32Protocol.MSG_INFO -> return frame.payload
                    else -> unexpectedTypes.add(frame.type)
                }
            }
        }
        return null
    }

    private fun responseFailure(
        reader: Esp32FrameReader,
        unexpectedTypes: Set<Int>,
    ): Esp32ProtocolException {
        if (reader.hasIncompleteFrame) {
            return Esp32IncompleteResponseException("Timed out with an incomplete ESP32 frame")
        }
        reader.lastError?.let { return it }
        if (unexpectedTypes.isNotEmpty()) {
            val types = unexpectedTypes.joinToString { "0x%02X".format(it) }
            return Esp32HelloTimeoutException("No INFO response; received $types")
        }
        return Esp32HelloTimeoutException("No INFO response from ESP32")
    }

    private fun drainPendingInput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (serial.read(buffer, DRAIN_TIMEOUT_MS) > 0) {
            // The upstream reader continuously consumes boot text and unsolicited startup INFO.
        }
    }

    companion object {
        const val HELLO_ATTEMPTS = 5
        const val HELLO_TIMEOUT_MS = 1_000
        const val VERIFICATION_TIMEOUT_MS = 3_000
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_SLICE_MS = 100
        private const val DRAIN_TIMEOUT_MS = 20
        private const val READ_BUFFER_SIZE = 4096
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal class Esp32FrameReader {
    private val encoded = ArrayList<Byte>()
    var lastError: Esp32MalformedFrameException? = null
        private set

    val hasIncompleteFrame: Boolean
        get() = encoded.isNotEmpty()

    fun feed(data: ByteArray, length: Int = data.size): List<Esp32Frame> {
        require(length in 0..data.size)
        val frames = mutableListOf<Esp32Frame>()
        for (index in 0 until length) {
            val value = data[index]
            if (value.toInt() != 0) {
                encoded.add(value)
                continue
            }
            if (encoded.isEmpty()) continue
            try {
                frames.add(Esp32Protocol.decodeFrame(encoded.toByteArray()))
            } catch (error: Esp32MalformedFrameException) {
                lastError = error
            }
            encoded.clear()
        }
        return frames
    }
}
