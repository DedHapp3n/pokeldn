package com.dedhapp3n.pokeldn.ldn

import com.dedhapp3n.pokeldn.esp32.Esp32AccessPointConfig
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class LdnAccessPointConfiguration(
    val channel: Int,
    val bssid: ByteArray,
    val ssid: ByteArray,
    val wlanKey: ByteArray,
    val maxParticipants: Int,
)

internal fun interface LdnRandomSource {
    fun nextBytes(size: Int): ByteArray
}

internal class SecureLdnRandomSource : LdnRandomSource {
    private val random = SecureRandom()
    override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
}

class LdnAccessPointBuilder internal constructor(
    private val random: LdnRandomSource = SecureLdnRandomSource(),
) {
    fun buildDiagnosticsNetwork(keys: LdnProdKeys): LdnAccessPointConfiguration {
        val ssid = random.nextBytes(BLOCK_SIZE)
        val serverRandom = random.nextBytes(BLOCK_SIZE)
        val bssid = random.nextBytes(MAC_SIZE).also { it[0] = ((it[0].toInt() and 0xfc) or 0x02).toByte() }
        return build(
            keys = keys,
            protocol = LDN_PROTOCOL_ONE,
            channel = DIAGNOSTIC_CHANNEL,
            bssid = bssid,
            ssid = ssid,
            serverRandom = serverRandom,
            password = byteArrayOf(),
            maxParticipants = MAX_PARTICIPANTS,
        )
    }

    internal fun build(
        keys: LdnProdKeys,
        protocol: Int,
        channel: Int,
        bssid: ByteArray,
        ssid: ByteArray,
        serverRandom: ByteArray,
        password: ByteArray,
        maxParticipants: Int,
    ): LdnAccessPointConfiguration {
        require(protocol == 1 || protocol == 3) { "Unsupported LDN protocol: $protocol" }
        require(channel in 1..13) { "LDN channel must be 1..13 for the ESP32" }
        require(bssid.size == MAC_SIZE) { "LDN BSSID must be six bytes" }
        require(ssid.size == BLOCK_SIZE) { "LDN SSID must be 16 bytes" }
        require(serverRandom.size == BLOCK_SIZE) { "LDN server random must be 16 bytes" }
        require(maxParticipants in 1..8) { "LDN participant limit must be 1..8" }
        val wlanKey = LdnKeyDerivation(keys, protocol).deriveDataKey(serverRandom, password)
        return LdnAccessPointConfiguration(
            channel = channel,
            bssid = bssid.copyOf(),
            ssid = ssid.copyOf(),
            wlanKey = wlanKey,
            maxParticipants = maxParticipants,
        )
    }

    companion object {
        const val DIAGNOSTIC_CHANNEL = 6
        const val MAX_PARTICIPANTS = 8
        private const val BLOCK_SIZE = 16
        private const val MAC_SIZE = 6
        private const val LDN_PROTOCOL_ONE = 1
    }
}

internal class LdnKeyDerivation(
    private val keys: LdnProdKeys,
    private val protocol: Int,
) {
    fun deriveDataKey(serverRandom: ByteArray, password: ByteArray): ByteArray =
        deriveKey(serverRandom + password, AUTHENTICATION_SOURCE)

    fun deriveAdvertiseKey(networkId: ByteArray): ByteArray =
        deriveKey(networkId, ADVERTISEMENT_SOURCE)

    private fun deriveKey(data: ByteArray, source: ByteArray): ByteArray {
        var key = when (protocol) {
            1 -> keys.value("master_key_00")
            3 -> keys.value("master_key_12")
            else -> throw IllegalArgumentException("Unsupported LDN protocol: $protocol")
        }
        key = aesDecrypt(keys.value("aes_kek_generation_source"), key)
        key = aesDecrypt(source, key)
        key = aesDecrypt(keys.value("aes_key_generation_source"), key)
        val digest = MessageDigest.getInstance("SHA-256").digest(data).copyOf(BLOCK_SIZE)
        return aesDecrypt(digest, key)
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    companion object {
        private const val BLOCK_SIZE = 16
        private val AUTHENTICATION_SOURCE = "f1e7018419a84f711da714c2cf919c9c".hexToBytes()
        private val ADVERTISEMENT_SOURCE = "191884743e24c77d87c69e4207d0c438".hexToBytes()

        private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

fun LdnAccessPointConfiguration.toEsp32AccessPointConfig(): Esp32AccessPointConfig =
    Esp32AccessPointConfig(
        channel = channel,
        bssid = bssid.copyOf(),
        ssid = ssid.joinToString("") { "%02x".format(it.toInt() and 0xff) },
        key = wlanKey.copyOf(),
        maxStations = maxParticipants,
    )
