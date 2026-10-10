package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnAuthenticationTest {
    @Test
    fun parsesKnownUpstreamAuthenticationRequest() {
        val request = LdnAuthenticationCodec.decodeRequest(keys(), fixture("ldn_auth_request.hex"))

        assertEquals(4, request.version)
        assertFalse(request.isResponse)
        assertEquals(LdnAdvertisementBuilder.LOCAL_COMMUNICATION_ID, request.localCommunicationId)
        assertEquals(LdnAdvertisementBuilder.SCENE_ID, request.sceneId)
        assertEquals("Console", request.username.toString(Charsets.UTF_8))
        assertEquals(88, request.appVersion)
        assertEquals(0, request.platform)
        assertEquals(0x300, request.challenge.size)
    }

    @Test
    fun respondsWithUpstreamFixtureAndRegistersOneParticipant() {
        val network = network()
        val source = "0a0b0c0d0e0f".hex()

        val outcome = network.authenticationHost.process(source, fixture("ldn_auth_request.hex"))

        assertTrue(outcome.requestParsed)
        assertEquals(0, outcome.statusCode)
        assertArrayEquals(fixture("ldn_auth_response.hex"), outcome.response)
        val participant = outcome.participant
        assertNotNull(participant)
        participant!!
        assertEquals(1, participant.index)
        assertEquals("169.254.33.2", participant.ipAddress)
        assertArrayEquals(source, participant.mac)
        assertEquals("Console", participant.name.toString(Charsets.UTF_8))
        assertArrayEquals(
            fixture("ldn_registered_advertisement.hex"),
            network.authenticationHost.currentAdvertisementFrame(),
        )

        assertNotNull(network.authenticationHost.removeParticipant(source))
        assertNull(network.authenticationHost.removeParticipant(source))
    }

    private fun network(): LdnDiscoveryNetwork = LdnAdvertisementBuilder(
        QueueRandomSource(
            "404142434445464748494a4b4c4d4e4f".hex(),
            "505152535455565758595a5b5c5d5e5f".hex(),
            "021122334455".hex(),
            "60616263".hex(),
            "0102030405060708".hex(),
            byteArrayOf(33),
            byteArrayOf(0xb7.toByte()),
            "1112131415161718".hex(),
        )
    ).buildDiscoveryNetwork(keys())

    private fun fixture(name: String): ByteArray = checkNotNull(javaClass.getResource("/$name"))
        .readText().trim().hex()

    private fun keys() = LdnProdKeysParser.parse(KEY_TEXT.trimIndent().toByteArray())

    private class QueueRandomSource(vararg values: ByteArray) : LdnRandomSource {
        private val values = ArrayDeque(values.toList())
        override fun nextBytes(size: Int): ByteArray = values.removeFirst().also {
            assertEquals(size, it.size)
        }
    }

    companion object {
        private const val KEY_TEXT = """
            aes_kek_generation_source = 000102030405060708090a0b0c0d0e0f
            aes_key_generation_source = 101112131415161718191a1b1c1d1e1f
            master_key_00 = 202122232425262728292a2b2c2d2e2f
            master_key_12 = 303132333435363738393a3b3c3d3e3f
        """
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
