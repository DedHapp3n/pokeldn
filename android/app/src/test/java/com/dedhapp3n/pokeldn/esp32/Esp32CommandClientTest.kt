package com.dedhapp3n.pokeldn.esp32

import com.dedhapp3n.pokeldn.usb.SerialIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Esp32CommandClientTest {
    @Test
    fun channelCommandMatchesUpstreamAndAcceptsItsOwnResult() {
        val serial = ReplyingSerial()
        Esp32CommandClient(serial).setChannel(11)

        val sent = Esp32Protocol.decodeFrame(serial.writes.single().dropLast(1).toByteArray())
        assertEquals(Esp32Protocol.CMD_CHANNEL, sent.type)
        assertArrayEquals(byteArrayOf(11), sent.payload)
    }

    @Test
    fun resultForAnotherCommandIsNotAccepted() {
        val serial = ReplyingSerial(replyCommand = Esp32Protocol.CMD_STOP)
        assertThrows(Esp32ProtocolException::class.java) {
            Esp32CommandClient(serial, requestTimeoutMs = 5).setChannel(11)
        }
    }

    @Test
    fun accessPointPayloadMatchesUpstreamLayout() {
        val config = Esp32AccessPointConfig(
            channel = 11,
            bssid = "021122334455".hex(),
            ssid = "0123456789abcdef0123456789abcdef",
            key = ByteArray(16) { it.toByte() },
        )
        val expected = byteArrayOf(11) + "021122334455".hex() +
            "0123456789abcdef0123456789abcdef".toByteArray() +
            ByteArray(16) { it.toByte() } + byteArrayOf(7, 0)
        assertArrayEquals(expected, config.toPayload())
    }

    private class ReplyingSerial(private val replyCommand: Int? = null) : SerialIo {
        val writes = mutableListOf<ByteArray>()
        private var pending = byteArrayOf()

        override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
            if (pending.isEmpty()) return 0
            pending.copyInto(buffer)
            return pending.size.also { pending = byteArrayOf() }
        }

        override fun write(bytes: ByteArray, timeoutMillis: Int) {
            writes += bytes.copyOf()
            val command = Esp32Protocol.decodeFrame(bytes.dropLast(1).toByteArray()).type
            val result = byteArrayOf((replyCommand ?: command).toByte()) +
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
            pending = Esp32Protocol.encodeFrame(Esp32Protocol.MSG_RESULT, result)
        }
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
