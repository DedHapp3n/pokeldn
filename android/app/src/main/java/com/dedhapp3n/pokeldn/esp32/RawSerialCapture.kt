package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import com.dedhapp3n.pokeldn.usb.UsbSerialOpenDiagnostic
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

enum class RawReadBufferMode(val label: String) {
    REUSED("Reused read buffer"),
    FRESH_SENTINEL("Fresh AA/55 buffer per read"),
}

data class RawReadCallDiagnostic(
    val index: Int,
    val requestedBytes: Int,
    val returnedBytes: Int,
    val elapsedMicros: Long,
    val firstBytes: ByteArray,
    val identicalToPrevious: Boolean,
)

data class RawReadDiagnostics(
    val totalCalls: Int,
    val recordedCalls: List<RawReadCallDiagnostic>,
    val totalBytesReported: Int,
    val totalBytesAppended: Int,
    val identicalConsecutiveReads: Int,
    val sentinelMatchingPositions: Int,
    val fullyUnchangedSentinelReads: Int,
    val theoreticalFreshUartBytes: Double,
)

data class PassiveProtocolAnalysis(
    val totalBytes: Int,
    val delimiterCount: Int,
    val candidateCount: Int,
    val validCobsFrames: Int,
    val validCrcFrames: Int,
    val cobsFailures: Int,
    val checksumFailures: Int,
    val malformedCandidates: Int,
    val creditFrames: Int,
    val infoFrames: Int,
    val frameTypes: Map<Int, Int>,
)

/** Strictly analyzes delimiter-terminated candidates without changing or repairing the captured bytes. */
class PassiveProtocolAnalyzer {
    private val candidate = ArrayList<Byte>()
    private var totalBytes = 0
    private var delimiterCount = 0
    private var candidateCount = 0
    private var validCobsFrames = 0
    private var validCrcFrames = 0
    private var cobsFailures = 0
    private var checksumFailures = 0
    private var creditFrames = 0
    private var infoFrames = 0
    private val frameTypes = linkedMapOf<Int, Int>()

    fun feed(bytes: ByteArray) {
        totalBytes += bytes.size
        for (byte in bytes) {
            if (byte.toInt() != 0) {
                candidate += byte
                continue
            }
            delimiterCount++
            if (candidate.isNotEmpty()) analyzeCandidate(candidate.toByteArray())
            candidate.clear()
        }
    }

    fun snapshot(): PassiveProtocolAnalysis = PassiveProtocolAnalysis(
        totalBytes = totalBytes,
        delimiterCount = delimiterCount,
        candidateCount = candidateCount,
        validCobsFrames = validCobsFrames,
        validCrcFrames = validCrcFrames,
        cobsFailures = cobsFailures,
        checksumFailures = checksumFailures,
        malformedCandidates = candidateCount - validCrcFrames,
        creditFrames = creditFrames,
        infoFrames = infoFrames,
        frameTypes = frameTypes.toMap(),
    )

    private fun analyzeCandidate(encoded: ByteArray) {
        candidateCount++
        try {
            Esp32Protocol.cobsDecode(encoded)
            validCobsFrames++
        } catch (_: Esp32CobsException) {
            cobsFailures++
            return
        }

        val frame = try {
            Esp32Protocol.decodeFrame(encoded)
        } catch (_: Esp32ChecksumException) {
            checksumFailures++
            return
        } catch (_: Esp32MalformedFrameException) {
            checksumFailures++
            return
        }
        validCrcFrames++
        frameTypes[frame.type] = (frameTypes[frame.type] ?: 0) + 1
        if (frame.type == Esp32Protocol.MSG_CREDIT && frame.payload.size == 4) creditFrames++
        if (frame.type == Esp32Protocol.MSG_INFO) {
            try {
                Esp32Protocol.parseInfo(frame.payload)
                infoFrames++
            } catch (_: Esp32ProtocolException) {
                // It is a CRC-valid INFO type, but not a structurally valid INFO payload.
            }
        }
    }
}

