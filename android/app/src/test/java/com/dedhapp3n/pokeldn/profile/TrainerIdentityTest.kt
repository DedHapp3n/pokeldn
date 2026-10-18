package com.dedhapp3n.pokeldn.profile

import com.dedhapp3n.pokeldn.za.ZaMessages
import org.junit.Assert.*
import org.junit.Test

class TrainerIdentityTest {
    private val manual = TrainerProfile("Manual", 10, 20, TrainerGender.MALE, TrainerLanguage.ENGLISH)

    @Test fun automaticModeAndDetectedTrainerRoundTripWhileManualSurvives() {
        val real = TrainerProfile("F14", 28641, 19407, TrainerGender.FEMALE, TrainerLanguage.GERMAN)
        val settings = TrainerIdentitySettings(TrainerSource.AUTOMATIC_FROM_GAME,
            DetectedTrainerProfile(real, "Pokémon Legends: Z-A", 123456789L))
        val stored = TrainerIdentityCodec.encode(settings)
        val loaded = TrainerIdentityCodec.decode(stored)
        assertEquals(settings, loaded)
        assertEquals(real, loaded.active(manual))
        assertEquals(manual, loaded.copy(source = TrainerSource.MANUAL).active(manual))
        assertEquals(manual, TrainerProfileCodec.decode(TrainerProfileCodec.encode(manual)))
    }

    @Test fun decodedZaIdentityIsEligibleForAutomaticProfile() {
        val message = javaClass.getResourceAsStream("/za/identity10.bin")!!.use { it.readBytes() }
        val detected = ZaMessages.trainer(message).asTrainerProfile()!!
        val settings = TrainerIdentitySettings(TrainerSource.AUTOMATIC_FROM_GAME,
            DetectedTrainerProfile(detected, "Pokémon Legends: Z-A", 1000))
        assertEquals("Player", TrainerIdentityCodec.decode(TrainerIdentityCodec.encode(settings)).active(manual).name)
        assertEquals(manual, TrainerIdentitySettings().active(manual))
    }
}
