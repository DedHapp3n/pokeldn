package com.dedhapp3n.pokeldn.ldn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LdnRawDataFrameTest {
    @Test
    fun upstream76ByteGroupFixtureDecryptsToSnapArpEthernet() {
        val frame = LdnRawDataFrame.decode(UPSTREAM_GROUP_ARP.hex())

        assertFalse(frame.toDs)
        assertFalse(frame.fromDs)
        assertTrue(frame.protectedFrame)
        assertFalse(frame.qos)
        assertEquals(0x010203040506L, frame.packetNumber)
        assertEquals(1, frame.keyId)
        assertArrayEquals(PEER.hex(), frame.source)
        assertArrayEquals(EXPECTED_ARP_ETHERNET.hex(), frame.decryptToEthernet(KEY.hex()))
    }

    @Test
    fun upstreamDriverDecryptedRetainedCcmpFrameIsNormalizedWithoutSecondDecrypt() {
        val result = LdnRawGroupDecoder(ByteArray(16) { 0x7f }).decode(UPSTREAM_HYBRID_GROUP_ARP.hex())

        assertTrue(result.driverDecrypted)
        assertArrayEquals(EXPECTED_ARP_ETHERNET.hex(), result.ethernet)
    }

    @Test
    fun upstreamDriverDecryptedFrameWithoutCcmpEnvelopeIsNormalized() {
        val result = LdnRawGroupDecoder(ByteArray(16)).decode(UPSTREAM_DRIVER_PLAINTEXT_GROUP_ARP.hex())

        assertTrue(result.driverDecrypted)
        assertArrayEquals(EXPECTED_ARP_ETHERNET.hex(), result.ethernet)
    }

    @Test
    fun qosTidIsUsedByUpstreamCcmpNonceAndAad() {
        val frame = LdnRawDataFrame.decode(UPSTREAM_QOS_GROUP_ARP.hex())

        assertTrue(frame.qos)
        assertEquals(5, frame.tid)
        assertEquals(1, frame.keyId)
        assertArrayEquals(EXPECTED_ARP_ETHERNET.hex(), frame.decryptToEthernet(KEY.hex()))
    }

    @Test
    fun badMicAndWrongKeyAreRejected() {
        val badMic = UPSTREAM_GROUP_ARP.hex().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            LdnRawDataFrame.decode(badMic).decryptToEthernet(KEY.hex())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LdnRawDataFrame.decode(UPSTREAM_GROUP_ARP.hex()).decryptToEthernet(ByteArray(16) { 9 })
        }
    }

    @Test
    fun nullAndAmsduFramesAreRejected() {
        val nullFrame = UPSTREAM_GROUP_ARP.hex().also { it[0] = 0x48 }
        assertThrows(IllegalArgumentException::class.java) { LdnRawDataFrame.decode(nullFrame) }
        val amsdu = UPSTREAM_QOS_GROUP_ARP.hex().also { it[24] = 0x85.toByte() }
        assertThrows(IllegalArgumentException::class.java) { LdnRawDataFrame.decode(amsdu) }
    }

    companion object {
        // Generated with the tracked vendor/LDN DataFrame.encrypt using the values below.
        // The QoS vector uses the same encryptor's QoS AAD/nonce and an explicit QoS MAC header.
        const val KEY = "000102030405060708090a0b0c0d0e0f"
        const val PEER = "0a0b0c0d0e0f"
        const val EXPECTED_ARP_ETHERNET =
            "ffffffffffff0a0b0c0d0e0f080600010800060400010a0b0c0d0e0fa9fe0e02000000000000a9fe0e01"
        const val UPSTREAM_GROUP_ARP =
            "08400000ffffffffffff0a0b0c0d0e0f02112233445500000605006004030201" +
                "2f35a964acab5b7283864f11d5bec8a860d91a09a42a47ea574aff1ad0278ea5226a1c1bed2d1459fc9c9fe7"
        const val UPSTREAM_QOS_GROUP_ARP =
            "88400000ffffffffffff0a0b0c0d0e0f021122334455000005000605006004030201" +
                "b27faac58b302293de171e5404d864cb4a891338be927d205e8457e243648d7d5762e230c38a98788a07b072"
        const val DERIVED_KEY_ARP_ETHERNET =
            "ffffffffffff0a0b0c0d0e0f080600010800060400010a0b0c0d0e0fa9fe2102000000000000a9fe2101"
        const val UPSTREAM_HYBRID_GROUP_ARP =
            "08400000ffffffffffff0a0b0c0d0e0f02112233445500000605006004030201" +
                "aaaa03000000080600010800060400010a0b0c0d0e0fa9fe0e02000000000000a9fe0e01ed2d1459fc9c9fe7"
        const val UPSTREAM_DRIVER_PLAINTEXT_GROUP_ARP =
            "08400000ffffffffffff0a0b0c0d0e0f0211223344550000" +
                "aaaa03000000080600010800060400010a0b0c0d0e0fa9fe0e02000000000000a9fe0e01"
    }
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