data class RawSerialCaptureResult(
    val baudRate: Int,
    val bufferMode: RawReadBufferMode,
    val bytes: ByteArray,
    val durationMicros: Long,
    val limitReached: Boolean,
    val analysis: PassiveProtocolAnalysis,
    val readDiagnostics: RawReadDiagnostics,
    val usbDiagnostic: UsbSerialOpenDiagnostic?,
) {
    val durationMs: Double
        get() = durationMicros / 1_000.0
    val bytesPerSecond: Double
        get() = if (durationMicros > 0) bytes.size * 1_000_000.0 / durationMicros else 0.0

    fun displayText(): String = buildString {
        appendLine("Mode: Passive (no data sent)")
        appendLine("Read buffers: ${bufferMode.label}")
        appendLine("Baud: $baudRate")
        appendLine("Bytes: ${bytes.size}${if (limitReached) " (capture limit reached)" else ""}")
        appendLine("Duration: %.3f ms".format(durationMs))
        appendLine("USB delivery rate: %.1f bytes/s".format(bytesPerSecond))
        appendLine("Maximum fresh UART bytes during capture (8N1): %.1f".format(readDiagnostics.theoreticalFreshUartBytes))
        appendLine("Read calls: ${readDiagnostics.totalCalls}")
        appendLine("Bytes reported by read(): ${readDiagnostics.totalBytesReported}")
        appendLine("Bytes appended: ${readDiagnostics.totalBytesAppended}")
        appendLine("Identical consecutive reads: ${readDiagnostics.identicalConsecutiveReads}")
        if (bufferMode == RawReadBufferMode.FRESH_SENTINEL) {
            appendLine("AA/55 sentinel positions unchanged inside returned ranges: ${readDiagnostics.sentinelMatchingPositions}")
            appendLine("Fully unchanged sentinel reads: ${readDiagnostics.fullyUnchangedSentinelReads}")
        }
        appendUsbDiagnostic(usbDiagnostic)
        appendLine("0x00 delimiters: ${analysis.delimiterCount}")
        appendLine("Candidates: ${analysis.candidateCount}")
        appendLine("Valid COBS frames: ${analysis.validCobsFrames}")
        appendLine("Valid CRC32 frames: ${analysis.validCrcFrames}")
        appendLine("Valid CREDIT: ${analysis.creditFrames}")
        appendLine("Valid INFO: ${analysis.infoFrames}")
        appendLine("Malformed: ${analysis.malformedCandidates}")
        appendLine("COBS failures: ${analysis.cobsFailures}")
        appendLine("CRC/structure failures: ${analysis.checksumFailures}")
        appendLine("Frame types: ${formatFrameTypes(analysis.frameTypes)}")
        appendLine("Read call details (${readDiagnostics.recordedCalls.size} recorded):")
        for (call in readDiagnostics.recordedCalls) {
            val first = call.firstBytes.toHex(" ")
            appendLine(
                "#${call.index} requested=${call.requestedBytes} returned=${call.returnedBytes} " +
                    "elapsed=${call.elapsedMicros} us identical=${call.identicalToPrevious} first32=$first"
            )
        }
        if (readDiagnostics.recordedCalls.size < readDiagnostics.totalCalls) {
            appendLine("${readDiagnostics.totalCalls - readDiagnostics.recordedCalls.size} additional read calls omitted")
        }
        appendLine("Offset  Hex                                               ASCII")
        for (offset in bytes.indices step BYTES_PER_LINE) {
            val end = min(offset + BYTES_PER_LINE, bytes.size)
            val row = bytes.copyOfRange(offset, end)
            val hex = row.toHex(" ")
            val ascii = row.joinToString("") {
                val value = it.toInt() and 0xFF
                if (value in 0x20..0x7E) value.toChar().toString() else "."
            }
            append("%04X    %-47s  |%s|\n".format(offset, hex, ascii))
        }
    }

    private fun StringBuilder.appendUsbDiagnostic(info: UsbSerialOpenDiagnostic?) {
        if (info == null) {
            appendLine("USB open diagnostics: unavailable")
            return
        }
        appendLine("USB VID/PID: %04X:%04X".format(info.vendorId, info.productId))
        appendLine("Serial driver: ${info.driverClass}")
        appendLine("Serial port class: ${info.portClass}")
        appendLine("Ports: ${info.portCount}; selected index: ${info.selectedPortIndex}")
        appendLine("Requested baud: ${info.requestedBaudRate}")
        appendLine("setParameters completed: ${info.setParametersCompleted}")
        if (info.endpoints.isEmpty()) appendLine("USB endpoints: none reported")
        for (endpoint in info.endpoints) {
            val address = "%02X".format(endpoint.address)
            appendLine(
                "USB endpoint: interface=${endpoint.interfaceIndex} index=${endpoint.endpointIndex} " +
                    "address=0x$address type=${endpoint.type} direction=${endpoint.direction} " +
                    "maxPacket=${endpoint.maxPacketSize}"
            )
        }
    }

    private fun formatFrameTypes(types: Map<Int, Int>): String =
        if (types.isEmpty()) "none"
        else types.entries.joinToString { (type, count) -> "0x%02X=%d".format(type, count) }

    companion object {
        private const val BYTES_PER_LINE = 16
    }
}

enum class RawCapturePhase {
    IDLE,
    CAPTURING,
    COMPLETE,
    FAILED,
}

data class RawCaptureState(
    val phase: RawCapturePhase = RawCapturePhase.IDLE,
    val result: RawSerialCaptureResult? = null,
    val detail: String? = null,
)

