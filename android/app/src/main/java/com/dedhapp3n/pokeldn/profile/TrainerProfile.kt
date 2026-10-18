package com.dedhapp3n.pokeldn.profile

import android.content.Context

enum class TrainerGender(val label: String) {
    NOT_SET("Not set"),
    MALE("Male"),
    FEMALE("Female"),
}

enum class TrainerLanguage(val label: String) {
    NOT_SET("Not set"),
    JAPANESE("Japanese"),
    ENGLISH("English"),
    FRENCH("French"),
    ITALIAN("Italian"),
    GERMAN("German"),
    SPANISH("Spanish"),
    KOREAN("Korean"),
    CHINESE_SIMPLIFIED("Chinese (Simplified)"),
    CHINESE_TRADITIONAL("Chinese (Traditional)"),
}

/** App-wide identity. Game-specific name encoding and length checks belong to each game module. */
data class TrainerProfile(
    val name: String = "",
    val tid: Int? = null,
    val sid: Int? = null,
    val gender: TrainerGender = TrainerGender.NOT_SET,
    val language: TrainerLanguage = TrainerLanguage.NOT_SET,
) {
    val configured: Boolean get() = validationError() == null

    fun validationError(): String? = when {
        name.isBlank() -> "Enter a trainer name"
        name.codePointCount(0, name.length) > MAX_NAME_CODE_POINTS ->
            "Trainer name must be $MAX_NAME_CODE_POINTS characters or fewer"
        name.any { it.isISOControl() } -> "Trainer name contains an unsupported control character"
        tid !in 0..MAX_TRAINER_ID -> "TID must be between 0 and $MAX_TRAINER_ID"
        sid !in 0..MAX_TRAINER_ID -> "SID must be between 0 and $MAX_TRAINER_ID"
        gender == TrainerGender.NOT_SET -> "Choose a gender"
        language == TrainerLanguage.NOT_SET -> "Choose a language"
        else -> null
    }

    fun normalized(): TrainerProfile = copy(name = name.trim())

    companion object {
        const val MAX_NAME_CODE_POINTS = 32
        const val MAX_TRAINER_ID = 65_535
    }
}

internal object TrainerProfileCodec {
    fun encode(profile: TrainerProfile): Map<String, String> = buildMap {
        put("name", profile.name)
        profile.tid?.let { put("tid", it.toString()) }
        profile.sid?.let { put("sid", it.toString()) }
        put("gender", profile.gender.name)
        put("language", profile.language.name)
    }

    fun decode(values: Map<String, String?>): TrainerProfile = TrainerProfile(
        name = values["name"].orEmpty(),
        tid = values["tid"]?.toIntOrNull()?.takeIf { it in 0..TrainerProfile.MAX_TRAINER_ID },
        sid = values["sid"]?.toIntOrNull()?.takeIf { it in 0..TrainerProfile.MAX_TRAINER_ID },
        gender = values["gender"].enumOrDefault(TrainerGender.NOT_SET),
        language = values["language"].enumOrDefault(TrainerLanguage.NOT_SET),
    )

    private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
}

class TrainerProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TrainerProfile = TrainerProfileCodec.decode(
        mapOf(
            "name" to preferences.getString("name", null),
            "tid" to preferences.getString("tid", null),
            "sid" to preferences.getString("sid", null),
            "gender" to preferences.getString("gender", null),
            "language" to preferences.getString("language", null),
        ),
    )

    fun save(profile: TrainerProfile): TrainerProfile {
        val normalized = profile.normalized()
        require(normalized.validationError() == null) { normalized.validationError() ?: "Invalid trainer profile" }
        preferences.edit().apply {
            clear()
            TrainerProfileCodec.encode(normalized).forEach(::putString)
        }.apply()
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "trainer-profile"
    }
}
