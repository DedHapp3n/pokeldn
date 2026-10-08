package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import kotlin.math.max
import kotlin.math.min

enum class RawCaptureMode(val label: String) {
    IDLE("Idle serial (no data sent)"),
    ONE_HELLO("After one upstream HELLO"),
}

data class RawSerialCaptureResult(
    val mode: RawCaptureMode,
    val bytes: ByteArray,
    val durationMs: Long,
    val limitReached: Boolean,
) {
    val delimiterCount: Int
        get() = bytes.count { it.toInt() == 0 }

    fun displayText(): String = buildString {
        appendLine("Mode: ${mode.label}")
        appendLine("Bytes: ${bytes.size}${if (limitReached) " (capture limit reached)" else ""}")
        appendLine("Duration: $durationMs ms")
        appendLine("0x00 delimiters: $delimiterCount")
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
    fun capture(mode: RawCaptureMode): RawSerialCaptureResult {
        val started = System.nanoTime()
        diagnostics("Raw capture started: ${mode.label}")
        if (mode == RawCaptureMode.ONE_HELLO) {
            val hello = Esp32Protocol.helloFrame
            diagnostics("Raw capture writing one HELLO (${hello.size} bytes)")
            serial.write(hello, WRITE_TIMEOUT_MS)
        }

        val captured = ByteArray(maxBytes)
        val readBuffer = ByteArray(READ_BUFFER_SIZE)
        var size = 0
        val deadline = started + captureDurationMs * NANOS_PER_MILLISECOND
        while (size < maxBytes && System.nanoTime() < deadline) {
            val remainingMs = max(1, (deadline - System.nanoTime()) / NANOS_PER_MILLISECOND)
            val timeoutMs = min(READ_TIMEOUT_MS, remainingMs.toInt())
            val count = serial.read(readBuffer, timeoutMs)
            diagnostics("Raw capture read returned $count bytes")
            if (count <= 0) continue
            val copyCount = min(count, maxBytes - size)
            readBuffer.copyInto(captured, destinationOffset = size, endIndex = copyCount)
            size += copyCount
        }

        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND
        val result = RawSerialCaptureResult(mode, captured.copyOf(size), elapsedMs, size == maxBytes)
        diagnostics(
            "Raw capture completed: ${result.bytes.size} bytes, ${result.delimiterCount} delimiters, ${elapsedMs} ms"
        )
        return result
    }

    companion object {
        const val MAX_BYTES = 4 * 1024
        const val CAPTURE_DURATION_MS = 3_000
        private const val READ_BUFFER_SIZE = 512
        private const val READ_TIMEOUT_MS = 100
        private const val WRITE_TIMEOUT_MS = 1_000
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
