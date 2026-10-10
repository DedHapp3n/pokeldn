package com.dedhapp3n.pokeldn.ldn

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class LdnRawDataFrame(
    val target: ByteArray,
    val source: ByteArray,
    val bssid: ByteArray,
    val toDs: Boolean,
    val fromDs: Boolean,
    val protectedFrame: Boolean,
    val qos: Boolean,
    val tid: Int,
    val packetNumber: Long,
    val keyId: Int,
    val payload: ByteArray,
) {
    fun decryptToEthernet(wlanKey: ByteArray): ByteArray {
        require(protectedFrame) { "Raw group frame is not CCMP protected" }
        require(payload.size >= CCMP_MIC_SIZE) { "Protected data frame is shorter than its CCMP MIC" }
        val plaintext = AesCcm.decryptAndVerify(
            key = wlanKey,
            nonce = byteArrayOf((if (qos) tid else 0).toByte()) + source + packetNumberBytes(),
            aad = aad(),
            ciphertext = payload.copyOfRange(0, payload.size - CCMP_MIC_SIZE),
            tag = payload.copyOfRange(payload.size - CCMP_MIC_SIZE, payload.size),
        )
        return snapToEthernet(plaintext)
    }

    /** Mirrors upstream accept_decrypted_ccmp for a driver-decrypted body with retained MIC. */
    fun acceptDecryptedCcmpToEthernet(): ByteArray? {
        if (!protectedFrame || payload.size < SNAP_HEADER_SIZE + CCMP_MIC_SIZE ||
            !payload.copyOfRange(0, 3).contentEquals(SNAP_PREFIX)) return null
        return snapToEthernet(payload.copyOfRange(0, payload.size - CCMP_MIC_SIZE))
    }

    fun plaintextToEthernet(): ByteArray {
        require(!protectedFrame) { "Protected data frame requires CCMP processing" }
        return snapToEthernet(payload)
    }

    private fun packetNumberBytes() = ByteArray(6) { index ->
        (packetNumber ushr ((5 - index) * 8)).toByte()
    }

    /** Matches vendored DataFrame._aad(): normalized flags and zero fragment/sequence number. */
    private fun aad(): ByteArray {
        var frameControl = IEEE80211_DATA shl 2
        if (qos) frameControl = frameControl or (IEEE80211_QOS_DATA shl 4)
        if (toDs) frameControl = frameControl or IEEE80211_TO_DS
        if (fromDs) frameControl = frameControl or IEEE80211_FROM_DS
        if (protectedFrame) frameControl = frameControl or IEEE80211_PROTECTED
        val output = ByteArrayOutputStream()
        output.write(frameControl and 0xff)
        output.write(frameControl ushr 8)
        output.write(target)
        output.write(source)
        output.write(bssid)
        output.write(byteArrayOf(0, 0))
        if (qos) output.write(byteArrayOf(tid.toByte(), 0))
        return output.toByteArray()
    }

    private fun snapToEthernet(plaintext: ByteArray): ByteArray {
        require(plaintext.size >= SNAP_HEADER_SIZE) { "Decrypted data frame is shorter than a SNAP header" }
        require(plaintext.copyOfRange(0, 3).contentEquals(SNAP_PREFIX)) { "SNAP extension is required" }
        val etherType = plaintext.copyOfRange(6, 8)
        return target + source + etherType + plaintext.copyOfRange(SNAP_HEADER_SIZE, plaintext.size)
    }

    companion object {
        private const val IEEE80211_DATA = 2
        private const val IEEE80211_QOS_DATA = 8
        private const val IEEE80211_TO_DS = 0x0100
        private const val IEEE80211_FROM_DS = 0x0200
        private const val IEEE80211_PROTECTED = 0x4000
        private const val CCMP_MIC_SIZE = 8
        private const val SNAP_HEADER_SIZE = 8
        private val SNAP_PREFIX = byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 0x03)

        fun decode(data: ByteArray): LdnRawDataFrame {
            require(data.size >= IEEE80211_HEADER_SIZE) { "Data frame is shorter than its MAC header" }
            val frameControl = littleU16(data, 0)
            require(frameControl and 0x03 == 0) { "Unsupported 802.11 MAC version" }
            val type = (frameControl ushr 2) and 0x03
            val subtype = (frameControl ushr 4) and 0x0f
            require(type == IEEE80211_DATA && subtype in setOf(0, IEEE80211_QOS_DATA)) {
                "Frame is not supported 802.11 data"
            }
            val qos = subtype == IEEE80211_QOS_DATA
            var offset = IEEE80211_HEADER_SIZE
            var tid = 0
            if (qos) {
                require(data.size >= offset + 2) { "QoS data frame is missing QoS control" }
                val qosControl = littleU16(data, offset)
                require(qosControl and 0x80 == 0) { "A-MSDU data frames are not supported" }
                tid = qosControl and 0x0f
                offset += 2
            }
            var protectedFrame = frameControl and IEEE80211_PROTECTED != 0
            var packetNumber = 0L
            var keyId = 0
            // Upstream handles monitor drivers which clear neither the on-wire Protected bit nor
            // the frame-control copy while removing the complete CCMP envelope.
            if (protectedFrame && data.size >= offset + 3 &&
                data.copyOfRange(offset, offset + 3).contentEquals(SNAP_PREFIX)) {
                protectedFrame = false
            }
            if (protectedFrame) {
                require(data.size >= offset + CCMP_HEADER_SIZE) { "Protected data frame is missing its CCMP header" }
                val extra = littleU16(data, offset + 2)
                require(extra and CCMP_EXTENDED_IV != 0) { "CCMP extended IV was expected" }
                packetNumber = littleU16(data, offset).toLong() or (littleU32(data, offset + 4) shl 16)
                keyId = (extra ushr 14) and 0x03
                offset += CCMP_HEADER_SIZE
            }
            return LdnRawDataFrame(
                target = data.copyOfRange(4, 10),
                source = data.copyOfRange(10, 16),
                bssid = data.copyOfRange(16, 22),
                toDs = frameControl and IEEE80211_TO_DS != 0,
                fromDs = frameControl and IEEE80211_FROM_DS != 0,
                protectedFrame = protectedFrame,
                qos = qos,
                tid = tid,
                packetNumber = packetNumber,
                keyId = keyId,
                payload = data.copyOfRange(offset, data.size),
            )
        }

        private const val IEEE80211_HEADER_SIZE = 24
        private const val CCMP_HEADER_SIZE = 8
        private const val CCMP_EXTENDED_IV = 0x2000
        private fun littleU16(data: ByteArray, offset: Int) =
            (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        private fun littleU32(data: ByteArray, offset: Int): Long =
            (0 until 4).fold(0L) { value, index ->
                value or ((data[offset + index].toLong() and 0xff) shl (index * 8))
            }
    }
}

