package com.dedhapp3n.pokeldn.ldn

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class LdnParticipant(
    val index: Int,
    val ipAddress: String,
    val mac: ByteArray,
    val name: ByteArray,
    val appVersion: Int,
    val platform: Int,
)

internal data class LdnHostIdentity(
    val bssid: ByteArray,
    val ssid: ByteArray,
    val serverRandom: ByteArray,
    val advertisementNonce: Int,
    val challengeToken: Long,
    val networkNumber: Int,
    val deviceId: Long,
    val applicationData: ByteArray,
)

internal data class LdnAdvertisedParticipant(
    val index: Int,
    val ipAddress: ByteArray,
    val mac: ByteArray,
    val platform: Int,
    val name: ByteArray,
)

internal data class LdnAuthenticationOutcome(
    val response: ByteArray,
    val statusCode: Int,
    val requestParsed: Boolean,
    val participant: LdnParticipant?,
)

/** Host-side Nintendo LDN authentication and participant table, without Pia or IP traffic. */
internal class LdnAuthenticationHost(
    private val keys: LdnProdKeys,
    private val identity: LdnHostIdentity,
) {
    private val lock = Any()
    private val participants = linkedMapOf<String, LdnParticipant>()
    private var nonce = identity.advertisementNonce
    private var advertisement = encodeAdvertisement()

    fun currentAdvertisementFrame(): ByteArray = synchronized(lock) { advertisement.copyOf() }

    fun process(source: ByteArray, payload: ByteArray): LdnAuthenticationOutcome = synchronized(lock) {
        val request = try {
            LdnAuthenticationCodec.decodeRequest(keys, payload)
        } catch (_: Exception) {
            return@synchronized LdnAuthenticationOutcome(
                response = LdnAuthenticationCodec.encodeResponse(
                    keys = keys,
                    identity = identity,
                    statusCode = AUTH_MALFORMED_REQUEST,
                    clientRandom = ByteArray(16),
                    challenge = byteArrayOf(),
                ),
                statusCode = AUTH_MALFORMED_REQUEST,
                requestParsed = false,
                participant = null,
            )
        }

        var status = validate(request)
        var challengeResponse = byteArrayOf()
        var participant: LdnParticipant? = null
        if (status == AUTH_SUCCESS) {
            val challenge = try {
                LdnAuthenticationCodec.answerChallenge(
                    request.challenge,
                    identity.challengeToken,
                    identity.deviceId,
                )
            } catch (_: Exception) {
                null
            }
            if (challenge == null) {
                status = AUTH_CHALLENGE_FAILURE
            } else {
                challengeResponse = challenge
                participant = register(source, request)
                if (participant == null) status = AUTH_DENIED_BY_POLICY
            }
        }
        LdnAuthenticationOutcome(
            response = LdnAuthenticationCodec.encodeResponse(
                keys = keys,
                identity = identity,
                statusCode = status,
                clientRandom = request.clientRandom,
                challenge = if (status == AUTH_SUCCESS) challengeResponse else byteArrayOf(),
            ),
            statusCode = status,
            requestParsed = true,
            participant = participant,
        )
    }

    fun removeParticipant(mac: ByteArray): LdnParticipant? = synchronized(lock) {
        val removed = participants.remove(mac.key()) ?: return@synchronized null
        incrementAdvertisementNonce()
        advertisement = encodeAdvertisement()
        removed
    }

    private fun validate(request: LdnAuthenticationRequest): Int = when {
        request.version !in 2..4 -> AUTH_INVALID_VERSION
        request.statusCode != 0 || request.isResponse -> AUTH_MALFORMED_REQUEST
        request.localCommunicationId != LdnAdvertisementBuilder.LOCAL_COMMUNICATION_ID -> AUTH_MALFORMED_REQUEST
        request.sceneId != LdnAdvertisementBuilder.SCENE_ID -> AUTH_MALFORMED_REQUEST
        !request.ssid.contentEquals(identity.ssid) -> AUTH_MALFORMED_REQUEST
        !request.serverRandom.contentEquals(identity.serverRandom) -> AUTH_MALFORMED_REQUEST
        else -> AUTH_SUCCESS
    }

    private fun register(source: ByteArray, request: LdnAuthenticationRequest): LdnParticipant? {
        require(source.size == 6) { "LDN participant MAC must contain six bytes" }
        participants[source.key()]?.let { return it }
        val used = participants.values.mapTo(mutableSetOf()) { it.index }
        val index = (1 until LdnAdvertisementBuilder.MAX_PARTICIPANTS).firstOrNull { it !in used }
            ?: return null
        val participant = LdnParticipant(
            index = index,
            ipAddress = "169.254.${identity.networkNumber}.${index + 1}",
            mac = source.copyOf(),
            name = request.username.copyOf(),
            appVersion = request.appVersion,
            platform = request.platform,
        )
        participants[source.key()] = participant
        incrementAdvertisementNonce()
        advertisement = encodeAdvertisement()
        return participant
    }

    private fun incrementAdvertisementNonce() {
        nonce += 1
    }

    private fun encodeAdvertisement(): ByteArray {
        val advertised = mutableListOf(
            LdnAdvertisedParticipant(
                index = 0,
                ipAddress = byteArrayOf(169.toByte(), 254.toByte(), identity.networkNumber.toByte(), 1),
                mac = identity.bssid,
                platform = PLATFORM_NX,
                name = HOST_NAME,
            )
        )
        participants.values.sortedBy(LdnParticipant::index).forEach { participant ->
            advertised += LdnAdvertisedParticipant(
                index = participant.index,
                ipAddress = participant.ipAddress.split('.').map(String::toInt).map(Int::toByte).toByteArray(),
                mac = participant.mac,
                platform = participant.platform,
                name = participant.name,
            )
        }
        return LdnAdvertisementBuilder.encodeActionFrame(keys, identity, nonce, advertised)
    }

    private fun ByteArray.key(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val AUTH_SUCCESS = 0
        private const val AUTH_DENIED_BY_POLICY = 1
        private const val AUTH_MALFORMED_REQUEST = 2
        private const val AUTH_INVALID_VERSION = 4
        private const val AUTH_CHALLENGE_FAILURE = 6
        private const val PLATFORM_NX = 0
        private val HOST_NAME = "POKELDN".toByteArray(Charsets.UTF_8)
    }
}

