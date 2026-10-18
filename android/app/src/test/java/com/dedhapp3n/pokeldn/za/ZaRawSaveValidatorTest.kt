package com.dedhapp3n.pokeldn.za

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ZaRawSaveValidatorTest {
    private val intro = hex("9EC99CD70ED33C44FB9303DCEB39B42A1947E9634BA2334416BF82A2BA6355B63D9DF24B5F7B6AB2621DC21B68E5C8B53A059000E8A8103DE2ECF00CB2ED4F6D")
    private val outro = hex("D6C01C598BC8B8CB46E153FC828C757513E045DF32693C75F059F8D9A25FB217E08052DBEA8973997579AFCB2E8007E6F126E0030AE66FF641BF7E59C2AE55FD")

    @Test fun acceptsEveryPkhexZaSizeWithPkhexHash() {
        assertEquals(setOf(0x2F3284, 0x2F3289, 0x309FA6, 0x309FB3), ZaRawSaveValidator.knownSizes)
        ZaRawSaveValidator.knownSizes.forEach { size ->
            val candidate = candidate(size)
            assertTrue("size 0x${size.toString(16)}", ZaRawSaveValidator.validate(candidate).valid)
        }
    }

    @Test fun rejectsWrongSizeEvenWithValidHash() {
        val result = ZaRawSaveValidator.validate(candidate(4096))
        assertFalse(result.knownSize)
        assertTrue(result.hashValid)
        assertFalse(result.valid)
    }

    @Test fun rejectsCorruptedFinalHash() {
        val candidate = candidate(0x2F3284).also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertFalse(ZaRawSaveValidator.validate(candidate).hashValid)
    }

    @Test fun rejectsCorruptedPayload() {
        val candidate = candidate(0x2F3284).also { it[12345] = (it[12345] + 1).toByte() }
        assertFalse(ZaRawSaveValidator.validate(candidate).hashValid)
    }

    @Test fun rejectsUniformFiles() {
        for (fill in listOf(0x00, 0xff)) {
            val result = ZaRawSaveValidator.validate(ByteArray(0x2F3284) { fill.toByte() })
            assertFalse(result.nonUniform)
            assertFalse(result.valid)
        }
    }

    private fun candidate(size: Int): ByteArray {
        val data = ByteArray(size) { index -> (index * 31 + 7).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(intro)
        digest.update(data, 0, size - 32)
        digest.update(outro)
        digest.digest().copyInto(data, size - 32)
        return data
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
