package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import kotlin.math.max
import kotlin.math.min

class Esp32Client(
    private val serial: SerialIo,
    private val helloAttempts: Int = HELLO_ATTEMPTS,
    private val helloTimeoutMs: Int = HELLO_TIMEOUT_MS,
    private val verificationTimeoutMs: Int = VERIFICATION_TIMEOUT_MS,
    private val drainMaxMs: Int = DRAIN_MAX_MS,
    private val diagnostics: (String) -> Unit = {},
) {
    fun hello(): Esp32Info {
        val started = System.nanoTime()
        diagnostics("HELLO test started")
        try {
            val reader = Esp32FrameReader(diagnostics)
            drainPendingInput(reader)
            val buffer = ByteArray(READ_BUFFER_SIZE)
            val unexpectedTypes = linkedSetOf<Int>()

            var synchronized = false
            for (attempt in 1..helloAttempts) {
                writeHello("synchronization attempt $attempt/$helloAttempts")
                if (readInfoPayload(reader, buffer, helloTimeoutMs, unexpectedTypes) != null) {
                    synchronized = true
                    break
                }
                diagnostics("Synchronization attempt $attempt timed out")
            }
            if (!synchronized) throw responseFailure(reader, unexpectedTypes)

            // Upstream open_serial first synchronizes past a possible board reset, then hello()
            // sends a fresh request whose INFO is parsed and checked for protocol compatibility.
            diagnostics("Starting final HELLO verification")
            writeHello("final verification")
            val payload = readInfoPayload(reader, buffer, verificationTimeoutMs, unexpectedTypes)
                ?: throw responseFailure(reader, unexpectedTypes)
            val info = Esp32Protocol.parseInfo(payload)
            if (info.protocolVersion != Esp32Protocol.PROTOCOL_VERSION) {
                throw Esp32IncompatibleProtocolException(info)
            }
            diagnostics("HELLO succeeded in ${elapsedMillis(started)} ms")
            return info
        } catch (error: Exception) {
            diagnostics("HELLO failed in ${elapsedMillis(started)} ms: ${error.message ?: error.javaClass.simpleName}")
            throw error
        }
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
            val readTimeoutMs = min(READ_SLICE_MS, remainingMs.toInt())
            val count = serial.read(buffer, readTimeoutMs)
            logSerialRead("Serial read", buffer, count, readTimeoutMs)
            if (count <= 0) continue
            for (frame in reader.feed(buffer, count)) {
                when (frame.type) {
                    Esp32Protocol.MSG_CREDIT -> diagnostics(
                        "CREDIT received (${frame.payload.size} payload bytes)"
                    )
                    Esp32Protocol.MSG_INFO -> {
                        diagnostics("INFO received (${frame.payload.size} payload bytes)")
                        return frame.payload
                    }
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
        reader.lastSynchronizedError?.let { error ->
            if (error is Esp32ChecksumException) return error
            if (reader.synchronizedMalformedCount >= REPEATED_MALFORMED_THRESHOLD) {
                return Esp32MalformedFrameException(
                    "Received ${reader.synchronizedMalformedCount} malformed frames after stream synchronization: " +
                        error.message
                )
            }
        }
        if (unexpectedTypes.isNotEmpty()) {
            val types = unexpectedTypes.joinToString { "0x%02X".format(it) }
            return Esp32HelloTimeoutException("No INFO response; received $types")
        }
        return Esp32HelloTimeoutException("No INFO response from ESP32")
    }

    private fun drainPendingInput(reader: Esp32FrameReader) {
        diagnostics("Serial drain started")
        val started = System.nanoTime()
        val deadline = started + drainMaxMs * NANOS_PER_MILLISECOND
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var drained = 0
        while (System.nanoTime() < deadline) {
            val remainingMs = max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
            val readTimeoutMs = min(DRAIN_READ_TIMEOUT_MS, remainingMs.toInt())
            val count = serial.read(buffer, readTimeoutMs)
            logSerialRead("Serial drain read", buffer, count, readTimeoutMs)
            if (count <= 0) break
            drained += count
            reader.feed(buffer, count)
        }
        diagnostics("Serial drain completed in ${elapsedMillis(started)} ms ($drained bytes discarded)")
    }

    private fun writeHello(stage: String) {
        val frame = Esp32Protocol.helloFrame
        diagnostics("HELLO write: $stage")
        serial.write(frame, WRITE_TIMEOUT_MS)
        diagnostics("HELLO write completed: ${frame.size} bytes")
    }

    private fun logSerialRead(label: String, buffer: ByteArray, count: Int, timeoutMs: Int) {
        if (readResultsLogged >= MAX_LOGGED_READS) {
            if (readResultsLogged == MAX_LOGGED_READS) {
                diagnostics("Further serial read diagnostics suppressed")
            }
            readResultsLogged++
            return
        }
        readResultsLogged++
        if (count <= 0) {
            diagnostics("$label returned $count bytes (timeout $timeoutMs ms)")
            return
        }
        val shown = minOf(count, MAX_RAW_BYTES_PER_READ, MAX_RAW_BYTES_TOTAL - rawBytesLogged)
        if (shown <= 0) {
            diagnostics("$label returned $count bytes (timeout $timeoutMs ms; raw log limit reached)")
            return
        }
        val raw = buffer.copyOfRange(0, shown).toHex()
        val delimiters = (0 until shown).filter { buffer[it].toInt() == 0 }
        val suffix = if (shown < count) "…" else ""
        diagnostics(
            "$label returned $count bytes (timeout $timeoutMs ms): $raw$suffix; " +
                "0x00 at ${if (delimiters.isEmpty()) "none" else delimiters.joinToString()}"
        )
        rawBytesLogged += shown
    }

    private var readResultsLogged = 0
    private var rawBytesLogged = 0

    companion object {
        const val HELLO_ATTEMPTS = 5
        const val HELLO_TIMEOUT_MS = 1_000
        const val VERIFICATION_TIMEOUT_MS = 3_000
        const val DRAIN_MAX_MS = 200
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val READ_SLICE_MS = 100
        private const val DRAIN_READ_TIMEOUT_MS = 20
        private const val READ_BUFFER_SIZE = 4096
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val REPEATED_MALFORMED_THRESHOLD = 2
        private const val MAX_LOGGED_READS = 128
        private const val MAX_RAW_BYTES_PER_READ = 128
        private const val MAX_RAW_BYTES_TOTAL = 1_024

        private fun elapsedMillis(started: Long): Long =
            (System.nanoTime() - started) / NANOS_PER_MILLISECOND
    }
}

internal class Esp32FrameReader(private val diagnostics: (String) -> Unit = {}) {
    private val encoded = ArrayList<Byte>()
    private var delimiterSeen = false
    private var rejectedLogs = 0
    var preSynchronizationRejected = 0
        private set
    var synchronizedMalformedCount = 0
        private set
    var lastSynchronizedError: Esp32MalformedFrameException? = null
        private set

    val hasIncompleteFrame: Boolean
        get() = delimiterSeen && encoded.isNotEmpty()

    fun feed(data: ByteArray, length: Int = data.size): List<Esp32Frame> {
        require(length in 0..data.size)
        val frames = mutableListOf<Esp32Frame>()
        for (index in 0 until length) {
            val value = data[index]
            if (value.toInt() != 0) {
                encoded.add(value)
                continue
            }
            val wasSynchronized = delimiterSeen
            delimiterSeen = true
            if (encoded.isEmpty()) continue
            val candidate = encoded.toByteArray()
            try {
                val frame = Esp32Protocol.decodeFrame(candidate)
                diagnostics("Decoded frame type 0x%02X (${frame.payload.size} payload bytes)".format(frame.type))
                frames.add(frame)
            } catch (error: Esp32MalformedFrameException) {
                if (wasSynchronized) {
                    synchronizedMalformedCount++
                    lastSynchronizedError = error
                } else {
                    preSynchronizationRejected++
                }
                if (rejectedLogs < MAX_REJECTED_LOGS) {
                    val phase = if (wasSynchronized) "synchronized" else "pre-sync"
                    diagnostics(
                        "Rejected $phase frame (${error.javaClass.simpleName}): ${error.message}; " +
                            "encoded=${candidate.toHex(MAX_REJECTED_BYTES)}"
                    )
                } else if (rejectedLogs == MAX_REJECTED_LOGS) {
                    diagnostics("Further rejected frame diagnostics suppressed")
                }
                rejectedLogs++
            }
            encoded.clear()
        }
        return frames
    }

    companion object {
        private const val MAX_REJECTED_LOGS = 16
        private const val MAX_REJECTED_BYTES = 128
    }
}

private fun ByteArray.toHex(limit: Int = size): String {
    val shown = minOf(size, limit)
    val text = copyOfRange(0, shown).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return if (shown < size) "$text…" else text
}
