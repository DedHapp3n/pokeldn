package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LdnAdvertisementTest {
    @Test
    fun protocolThreeAdvertisementMatchesVendoredUpstreamFixture() {
        val random = QueueRandomSource(
            "404142434445464748494a4b4c4d4e4f".hex(),
            "505152535455565758595a5b5c5d5e5f".hex(),
            "021122334455".hex(),
            "60616263".hex(),
            "0102030405060708".hex(),
            byteArrayOf(33),
            byteArrayOf(0xb7.toByte()),
            "1112131415161718".hex(),
        )
        val network = LdnAdvertisementBuilder(random).buildDiscoveryNetwork(keys())
        assertEquals(LdnAdvertisementBuilder.LOCAL_COMMUNICATION_ID, network.localCommunicationId)
        assertEquals(LdnAdvertisementBuilder.SCENE_ID, network.sceneId)
        assertArrayEquals("760d603ffb6bc07c57f89651caaff05c".hex(), network.accessPoint.wlanKey)
        assertEquals(EXPECTED_ACTION_FRAME, network.advertisementFrame.toHex())
    }

    @Test
    fun discoveryApplicationDataMatchesUpstreamDefaultTrainerFixture() {
        assertEquals(
            EXPECTED_APP_DATA,
            LdnAdvertisementBuilder.buildDiscoveryApplicationData("b7f1".hex()).toHex(),
        )
    }

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
        private const val EXPECTED_APP_DATA =
            "005c1600580000000000000000000000000000000001010000000701504f4b454c444e000000000000" +
                "00000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                "00000000000000000000006346606c642e74673d6140524c5e71232323232323652323232323232323"
        private const val EXPECTED_ACTION_FRAME =
            "d0000000ffffffffffff021122334455ffffffffffff00007f0022aa040001010000000001006fa0233f" +
                "80000000570f00000000404142434445464748494a4b4c4d4e4f040300d460616263e17e1c743bcb" +
                "2270662f03f082dbb5db2405e256bdcb02348042cfc0dbaff50eab32ec4edc42e31cfd1bc0f05bae" +
                "b29d7ec8f68c965de9d6083f769a87a7d9be201ff786d8f2096320a5bacf54bb6dede8aef8fcab0" +
                "de4c7e23fbf452cd511299a36555aab61bf41485c7e4a01ebcb3ac09896b0b91e1dc7c42af315ec" +
                "d5eb947eacb764ce6a4211f756e68f3228222093be1041e743c805b965b3854faf2deacd1112fa92" +
                "faecf9f53f41f5c5499d9060e52d2a98e945f25cd53fd57121f0e7a3974bc12aef4b6052a58d13" +
                "7059a9793510855dd062710481ab1551543ae0112a4447cf"

        private fun keys() = LdnProdKeysParser.parse(KEY_TEXT.trimIndent().toByteArray())
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
