package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnAccessPointBuilderTest {
    @Test
    fun parserRequiresOnlyTheFourUpstreamLdnKeys() {
        val parsed = LdnProdKeysParser.parse(KEY_TEXT.toByteArray())
        assertArrayEquals("202122232425262728292a2b2c2d2e2f".hex(), parsed.value("master_key_00"))

        assertThrows(LdnKeyException::class.java) {
            LdnProdKeysParser.parse("master_key_00 = 00".toByteArray())
        }
    }

    @Test
    fun dataKeyMatchesVendoredUpstreamKeyDerivation() {
        val config = fixtureBuilder().build(
            keys = LdnProdKeysParser.parse(KEY_TEXT.toByteArray()),
            protocol = 1,
            channel = 6,
            bssid = "021122334455".hex(),
            ssid = "505152535455565758595a5b5c5d5e5f".hex(),
            serverRandom = "404142434445464748494a4b4c4d4e4f".hex(),
            password = byteArrayOf(),
            maxParticipants = 8,
        )

        assertArrayEquals("17f3b9d296cf61bdf15f09f1cfad4f18".hex(), config.wlanKey)
        assertEquals(6, config.channel)
        assertEquals(8, config.maxParticipants)
    }

    @Test
    fun esp32PayloadMatchesUpstreamApStartFixture() {
        val config = fixtureBuilder().build(
            keys = LdnProdKeysParser.parse(KEY_TEXT.toByteArray()),
            protocol = 1,
            channel = 6,
            bssid = "021122334455".hex(),
            ssid = "505152535455565758595a5b5c5d5e5f".hex(),
            serverRandom = "404142434445464748494a4b4c4d4e4f".hex(),
            password = byteArrayOf(),
            maxParticipants = 8,
        )

        assertArrayEquals(
            ("060211223344553530353135323533353435353536353735383539356135623563356435653566" +
                "17f3b9d296cf61bdf15f09f1cfad4f180800").hex(),
            config.toEsp32AccessPointConfig().toPayload(),
        )
    }

    @Test
    fun diagnosticsIdentityUsesUpstreamShapeAndLocalUnicastBssid() {
        val source = QueueRandomSource(
            "000102030405060708090a0b0c0d0e0f".hex(),
            "101112131415161718191a1b1c1d1e1f".hex(),
            "ff1122334455".hex(),
        )
        val config = LdnAccessPointBuilder(source)
            .buildDiagnosticsNetwork(LdnProdKeysParser.parse(KEY_TEXT.toByteArray()))

        assertEquals(LdnAccessPointBuilder.DIAGNOSTIC_CHANNEL, config.channel)
        assertEquals(16, config.ssid.size)
        assertEquals(16, config.wlanKey.size)
        assertEquals(0x02, config.bssid[0].toInt() and 0x03)
        assertTrue(config.toEsp32AccessPointConfig().ssid.matches(Regex("[0-9a-f]{32}")))
    }

    private fun fixtureBuilder() = LdnAccessPointBuilder(QueueRandomSource())

    private class QueueRandomSource(vararg values: ByteArray) : LdnRandomSource {
        private val values = ArrayDeque(values.toList())
        override fun nextBytes(size: Int): ByteArray = values.removeFirst().also { assertEquals(size, it.size) }
    }

    companion object {
        private val KEY_TEXT = """
            aes_kek_generation_source = 000102030405060708090a0b0c0d0e0f
            aes_key_generation_source = 101112131415161718191a1b1c1d1e1f
            master_key_00 = 202122232425262728292a2b2c2d2e2f
            master_key_12 = 303132333435363738393a3b3c3d3e3f
        """.trimIndent()
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
