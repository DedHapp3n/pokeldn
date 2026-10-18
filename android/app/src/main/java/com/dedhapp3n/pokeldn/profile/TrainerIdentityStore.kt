package com.dedhapp3n.pokeldn.profile

import android.content.Context

enum class TrainerSource { MANUAL, AUTOMATIC_FROM_GAME }

data class DetectedTrainerProfile(val profile: TrainerProfile, val sourceGame: String, val detectedAt: Long)

data class TrainerIdentitySettings(
    val source: TrainerSource = TrainerSource.MANUAL,
    val detected: DetectedTrainerProfile? = null,
) {
    fun active(manual: TrainerProfile): TrainerProfile =
        if (source == TrainerSource.AUTOMATIC_FROM_GAME) detected?.profile ?: manual else manual
}

/** Stored separately from the manual trainer profile, which remains available at all times. */
internal object TrainerIdentityCodec {
    fun encode(settings: TrainerIdentitySettings): Map<String, String> = buildMap {
        put("source", settings.source.name)
        settings.detected?.let { detected ->
            put("game", detected.sourceGame)
            put("detectedAt", detected.detectedAt.toString())
            TrainerProfileCodec.encode(detected.profile).forEach { (key, value) -> put("detected.$key", value) }
        }
    }

    fun decode(values: Map<String, String?>): TrainerIdentitySettings {
        val source = TrainerSource.entries.firstOrNull { it.name == values["source"] } ?: TrainerSource.MANUAL
        val game = values["game"]?.takeIf(String::isNotBlank)
        val time = values["detectedAt"]?.toLongOrNull()?.takeIf { it > 0 }
        val profile = TrainerProfileCodec.decode(
            listOf("name", "tid", "sid", "gender", "language").associateWith { values["detected.$it"] },
        ).takeIf { it.configured }
        val detected = if (game != null && time != null && profile != null) DetectedTrainerProfile(profile, game, time) else null
        return TrainerIdentitySettings(source, detected)
    }
}

class TrainerIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("trainer-identity-source", Context.MODE_PRIVATE)

    fun load(): TrainerIdentitySettings = TrainerIdentityCodec.decode(
        preferences.all.mapValues { it.value as? String },
    )

    fun setSource(source: TrainerSource): TrainerIdentitySettings = load().copy(source = source).also(::save)

    fun recordDetected(game: String, profile: TrainerProfile, now: Long = System.currentTimeMillis()): TrainerIdentitySettings {
        require(profile.configured && game.isNotBlank())
        val current = load()
        if (current.source != TrainerSource.AUTOMATIC_FROM_GAME) return current
        if (current.detected?.profile == profile && current.detected.sourceGame == game) return current
        return current.copy(detected = DetectedTrainerProfile(profile, game, now)).also(::save)
    }

    private fun save(settings: TrainerIdentitySettings) {
        val fields = TrainerIdentityCodec.encode(settings)
        preferences.edit().apply { clear(); fields.forEach(::putString) }.apply()
    }
}