class RawSerialCapture(
    private val serial: SerialIo,
    private val maxBytes: Int = MAX_BYTES,
    private val captureDurationMs: Int = CAPTURE_DURATION_MS,
    private val diagnostics: (String) -> Unit = {},
) {
    fun capture(
        baudRate: Int,
        bufferMode: RawReadBufferMode,
        usbDiagnostic: UsbSerialOpenDiagnostic? = null,
    ): RawSerialCaptureResult {
        val started = System.nanoTime()
        diagnostics("Passive raw capture started at $baudRate baud using ${bufferMode.label}; no data will be sent")
        val captured = ByteArray(maxBytes)
        val reusedBuffer = ByteArray(READ_BUFFER_SIZE)
        val calls = ArrayList<RawReadCallDiagnostic>()
        var totalCalls = 0
        var totalReported = 0
        var size = 0
        var identicalConsecutive = 0
        var sentinelMatches = 0
        var fullyUnchangedSentinelReads = 0
        var previousRead: ByteArray? = null
        val deadline = started + captureDurationMs * NANOS_PER_MILLISECOND
        while (size < maxBytes && System.nanoTime() < deadline) {
            val remainingMs = max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
            val timeoutMs = min(READ_TIMEOUT_MS, remainingMs.toInt())
            val readBuffer = if (bufferMode == RawReadBufferMode.REUSED) reusedBuffer
                else freshSentinelBuffer(READ_BUFFER_SIZE)
            val readStarted = System.nanoTime()
            val count = serial.read(readBuffer, timeoutMs)
            val readElapsedMicros = (System.nanoTime() - readStarted) / NANOS_PER_MICROSECOND
            totalCalls++
            if (count < 0 || count > readBuffer.size) {
                throw IOException("Serial read returned invalid byte count $count for a ${readBuffer.size}-byte buffer")
            }
            totalReported += count
            val returned = readBuffer.copyOf(count)
            val identical = returned.isNotEmpty() && previousRead?.contentEquals(returned) == true
            if (identical) identicalConsecutive++
            if (bufferMode == RawReadBufferMode.FRESH_SENTINEL && count > 0) {
                val matches = returned.indices.count { returned[it] == sentinelByte(it) }
                sentinelMatches += matches
                if (matches == count) fullyUnchangedSentinelReads++
            }
            if (calls.size < MAX_RECORDED_CALLS) {
                calls += RawReadCallDiagnostic(
                    index = totalCalls,
                    requestedBytes = readBuffer.size,
                    returnedBytes = count,
                    elapsedMicros = readElapsedMicros,
                    firstBytes = returned.copyOf(min(FIRST_BYTES_PER_CALL, returned.size)),
                    identicalToPrevious = identical,
                )
            }
            previousRead = returned
            if (totalCalls <= MAX_RECORDED_CALLS) {
                diagnostics(
                    "Passive read #$totalCalls requested ${readBuffer.size}, returned $count in $readElapsedMicros us"
                )
            }
            if (count == 0) continue
            val copyCount = min(count, maxBytes - size)
            returned.copyInto(captured, destinationOffset = size, endIndex = copyCount)
            size += copyCount
        }

        val durationMicros = (System.nanoTime() - started) / NANOS_PER_MICROSECOND
        val bytes = captured.copyOf(size)
        val analyzer = PassiveProtocolAnalyzer().apply { feed(bytes.copyOf()) }
        val readDiagnostics = RawReadDiagnostics(
            totalCalls = totalCalls,
            recordedCalls = calls,
            totalBytesReported = totalReported,
            totalBytesAppended = size,
            identicalConsecutiveReads = identicalConsecutive,
            sentinelMatchingPositions = sentinelMatches,
            fullyUnchangedSentinelReads = fullyUnchangedSentinelReads,
            theoreticalFreshUartBytes = baudRate / BITS_PER_UART_BYTE.toDouble() * durationMicros / MICROSECONDS_PER_SECOND,
        )
        val result = RawSerialCaptureResult(
            baudRate = baudRate,
            bufferMode = bufferMode,
            bytes = bytes,
            durationMicros = durationMicros,
            limitReached = size == maxBytes,
            analysis = analyzer.snapshot(),
            readDiagnostics = readDiagnostics,
            usbDiagnostic = usbDiagnostic,
        )
        diagnostics(
            "Passive capture completed: $size appended from $totalReported reported bytes in $totalCalls reads, " +
                "$durationMicros us"
        )
        return result
    }

    private fun freshSentinelBuffer(size: Int): ByteArray = ByteArray(size) { sentinelByte(it) }

    private fun sentinelByte(index: Int): Byte = if (index % 2 == 0) 0xAA.toByte() else 0x55

    companion object {
        const val MAX_BYTES = 4 * 1024
        const val CAPTURE_DURATION_MS = 3_000
        private const val READ_BUFFER_SIZE = 512
        private const val READ_TIMEOUT_MS = 100
        private const val FIRST_BYTES_PER_CALL = 32
        private const val MAX_RECORDED_CALLS = 40
        private const val BITS_PER_UART_BYTE = 10
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val MICROSECONDS_PER_SECOND = 1_000_000.0
    }
}

private fun ByteArray.toHex(separator: String): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
