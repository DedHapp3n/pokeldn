package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import com.dedhapp3n.pokeldn.usb.SerialTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synchronous ESP32 command boundary. It must have exclusive ownership of [serial] while in use. */
class Esp32CommandClient(
    private val serial: SerialIo,
    private val onEvent: (Esp32Frame) -> Unit = {},
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
) {
    private val reader = Esp32FrameReader()

    fun setChannel(channel: Int) {
        require(channel in 1..14) { "Wi-Fi channel must be between 1 and 14" }
        requestResult(Esp32Protocol.CMD_CHANNEL, byteArrayOf(channel.toByte()), requestTimeoutMs)
    }

    fun startAccessPoint(config: Esp32AccessPointConfig) {
        requestResult(Esp32Protocol.CMD_AP_START, config.toPayload(), AP_START_TIMEOUT_MS)
    }

    fun stop() = requestResult(Esp32Protocol.CMD_STOP, timeoutMs = STOP_TIMEOUT_MS)

    fun status(): String = request(Esp32Protocol.CMD_STATUS, Esp32Protocol.MSG_STATUS, timeoutMs = requestTimeoutMs)
        .toString(Charsets.UTF_8)

    fun alive() = send(Esp32Protocol.CMD_ALIVE)

    fun sendEthernet(frame: ByteArray) {
        require(frame.size in 14..1600) { "Ethernet frame must be 14..1600 bytes" }
        send(Esp32Protocol.CMD_ETH_TX, frame)
    }

    fun changeBaud(baudRate: Int, transport: SerialTransport) {
        require(baudRate > 0) { "Baud rate must be positive" }
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(baudRate).array()
        requestResult(Esp32Protocol.CMD_BAUD, payload, requestTimeoutMs)
        transport.setBaudRate(baudRate)
    }

    private fun requestResult(command: Int, payload: ByteArray = byteArrayOf(), timeoutMs: Int = REQUEST_TIMEOUT_MS) {
        val reply = request(command, Esp32Protocol.MSG_RESULT, payload, timeoutMs)
        if (reply.size < 5 || (reply[0].toInt() and 0xff) != command) {
            throw Esp32ProtocolException("Invalid RESULT for command 0x%02X".format(command))
        }
        val code = ByteBuffer.wrap(reply, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (code != 0) throw Esp32ProtocolException(
            "ESP32 command 0x%02X failed with 0x%08X".format(command, code)
        )
    }

    private fun request(
        command: Int,
        replyType: Int,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Int = REQUEST_TIMEOUT_MS,
    ): ByteArray {
        send(command, payload)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val buffer = ByteArray(4096)
        while (System.nanoTime() < deadline) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1, 100).toInt()
            val count = serial.read(buffer, remaining)
            if (count <= 0) continue
            for (frame in reader.feed(buffer, count)) {
                if (frame.type == replyType &&
                    (replyType != Esp32Protocol.MSG_RESULT ||
                        (frame.payload.firstOrNull()?.toInt()?.and(0xff) == command))) {
                    return frame.payload
                }
                onEvent(frame)
            }
        }
        throw Esp32ProtocolException(
            "No reply 0x%02X to ESP32 command 0x%02X".format(replyType, command)
        )
    }

    private fun send(command: Int, payload: ByteArray = byteArrayOf()) {
        serial.write(Esp32Protocol.encodeFrame(command, payload), WRITE_TIMEOUT_MS)
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 3_000
        private const val AP_START_TIMEOUT_MS = 5_000
        private const val STOP_TIMEOUT_MS = 5_000
        private const val WRITE_TIMEOUT_MS = 1_000
    }
}

data class Esp32AccessPointConfig(
    val channel: Int,
    val bssid: ByteArray,
    val ssid: String,
    val key: ByteArray,
    val maxStations: Int = 7,
    val flags: Int = 0,
    val flags2: Int = 0,
    val power: Int = 0,
) {
    fun toPayload(): ByteArray {
        require(channel in 1..14) { "Wi-Fi channel must be between 1 and 14" }
        require(bssid.size == 6) { "BSSID must be six bytes" }
        val ssidBytes = ssid.toByteArray(Charsets.US_ASCII)
        require(ssidBytes.size == 32) { "LDN SSID must be 32 ASCII bytes" }
        require(key.size == 16) { "LDN key must be 16 bytes" }
        require(maxStations in 1..255 && flags in 0..255 && flags2 in 0..255 && power in 0..255)
        val tail = when {
            power != 0 -> byteArrayOf(flags2.toByte(), power.toByte())
            flags2 != 0 -> byteArrayOf(flags2.toByte())
            else -> byteArrayOf()
        }
        return byteArrayOf(channel.toByte()) + bssid + ssidBytes + key +
            byteArrayOf(maxStations.toByte(), flags.toByte()) + tail
    }
}
