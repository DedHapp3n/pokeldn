package com.dedhapp3n.pokeldn.esp32

import java.util.zip.CRC32

object Esp32Protocol {
    const val PROTOCOL_VERSION = 1
    const val CMD_HELLO = 0x01
    const val CMD_BAUD = 0x02
    const val CMD_CHANNEL = 0x03
    const val CMD_STOP = 0x05
    const val CMD_AP_START = 0x06
    const val CMD_ETH_TX = 0x08
    const val CMD_STATUS = 0x0B
    const val CMD_ALIVE = 0x0F
    const val MSG_INFO = 0x81
    const val MSG_RESULT = 0x82
    const val MSG_RX_ETH = 0x85
    const val MSG_LINK = 0x86
    const val MSG_STA_JOINED = 0x87
    const val MSG_STA_LEFT = 0x88
    const val MSG_STATUS = 0x89
    const val MSG_CREDIT = 0x8B
    const val MSG_TX_DONE = 0x8D

    val helloFrame: ByteArray
        get() = encodeFrame(CMD_HELLO)

    fun encodeFrame(type: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type in 0..255) { "Message type must fit in one byte" }
        val body = byteArrayOf(type.toByte()) + payload
        val checksum = CRC32().apply { update(body) }.value
        val framed = body + byteArrayOf(
            checksum.toByte(),
            (checksum ushr 8).toByte(),
            (checksum ushr 16).toByte(),
            (checksum ushr 24).toByte(),
        )
        return cobsEncode(framed) + byteArrayOf(0)
    }

    fun decodeFrame(encoded: ByteArray): Esp32Frame {
        val body = cobsDecode(encoded)
        if (body.size < 5) throw Esp32MalformedFrameException("Frame is too short")
        val content = body.copyOfRange(0, body.size - 4)
        val expected = (body[body.size - 4].toLong() and 0xFF) or
            ((body[body.size - 3].toLong() and 0xFF) shl 8) or
            ((body[body.size - 2].toLong() and 0xFF) shl 16) or
            ((body[body.size - 1].toLong() and 0xFF) shl 24)
        val actual = CRC32().apply { update(content) }.value
        if (actual != expected) throw Esp32ChecksumException("Frame checksum is invalid")
        return Esp32Frame(content[0].toInt() and 0xFF, content.copyOfRange(1, content.size))
    }

    fun parseInfo(payload: ByteArray): Esp32Info {
        if (payload.size < INFO_HEADER_SIZE) {
            throw Esp32IncompleteResponseException(
                "INFO payload is ${payload.size} bytes; expected at least $INFO_HEADER_SIZE"
            )
        }
        return Esp32Info(
            protocolVersion = payload[0].toInt() and 0xFF,
            stationMac = payload.copyOfRange(1, 7),
            accessPointMac = payload.copyOfRange(7, 13),
            chipRevision = payload[13].toInt() and 0xFF,
            firmwareText = payload.copyOfRange(INFO_HEADER_SIZE, payload.size).toString(Charsets.UTF_8),
        )
    }

    internal fun cobsEncode(data: ByteArray): ByteArray {
        val output = ArrayList<Byte>(data.size + 2)
        output.add(0)
        var codeIndex = 0
        var code = 1
        for (value in data) {
            if (value.toInt() == 0) {
                output[codeIndex] = code.toByte()
                codeIndex = output.size
                output.add(0)
                code = 1
            } else {
                output.add(value)
                code++
                if (code == 0xFF) {
                    output[codeIndex] = code.toByte()
                    codeIndex = output.size
                    output.add(0)
                    code = 1
                }
            }
        }
        output[codeIndex] = code.toByte()
        return output.toByteArray()
    }

    internal fun cobsDecode(data: ByteArray): ByteArray {
        val output = ArrayList<Byte>(data.size)
        var index = 0
        while (index < data.size) {
            val code = data[index].toInt() and 0xFF
            if (code == 0 || index + code > data.size) {
                throw Esp32CobsException("Invalid COBS block")
            }
            index++
            repeat(code - 1) {
                if (index >= data.size) throw Esp32CobsException("Invalid COBS block")
                output.add(data[index++])
            }
            if (code != 0xFF && index < data.size) output.add(0)
        }
        return output.toByteArray()
    }

    private const val INFO_HEADER_SIZE = 14
}

data class Esp32Frame(val type: Int, val payload: ByteArray)

data class Esp32Info(
    val protocolVersion: Int,
    val stationMac: ByteArray,
    val accessPointMac: ByteArray,
    val chipRevision: Int,
    val firmwareText: String,
) {
    val firmwareVersion: String
        get() = firmwareText.split(Regex("\\s+")).firstOrNull { it.startsWith("version=") }
            ?.removePrefix("version=").orEmpty()
}

fun ByteArray.formatMac(): String = joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

open class Esp32ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
open class Esp32MalformedFrameException(message: String) : Esp32ProtocolException(message)
class Esp32CobsException(message: String) : Esp32MalformedFrameException(message)
class Esp32ChecksumException(message: String) : Esp32MalformedFrameException(message)
class Esp32IncompleteResponseException(message: String) : Esp32ProtocolException(message)
class Esp32IncompatibleProtocolException(val info: Esp32Info) : Esp32ProtocolException(
    "Board speaks protocol ${info.protocolVersion}; app speaks ${Esp32Protocol.PROTOCOL_VERSION}"
)
class Esp32HelloTimeoutException(message: String) : Esp32ProtocolException(message)