internal class LdnRawGroupDecoder(wlanKey: ByteArray) {
    private val key = wlanKey.copyOf()

    init {
        require(key.size == 16) { "LDN WLAN key must be 16 bytes" }
    }

    fun decode(frame: ByteArray): LdnRawDecodeResult {
        val decoded = LdnRawDataFrame.decode(frame)
        if (!decoded.protectedFrame) {
            return LdnRawDecodeResult(decoded.plaintextToEthernet(), driverDecrypted = true)
        }
        decoded.acceptDecryptedCcmpToEthernet()?.let {
            return LdnRawDecodeResult(it, driverDecrypted = true)
        }
        return LdnRawDecodeResult(decoded.decryptToEthernet(key), driverDecrypted = false)
    }
}

internal data class LdnRawDecodeResult(val ethernet: ByteArray, val driverDecrypted: Boolean)

/** Minimal CCM with the L=2, 8-byte tag profile used by 802.11 CCMP. */
private object AesCcm {
    fun decryptAndVerify(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
    ): ByteArray {
        require(key.size == 16 && nonce.size == 13 && tag.size == 8)
        require(ciphertext.size <= 0xffff) { "CCMP payload is too large" }
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val plaintext = ByteArray(ciphertext.size)
        var offset = 0
        var counter = 1
        while (offset < ciphertext.size) {
            val stream = aes.doFinal(counterBlock(nonce, counter++))
            val size = minOf(16, ciphertext.size - offset)
            repeat(size) { plaintext[offset + it] = (ciphertext[offset + it].toInt() xor stream[it].toInt()).toByte() }
            offset += size
        }
        var mac = ByteArray(16)
        fun absorb(block: ByteArray) {
            mac = aes.doFinal(ByteArray(16) { (mac[it].toInt() xor block[it].toInt()).toByte() })
        }
        absorb(byteArrayOf(0x59) + nonce + u16(plaintext.size))
        val encodedAad = u16(aad.size) + aad
        blocks(encodedAad).forEach(::absorb)
        blocks(plaintext).forEach(::absorb)
        val s0 = aes.doFinal(counterBlock(nonce, 0))
        val expected = ByteArray(8) { (mac[it].toInt() xor s0[it].toInt()).toByte() }
        var difference = 0
        repeat(8) { difference = difference or (expected[it].toInt() xor tag[it].toInt()) }
        require(difference == 0) { "CCMP MIC verification failed" }
        return plaintext
    }

    private fun blocks(data: ByteArray): List<ByteArray> = if (data.isEmpty()) emptyList() else
        data.asList().chunked(16).map { chunk -> ByteArray(16) { chunk.getOrElse(it) { 0 } } }

    private fun counterBlock(nonce: ByteArray, counter: Int) =
        byteArrayOf(0x01) + nonce + u16(counter)

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
}
