package com.dedhapp3n.pokeldn.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerProfileTest {
    @Test fun `complete profile validates and round trips without inventing values`() {
        val profile = TrainerProfile("Leaf", 12345, 54321, TrainerGender.FEMALE, TrainerLanguage.GERMAN)

        assertTrue(profile.configured)
        assertNull(profile.validationError())
        assertEquals(profile, TrainerProfileCodec.decode(TrainerProfileCodec.encode(profile)))
    }

    @Test fun `empty persisted state remains explicitly unset`() {
        val profile = TrainerProfileCodec.decode(emptyMap())

        assertFalse(profile.configured)
        assertEquals("", profile.name)
        assertNull(profile.tid)
        assertNull(profile.sid)
        assertEquals(TrainerGender.NOT_SET, profile.gender)
        assertEquals(TrainerLanguage.NOT_SET, profile.language)
    }

    @Test fun `ids and app wide name safety are validated`() {
        assertEquals("TID must be between 0 and 65535", valid().copy(tid = 65_536).validationError())
        assertEquals("SID must be between 0 and 65535", valid().copy(sid = -1).validationError())
        assertTrue(valid().copy(name = "A".repeat(33)).validationError()!!.contains("32"))
        assertTrue(valid().copy(name = "Bad\nName").validationError()!!.contains("control"))
    }

    @Test fun `unknown stored enum values fall back to not set`() {
        val decoded = TrainerProfileCodec.decode(mapOf("gender" to "OTHER", "language" to "KLINGON"))
        assertEquals(TrainerGender.NOT_SET, decoded.gender)
        assertEquals(TrainerLanguage.NOT_SET, decoded.language)
    }

    private fun valid() = TrainerProfile("Red", 1, 2, TrainerGender.MALE, TrainerLanguage.ENGLISH)
}
