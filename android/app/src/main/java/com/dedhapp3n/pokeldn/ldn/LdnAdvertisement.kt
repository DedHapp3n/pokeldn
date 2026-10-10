package com.dedhapp3n.pokeldn.ldn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LdnDiscoveryNetwork internal constructor(
    val accessPoint: LdnAccessPointConfiguration,
    val advertisementFrame: ByteArray,
    val localCommunicationId: Long,
    val sceneId: Int,
    internal val authenticationHost: LdnAuthenticationHost,
    internal val piaHost: LdnPiaHost,
    internal val parentSessionId: ByteArray,
) {
    init {
        require(accessPoint.bssid.contentEquals(authenticationHost.hostMac))
        require(accessPoint.bssid.contentEquals(piaHost.hostMac))
        require(authenticationHost.networkNumber == piaHost.networkNumber)
        require(authenticationHost.hostIp == piaHost.hostIp)
    }
}

/** Minimum protocol-3 host advertisement used by upstream's FRLG discovery-only probe. */
class LdnAdvertisementBuilder internal constructor(
    private val random: LdnRandomSource = SecureLdnRandomSource(),
) {
    fun buildDiscoveryNetwork(keys: LdnProdKeys): LdnDiscoveryNetwork {
        val ssid = random.nextBytes(16)
        val serverRandom = random.nextBytes(16)
        val bssid = random.nextBytes(6).also {
            it[0] = ((it[0].toInt() and 0xfc) or 0x02).toByte()
        }
        val nonce = random.nextBytes(4)
        val challenge = random.nextBytes(8)
        val networkNumber = ((random.nextBytes(1)[0].toInt() and 0x7f).coerceAtLeast(1))
        val parentSessionId = byteArrayOf(random.nextBytes(1)[0], 0xf1.toByte())
        val deviceId = ByteBuffer.wrap(random.nextBytes(8)).order(ByteOrder.BIG_ENDIAN).long
        val accessPoint = LdnAccessPointBuilder(random).build(
            keys = keys,
            protocol = LDN_PROTOCOL,
            channel = CHANNEL,
            bssid = bssid,
            ssid = ssid,
            serverRandom = serverRandom,
            password = GBA_APP_PASSPHRASE,
            maxParticipants = MAX_PARTICIPANTS,
        )
        val applicationData = buildDiscoveryApplicationData(parentSessionId)
        val identity = LdnHostIdentity(
            bssid = bssid,
            ssid = ssid,
            serverRandom = serverRandom,
            advertisementNonce = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN).int,
            challengeToken = ByteBuffer.wrap(challenge).order(ByteOrder.BIG_ENDIAN).long,
            networkNumber = networkNumber,
            deviceId = deviceId,
            applicationData = applicationData,
        )
        val authenticationHost = LdnAuthenticationHost(keys, identity)
        val piaHost = LdnPiaHost(
            ssid = ssid,
            hostMac = bssid,
            networkNumber = networkNumber,
            maxParticipants = MAX_PARTICIPANTS,
        )
        return LdnDiscoveryNetwork(
            accessPoint = accessPoint,
            advertisementFrame = authenticationHost.currentAdvertisementFrame(),
            localCommunicationId = LOCAL_COMMUNICATION_ID,
            sceneId = SCENE_ID,
            authenticationHost = authenticationHost,
            piaHost = piaHost,
            parentSessionId = parentSessionId,
        )
    }

    companion object {
        const val CHANNEL = 6
        const val MAX_PARTICIPANTS = 6
        const val LOCAL_COMMUNICATION_ID = 0x01006fa0233f8000L
        const val SCENE_ID = 22287
        const val APP_VERSION = 88
        const val ADVERTISEMENT_INTERVAL_MS = 100L
        internal const val LDN_PROTOCOL = 3
        private const val NETWORK_VERSION = 4
        private const val ADVERTISE_FORMAT_AES_GCM = 3
        private const val SECURITY_MODE_PROD = 1
        private const val ACCEPT_ALL = 0
        private const val PLATFORM_NX = 0
        private const val BAND_2_4_GHZ = 2
        private const val GCM_TAG_SIZE = 16
        private val HOST_NAME = "POKELDN".toByteArray(Charsets.UTF_8)
        private val ADVERTISEMENT_PREFIX = "7f0022aa0400010100000000".hexToBytes()
        private val GBA_APP_PASSPHRASE = (
            "fcb6f6adb9dfea66aca9c326149d2b3b08a781895cbf78f720d78b85a57584a9" +
                "9665d237797b2a41ddef14063ec28d259143af7832fb3cbcf2759cbfbdc81d8c"
            ).hexToBytes()
        private val CAPTURED_TRADE_APPLICATION_DATA = (
            "005c16005800000000000000000000000000000000010100000005014368617365000000000000" +
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000686c5a68656c76623476354358455a232323232368642323232323232323"
            ).hexToBytes()
        private val DEFAULT_DISCOVERY_RECORD =
            "2288cac9c5bfc6bec8ff0000000000009515000000000000".hexToBytes()

        internal fun encodeActionFrame(
            keys: LdnProdKeys,
            identity: LdnHostIdentity,
            nonce: Int,
            participants: List<LdnAdvertisedParticipant>,
        ): ByteArray {
            val networkId = ByteArrayOutputStream().apply {
                writeU64(LOCAL_COMMUNICATION_ID)
                write(ByteArray(2))
                writeU16(SCENE_ID)
                write(ByteArray(4))
                write(identity.ssid)
            }.toByteArray()
            val plaintext = ByteArrayOutputStream().apply {
                write(identity.serverRandom)
                write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(identity.challengeToken).array())
                write(SECURITY_MODE_PROD)
                write(ACCEPT_ALL)
                writeU16(APP_VERSION)
                write(ByteArray(8))
                writeU16((BAND_2_4_GHZ shl 10) or CHANNEL)
                write(MAX_PARTICIPANTS)
                write(participants.size)
                participants.forEach { participant ->
                    require(participant.ipAddress.size == 4 && participant.mac.size == 6)
                    require(participant.name.size <= 32)
                    write(participant.ipAddress)
                    write(participant.mac)
                    write(participant.index)
                    write(participant.platform)
                    write(participant.name)
                    write(ByteArray(32 - participant.name.size))
                    write(ByteArray(4))
                }
                writeU16(identity.applicationData.size)
                write(identity.applicationData)
            }.toByteArray()
            val nonceBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(nonce).array()
            val authenticatedHeader = ByteArrayOutputStream().apply {
                write(networkId)
                write(NETWORK_VERSION)
                write(ADVERTISE_FORMAT_AES_GCM)
                writeU16(plaintext.size)
                write(nonceBytes)
            }.toByteArray()
            val key = LdnKeyDerivation(keys, LDN_PROTOCOL).deriveAdvertiseKey(networkId)
            val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, nonceBytes + ByteArray(8)),
                )
                updateAAD(authenticatedHeader)
                doFinal(plaintext)
            }
            val ciphertextSize = encrypted.size - GCM_TAG_SIZE
            val advertisement = ADVERTISEMENT_PREFIX + authenticatedHeader +
                encrypted.copyOfRange(ciphertextSize, encrypted.size) +
                encrypted.copyOfRange(0, ciphertextSize)
            return wrapActionFrame(identity.bssid, advertisement)
        }

        internal fun buildDiscoveryApplicationData(parentSessionId: ByteArray): ByteArray {
            require(parentSessionId.size == 2) { "FRLG discovery parent id must be two bytes" }
            val header = CAPTURED_TRADE_APPLICATION_DATA.copyOfRange(0, 0x5c)
            header[0x17] = 0
            header[0x18] = 0
            header[0x19] = 0
            header[0x1a] = HOST_NAME.size.toByte()
            header[0x1b] = 1
            header.fill(0, 0x1c, 0x5c)
            HOST_NAME.copyInto(header, 0x1c)
            val record = DEFAULT_DISCOVERY_RECORD.copyOf()
            parentSessionId.copyInto(record, 10)
            return header + base85Encode(record)
        }

        private fun wrapActionFrame(source: ByteArray, advertisement: ByteArray): ByteArray =
            byteArrayOf(0xd0.toByte(), 0, 0, 0) + ByteArray(6) { 0xff.toByte() } + source +
                ByteArray(6) { 0xff.toByte() } + byteArrayOf(0, 0) + advertisement

        private fun base85Encode(input: ByteArray): ByteArray {
            val data = if (input.size % 4 == 0) input else input.copyOf(input.size + 4 - input.size % 4)
            val output = ByteArrayOutputStream()
            for (offset in data.indices step 4) {
                var value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                repeat(5) {
                    val digit = (value % 85).toInt()
                    var encoded = 0x23 + digit
                    if (encoded >= 0x5c) encoded++
                    output.write(encoded)
                    value /= 85
                }
            }
            return output.toByteArray()
        }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun ByteArrayOutputStream.writeU16(value: Int) {
            write(byteArrayOf((value ushr 8).toByte(), value.toByte()))
        }

        private fun ByteArrayOutputStream.writeU64(value: Long) {
            write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array())
        }
    }
}
