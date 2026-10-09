package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertEquals
import org.junit.Test

class ProdKeysValidatorTest {
    @Test
    fun acceptsTheFourKeysUsedByUpstreamLdnTests() {
        val text = ProdKeysValidator.requiredKeys.joinToString("\n") { "$it = 00112233445566778899aabbccddeeff" }
        assertEquals(ProdKeysPhase.AVAILABLE, ProdKeysValidator.validate(text.toByteArray()).phase)
    }

    @Test
    fun rejectsMissingRequiredKeys() {
        assertEquals(
            ProdKeysPhase.INVALID,
            ProdKeysValidator.validate("master_key_00 = 00".toByteArray()).phase,
        )
    }
}
