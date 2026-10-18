package com.dedhapp3n.pokeldn.za

import com.dedhapp3n.pokeldn.profile.TrainerGender
import com.dedhapp3n.pokeldn.profile.TrainerLanguage
import com.dedhapp3n.pokeldn.profile.TrainerProfile
import java.security.MessageDigest

/** Decoders for the application messages documented in current upstream docs/za.md. */
internal object ZaMessages {
    fun trainer(message: ByteArray): ZaTrainer {
        require(message.size == 106 && message[0].u() == 0x14 && message[1].u() == 0) { "Invalid 1400 identity" }
        var pos = 2
        fun integer(): Long {
            require(pos < message.size)
            val marker = message[pos++].u()
            val width = when (marker) { in 0..127 -> return marker.toLong(); 0x80 -> 1; 0x81 -> 2; 0x82 -> 4; 0x83 -> 8; else -> error("Invalid integer tag") }
            require(pos + width <= message.size)
            var value = 0L
            repeat(width) { value = value or (message[pos++].u().toLong() shl (it * 8)) }
            return value
        }
        fun tagged(tag: Int, expected: Long? = null): Long {
            require(message[pos++].u() == tag) { "Invalid identity tag" }
            return integer().also { if (expected != null) require(it == expected) }
        }
        tagged(0xb9, 2)
        integer() // UserData key
        tagged(0xb9, 1)
        tagged(0xbc, 0x5d)
        tagged(0xb9, 6)
        val id = integer()
        val gender = integer().toInt()
        val language = integer().toInt()
        tagged(0xbc, 26)
        require(pos + 26 <= message.size)
        val name = String(message, pos, 26, Charsets.UTF_16LE).substringBefore('\u0000')
        require(name.isNotBlank())
        return ZaTrainer(name, (id and 0xffff).toInt(), (id ushr 16).toInt(), gender, language)
    }

    fun identitySync(message: ByteArray): Long {
        require(message.size == 9 && message.sliceArray(0..4).contentEquals(byteArrayOf(0x14, 0x03, 0xb9.toByte(), 0x01, 0x82.toByte())))
        return message.u32(5)
    }

    fun selection(message: ByteArray): ByteArray {
        require(message.size == 1211 && message[0].u() == 1 && message[1].u() == 0) { "Invalid 0100 selection" }
        return message.copyOf()
    }

    fun preview(message: ByteArray): ZaPreview {
        require(message.size == 354 && message.sliceArray(0..8).contentEquals(byteArrayOf(1, 1, 0xb9.toByte(), 3, 0, 0xbc.toByte(), 0x81.toByte(), 0x58, 1))) { "Invalid 0101 offer" }
        val marker = message.last().u()
        require(marker == 0 || marker == 1) { "Unknown offer marker" }
        val raw = message.copyOfRange(9, 353)
        val plain = ZaPokemon.decrypt(raw)
        val info = ZaPokemon.read(plain)
        val hash = MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it.u()) }
        return ZaPreview(marker == 1, raw, info, hash)
    }

    private fun Byte.u() = toInt() and 0xff
    private fun ByteArray.u32(at: Int): Long = (0..3).fold(0L) { n, i -> n or (this[at + i].u().toLong() shl (8 * i)) }
}

data class ZaTrainer(val name: String, val tid: Int, val sid: Int, val gender: Int, val language: Int) {
    val genderLabel: String get() = when (gender) { 0 -> "Male"; 1 -> "Female"; else -> "Unknown ($gender)" }
    val languageLabel: String get() = when (language) {
        1 -> "Japanese"; 2 -> "English"; 3 -> "French"; 4 -> "Italian"; 5 -> "German"
        7 -> "Spanish"; 8 -> "Korean"; 9 -> "Chinese (Traditional)"; 10 -> "Chinese (Simplified)"
        11 -> "Spanish (Latin America)"; else -> "Unknown ($language)"
    }

    /** Explicit copy candidate; never writes the user's configured profile. */
    fun asTrainerProfile(): TrainerProfile? {
        val profile = TrainerProfile(
            name, tid, sid,
            when (gender) { 0 -> TrainerGender.MALE; 1 -> TrainerGender.FEMALE; else -> TrainerGender.NOT_SET },
            when (language) {
                1 -> TrainerLanguage.JAPANESE; 2 -> TrainerLanguage.ENGLISH; 3 -> TrainerLanguage.FRENCH
                4 -> TrainerLanguage.ITALIAN; 5 -> TrainerLanguage.GERMAN; 7 -> TrainerLanguage.SPANISH
                8 -> TrainerLanguage.KOREAN; 9 -> TrainerLanguage.CHINESE_TRADITIONAL
                10 -> TrainerLanguage.CHINESE_SIMPLIFIED; else -> TrainerLanguage.NOT_SET
            },
        )
        return profile.takeIf { it.validationError() == null }
    }
}

internal data class ZaPreview(val shown: Boolean, val raw: ByteArray, val pokemon: ZaPokemonInfo, val fingerprint: String)
