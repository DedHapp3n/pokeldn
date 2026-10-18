package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.ldn.LdnAccessPointBuilder
import com.dedhapp3n.pokeldn.ldn.LdnAccessPointConfiguration
import com.dedhapp3n.pokeldn.ldn.LdnAuthenticationHost
import com.dedhapp3n.pokeldn.ldn.LdnHostIdentity
import com.dedhapp3n.pokeldn.ldn.LdnHostParameters
import com.dedhapp3n.pokeldn.ldn.LdnProdKeys
import com.dedhapp3n.pokeldn.ldn.LdnRandomSource
import com.dedhapp3n.pokeldn.ldn.SecureLdnRandomSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Legends: Z-A values from upstream za/session.py; independent of FRLG discovery. */
internal object ZaNetworkParameters {
    const val LDN_PROTOCOL = 1
    const val COMM_ID = 0x0100F43008C44000L
    const val SCENE_ID = 1
    const val APP_VERSION = 6
    const val MAX_PARTICIPANTS = 2
    const val SYS_COMM_VERSION = 22
    const val APP_COMM_VERSION = 6
    const val PIA_VERSION = 16
    const val PIA_PORT = 12345
    val PASSPHRASE = "BM7cXkadR9ugiXdHiurkiyhrQwcR3rMgCM5BF47dranKXWAGpGEA9z3ncXRnPjCX".toByteArray(Charsets.US_ASCII)
    val GAME_KEY = "p3bwdaSsywFXUkDu".toByteArray(Charsets.US_ASCII)
}

/** The 0x5c Pia system property and 20 game bytes from za.build_advertise_data. */
internal object ZaAdvertisementBuilder {
    fun linkCodeMask(): ByteArray {
        val key = ZaNetworkParameters.GAME_KEY
        val nonce = byteArrayOf(key[1], key[8], key[7], key[2])
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ByteArray(16)).copyOfRange(0, 16)
        }
    }

    fun buildApplicationData(code: String, numPlayers: Int = 1): ByteArray {
        require(code.length == 8 && code.all(Char::isDigit)) { "Z-A Link Code must be eight digits" }
        require(numPlayers in 1..2)
        val codeBytes = code.toByteArray(Charsets.US_ASCII)
        val system = ByteArray(0x5c)
        system[0] = 0; system[1] = 0x5c
        system[2] = ZaNetworkParameters.SYS_COMM_VERSION.toByte()
        system[3] = 0; system[4] = ZaNetworkParameters.APP_COMM_VERSION.toByte()
        val mask = linkCodeMask()
        repeat(16) { system[5 + it] = (mask[it].toInt() xor if (it < 8) codeBytes[it].toInt() else 0).toByte() }
        system[0x15] = 1 // player limit enabled
        system[0x16] = numPlayers.toByte()
        system[0x1a] = 1 // UTF-8 name: a single space
        system[0x1b] = 1
        system[0x1c] = ' '.code.toByte()
        val game = ByteArray(20)
        codeBytes.copyInto(game)
        game[16] = 8
        return system + game
    }
}

internal data class ZaLdnNetwork(
    val accessPoint: LdnAccessPointConfiguration,
    val authentication: LdnAuthenticationHost,
    val applicationData: ByteArray,
)

internal class ZaLdnNetworkBuilder(private val random: LdnRandomSource = SecureLdnRandomSource()) {
    fun build(keys: LdnProdKeys, code: String): ZaLdnNetwork {
        val ssid = random.nextBytes(16)
        val serverRandom = random.nextBytes(16)
        val mac = random.nextBytes(6).also { it[0] = ((it[0].toInt() and 0xfc) or 2).toByte() }
        val nonce = ByteBuffer.wrap(random.nextBytes(4)).order(ByteOrder.BIG_ENDIAN).int
        val challenge = ByteBuffer.wrap(random.nextBytes(8)).order(ByteOrder.BIG_ENDIAN).long
        val networkNumber = (random.nextBytes(1)[0].toInt() and 0x7f).coerceAtLeast(1)
        val deviceId = ByteBuffer.wrap(random.nextBytes(8)).order(ByteOrder.BIG_ENDIAN).long
        val appData = ZaAdvertisementBuilder.buildApplicationData(code)
        val accessPoint = LdnAccessPointBuilder(random).build(
            keys, ZaNetworkParameters.LDN_PROTOCOL, 6, mac, ssid, serverRandom,
            ZaNetworkParameters.PASSPHRASE, ZaNetworkParameters.MAX_PARTICIPANTS,
        )
        val identity = LdnHostIdentity(mac, ssid, serverRandom, nonce, challenge, networkNumber, deviceId, appData)
        val parameters = LdnHostParameters(
            protocol = ZaNetworkParameters.LDN_PROTOCOL,
            communicationId = ZaNetworkParameters.COMM_ID,
            sceneId = ZaNetworkParameters.SCENE_ID,
            appVersion = ZaNetworkParameters.APP_VERSION,
            maxParticipants = ZaNetworkParameters.MAX_PARTICIPANTS,
            channel = 6,
            hostName = " ".toByteArray(Charsets.UTF_8),
        )
        return ZaLdnNetwork(accessPoint, LdnAuthenticationHost(keys, identity, parameters), appData)
    }
}
