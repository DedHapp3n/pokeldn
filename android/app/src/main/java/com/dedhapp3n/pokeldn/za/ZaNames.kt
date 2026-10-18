package com.dedhapp3n.pokeldn.za

import android.content.res.AssetManager
import java.util.Locale

/** ID-indexed English names from pinned PKHeX.Core tables; unknown IDs remain explicit. */
internal class ZaNames(
    private val species: List<String>,
    private val natures: List<String>,
    private val abilities: List<String>,
    private val moves: List<String>,
    private val items: List<String>,
    private val locations: List<String>,
) {
    fun species(id: Int) = name(species, id)
    fun nature(id: Int) = name(natures, id)
    fun ability(id: Int) = name(abilities, id)
    fun item(id: Int) = if (id == 0) "None" else name(items, id)
    fun move(id: Int) = name(moves, id)
    fun location(id: Int) = name(locations, id)
    fun ball(id: Int) = BALLS.getOrNull(id) ?: unknown(id)

    fun headline(info: ZaPokemonInfo): String = species(info.species).uppercase(Locale.ROOT)
    fun summary(info: ZaPokemonInfo): String = buildString {
        if (info.form != 0) append("Form ${info.form} · ")
        append("Lv. ${info.level} · ${gender(info.gender)}")
    }

    companion object {
        fun fromAssets(assets: AssetManager): ZaNames {
            fun table(file: String): List<String> = assets.open("za/lookups/$file.txt").bufferedReader(Charsets.UTF_8)
                .use { it.readLines().map(String::trim) }
            return ZaNames(table("species"), table("natures"), table("abilities"),
                table("moves"), table("items"), table("za_locations"))
        }

        fun gender(value: Int) = when (value) { 0 -> "Male"; 1 -> "Female"; 2 -> "Genderless"; else -> "Unknown" }
        /** Record order is HP, Atk, Def, Spe, SpA, SpD; display the conventional order. */
        fun statRows(values: List<Int>): List<Pair<String, Int>> {
            require(values.size == 6)
            return listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe")
                .zip(listOf(0, 1, 2, 4, 5, 3).map(values::get))
        }
        private fun unknown(id: Int) = "Unknown (#$id)"
        private val BALLS = listOf(
            "None", "Master Ball", "Ultra Ball", "Great Ball", "Poké Ball", "Safari Ball", "Net Ball",
            "Dive Ball", "Nest Ball", "Repeat Ball", "Timer Ball", "Luxury Ball", "Premier Ball", "Dusk Ball",
            "Heal Ball", "Quick Ball", "Cherish Ball", "Fast Ball", "Level Ball", "Lure Ball", "Heavy Ball",
            "Love Ball", "Friend Ball", "Moon Ball", "Sport Ball", "Dream Ball", "Beast Ball", "Strange Ball",
            "Poké Ball (Hisui)", "Great Ball (Hisui)", "Ultra Ball (Hisui)", "Feather Ball", "Wing Ball",
            "Jet Ball", "Heavy Ball (Hisui)", "Leaden Ball", "Gigaton Ball", "Origin Ball",
        )
    }

    private fun name(table: List<String>, id: Int): String = table.getOrNull(id)
        ?.takeIf { value -> value.isNotBlank() && value.any(Char::isLetterOrDigit) }
        ?: unknown(id)
}
