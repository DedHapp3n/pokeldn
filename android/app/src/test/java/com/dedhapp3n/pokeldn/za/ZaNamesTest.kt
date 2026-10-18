package com.dedhapp3n.pokeldn.za

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ZaNamesTest {
    private fun names(): ZaNames {
        val base = File("src/main/assets/za/lookups")
        fun table(name: String) = File(base, "$name.txt").readLines(Charsets.UTF_8).map(String::trim)
        return ZaNames(table("species"), table("natures"), table("abilities"),
            table("moves"), table("items"), table("za_locations"))
    }

    @Test fun pinnedNamesResolveKnownIdsAndUnknownsStayExplicit() {
        val names = names()
        assertEquals("Camerupt", names.species(323))
        assertEquals("Quiet", names.nature(17))
        assertEquals("Simple", names.ability(86))
        assertEquals("None", names.item(0))
        assertEquals("Great Ball", names.ball(3))
        assertEquals("Lava Plume", names.move(436))
        assertEquals("Wild Zone 8", names.location(211))
        assertEquals("Unknown (#99999)", names.ability(99999))
        assertEquals("Unknown (#99999)", names.location(99999))
    }

    @Test fun pokemonHeadlineSummaryAndStatsReadNaturally() {
        val source = javaClass.getResourceAsStream("/za/preview.bin")!!.use { ZaMessages.preview(it.readBytes()).pokemon }
        val pokemon = source.copy(species = 323, form = 0, level = 30, gender = 1)
        assertEquals("CAMERUPT", names().headline(pokemon))
        assertEquals("Lv. 30 · Female", names().summary(pokemon))
        assertEquals("Form 2 · Lv. 30 · Female", names().summary(pokemon.copy(form = 2)))
        assertEquals(listOf("HP", "Atk", "Def", "SpA", "SpD", "Spe"), ZaNames.statRows(pokemon.ivs).map { it.first })
    }

    @Test fun statRowsReorderWireValuesForDisplay() {
        assertEquals(listOf("HP" to 1, "Atk" to 2, "Def" to 3, "SpA" to 5, "SpD" to 6, "Spe" to 4),
            ZaNames.statRows(listOf(1, 2, 3, 4, 5, 6)))
    }
}
