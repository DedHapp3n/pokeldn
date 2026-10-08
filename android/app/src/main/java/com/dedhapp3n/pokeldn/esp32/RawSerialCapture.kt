package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import kotlin.math.max
import kotlin.math.min

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
    val bytes: ByteArray,
    val durationMs: Long,
    val limitReached: Boolean,
    val analysis: PassiveProtocolAnalysis,
) {
    val bytesPerSecond: Double
        get() = if (durationMs > 0) bytes.size * 1_000.0 / durationMs else 0.0

    fun displayText(): String = buildString {
        appendLine("Mode: Passive (no data sent)")
        appendLine("Baud: $baudRate")
        appendLine("Bytes: ${bytes.size}${if (limitReached) " (capture limit reached)" else ""}")
        appendLine("Duration: $durationMs ms")
        appendLine("Rate: %.1f bytes/s".format(bytesPerSecond))
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
        appendLine("Offset  Hex                                               ASCII")
        for (offset in bytes.indices step BYTES_PER_LINE) {
            val end = min(offset + BYTES_PER_LINE, bytes.size)
            val row = bytes.copyOfRange(offset, end)
            val hex = row.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
            val ascii = row.joinToString("") {
                val value = it.toInt() and 0xFF
                if (value in 0x20..0x7E) value.toChar().toString() else "."
            }
            append("%04X    %-47s  |%s|\n".format(offset, hex, ascii))
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
    fun capture(baudRate: Int): RawSerialCaptureResult {
        val started = System.nanoTime()
        diagnostics("Passive raw capture started at $baudRate baud; no data will be sent")
        val captured = ByteArray(maxBytes)
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        var size = 0
        val deadline = started + captureDurationMs * NANOS_PER_MILLISECOND
        while (size < maxBytes && System.nanoTime() < deadline) {
            val remainingMs = max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
            val timeoutMs = min(READ_TIMEOUT_MS, remainingMs.toInt())
            val count = serial.read(readBuffer, timeoutMs)
            diagnostics("Passive raw capture read returned $count bytes")
            if (count <= 0) continue
            val copyCount = min(count, maxBytes - size)
            readBuffer.copyInto(captured, destinationOffset = size, endIndex = copyCount)
            size += copyCount
        }

        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
        val bytes = captured.copyOf(size)
        val analyzer = PassiveProtocolAnalyzer().apply { feed(bytes.copyOf()) }
        val result = RawSerialCaptureResult(
            baudRate = baudRate,
            bytes = bytes,
            durationMs = elapsedMs,
            limitReached = size == maxBytes,
            analysis = analyzer.snapshot(),
        )
        diagnostics(
            "Passive raw capture completed at $baudRate: ${result.bytes.size} bytes, " +
                "${result.analysis.validCrcFrames} valid frames, ${result.analysis.malformedCandidates} malformed, " +
                "$elapsedMs ms"
        )
        return result
    }

    companion object {
        const val MAX_BYTES = 4 * 1024
        const val CAPTURE_DURATION_MS = 3_000
        private const val READ_BUFFER_SIZE = 512
        private const val READ_TIMEOUT_MS = 100
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