internal data class LdnAuthenticationRequest(
    val version: Int,
    val statusCode: Int,
    val isResponse: Boolean,
    val localCommunicationId: Long,
    val sceneId: Int,
    val ssid: ByteArray,
    val serverRandom: ByteArray,
    val clientRandom: ByteArray,
    val username: ByteArray,
    val appVersion: Int,
    val platform: Int,
    val challenge: ByteArray,
)

internal object LdnAuthenticationCodec {
    fun decodeRequest(keys: LdnProdKeys, data: ByteArray): LdnAuthenticationRequest {
        require(data.size >= OUTER_HEADER_SIZE + AUTH_HEADER_SIZE + GCM_TAG_SIZE) {
            "Authentication frame is too short"
        }
        require(data.copyOfRange(0, 3).contentEquals(NINTENDO_OUI)) { "Wrong authentication OUI" }
        require(uint16BigEndian(data, 3) == AUTHENTICATION_FRAME_TYPE) {
            "Not an authentication frame"
        }
        val headerStart = OUTER_HEADER_SIZE
        val header = data.copyOfRange(headerStart, headerStart + AUTH_HEADER_SIZE)
        val version = header[0].toInt() and 0xff
        val payloadSize = (header[1].toInt() and 0xff) or ((header[4].toInt() and 0xff) shl 8)
        val statusCode = header[2].toInt() and 0xff
        val isResponse = header[3].toInt() != 0
        require((header[5].toInt() and 0xff) == AUTH_FORMAT_AES_GCM) {
            "Wrong authentication format"
        }
        val expectedSize = OUTER_HEADER_SIZE + AUTH_HEADER_SIZE + GCM_TAG_SIZE + payloadSize
        require(data.size == expectedSize) { "Authentication frame has wrong size" }
        val network = header.copyOfRange(8, 40)
        val localCommunicationId = ByteBuffer.wrap(network, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val sceneId = uint16LittleEndian(network, 10)
        val ssid = network.copyOfRange(16, 32)
        val serverRandom = header.copyOfRange(40, 56)
        val clientRandom = header.copyOfRange(56, 72)
        val tagOffset = OUTER_HEADER_SIZE + AUTH_HEADER_SIZE
        val tag = data.copyOfRange(tagOffset, tagOffset + GCM_TAG_SIZE)
        val ciphertext = data.copyOfRange(tagOffset + GCM_TAG_SIZE, data.size)
        val key = LdnKeyDerivation(keys, LdnAdvertisementBuilder.LDN_PROTOCOL)
            .deriveAuthenticationKey(clientRandom)
        val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(0, 12)),
            )
            updateAAD(header)
            doFinal(ciphertext + tag)
        }
        require(plaintext.size >= REQUEST_BASE_SIZE) { "Authentication request payload is too short" }
        val username = plaintext.copyOfRange(0, 32).dropLastWhile { it == 0.toByte() }.toByteArray()
        val appVersion = uint16BigEndian(plaintext, 32)
        val platform = plaintext[34].toInt() and 0xff
        val challenge = if (version >= 3) {
            require(plaintext.size >= REQUEST_CHALLENGE_OFFSET) { "Authentication challenge is missing" }
            plaintext.copyOfRange(REQUEST_CHALLENGE_OFFSET, plaintext.size)
        } else byteArrayOf()
        return LdnAuthenticationRequest(
            version,
            statusCode,
            isResponse,
            localCommunicationId,
            sceneId,
            ssid,
            serverRandom,
            clientRandom,
            username,
            appVersion,
            platform,
            challenge,
        )
    }

    fun answerChallenge(request: ByteArray, token: Long, hostDeviceId: Long): ByteArray {
        require(request.size == CHALLENGE_REQUEST_SIZE) { "Challenge request has wrong size" }
        val mac = request.copyOfRange(4, 36)
        val body = request.copyOfRange(48, request.size)
        require(MessageDigest.isEqual(mac, hmac(body))) { "Challenge request has wrong HMAC" }
        require((body[2].toInt() and 0xff) <= 8 && (body[3].toInt() and 0xff) <= 64) {
            "Challenge parameter count is invalid"
        }
        val requestToken = ByteBuffer.wrap(body, 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        require(requestToken == token) { "Challenge token does not match the advertisement" }
        val nonce = ByteBuffer.wrap(body, 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val deviceId = ByteBuffer.wrap(body, 24, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val unknown = body.copyOfRange(32, 48)
        val responseBody = ByteArrayOutputStream().apply {
            write(ByteArray(4))
            writeU32LittleEndian(2)
            writeU64LittleEndian(nonce)
            writeU64LittleEndian(deviceId)
            writeU64LittleEndian(hostDeviceId)
            write(unknown)
            write(ByteArray(16))
            write(ByteArray(0x90))
        }.toByteArray()
        check(responseBody.size == CHALLENGE_RESPONSE_BODY_SIZE)
        return ByteArrayOutputStream().apply {
            write(ByteArray(4))
            write(hmac(responseBody))
            write(ByteArray(12))
            write(responseBody)
        }.toByteArray()
    }

    fun encodeResponse(
        keys: LdnProdKeys,
        identity: LdnHostIdentity,
        statusCode: Int,
        clientRandom: ByteArray,
        challenge: ByteArray,
    ): ByteArray {
        require(clientRandom.size == 16)
        val payload = ByteArrayOutputStream().apply {
            write(PLATFORM_NX)
            write(ByteArray(0x83))
            write(challenge)
        }.toByteArray()
        val networkId = ByteArrayOutputStream().apply {
            writeU64LittleEndian(LdnAdvertisementBuilder.LOCAL_COMMUNICATION_ID)
            write(ByteArray(2))
            writeU16LittleEndian(LdnAdvertisementBuilder.SCENE_ID)
            write(ByteArray(4))
            write(identity.ssid)
        }.toByteArray()
        val header = ByteArrayOutputStream().apply {
            write(NETWORK_VERSION)
            write(payload.size and 0xff)
            write(statusCode)
            write(1)
            write(payload.size ushr 8)
            write(AUTH_FORMAT_AES_GCM)
            write(ByteArray(2))
            write(networkId)
            write(identity.serverRandom)
            write(clientRandom)
        }.toByteArray()
        val key = LdnKeyDerivation(keys, LdnAdvertisementBuilder.LDN_PROTOCOL)
            .deriveAuthenticationKey(clientRandom)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, header.copyOfRange(0, 12)),
            )
            updateAAD(header)
            doFinal(payload)
        }
        val ciphertextSize = encrypted.size - GCM_TAG_SIZE
        return NINTENDO_OUI + byteArrayOf(1, 2, 0) + header +
            encrypted.copyOfRange(ciphertextSize, encrypted.size) +
            encrypted.copyOfRange(0, ciphertextSize)
    }

    private fun hmac(body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(CHALLENGE_KEY, "HmacSHA256"))
        doFinal(body)
    }

    private fun uint16BigEndian(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun uint16LittleEndian(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArrayOutputStream.writeU16LittleEndian(value: Int) {
        write(value and 0xff)
        write(value ushr 8)
    }

    private fun ByteArrayOutputStream.writeU32LittleEndian(value: Int) {
        repeat(4) { shift -> write(value ushr (shift * 8)) }
    }

    private fun ByteArrayOutputStream.writeU64LittleEndian(value: Long) {
        repeat(8) { shift -> write((value ushr (shift * 8)).toInt()) }
    }

    private const val AUTHENTICATION_FRAME_TYPE = 0x0102
    private const val AUTH_FORMAT_AES_GCM = 1
    private const val NETWORK_VERSION = 4
    private const val PLATFORM_NX = 0
    private const val OUTER_HEADER_SIZE = 6
    private const val AUTH_HEADER_SIZE = 0x48
    private const val GCM_TAG_SIZE = 16
    private const val REQUEST_BASE_SIZE = 64
    private const val REQUEST_CHALLENGE_OFFSET = 100
    private const val CHALLENGE_REQUEST_SIZE = 0x300
    private const val CHALLENGE_RESPONSE_BODY_SIZE = 0xd0
    private val NINTENDO_OUI = byteArrayOf(0x00, 0x22, 0xaa.toByte())
    private val CHALLENGE_KEY = (
        "f84b487fb37251c263bf11609036589266af70ca79b44c93c7370c5769c0f602"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
