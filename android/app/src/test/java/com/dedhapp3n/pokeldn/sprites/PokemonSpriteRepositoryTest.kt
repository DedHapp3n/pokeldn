package com.dedhapp3n.pokeldn.sprites

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PokemonSpriteRepositoryTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1)

    @Test fun keySeparatesSpeciesFormAndShinyAndUnknownFormHasNoWrongSprite() {
        assertNotEquals(PokemonSpriteKey(25, 0, false).cacheName, PokemonSpriteKey(25, 0, true).cacheName)
        assertNotEquals(PokemonSpriteKey(25, 0, false).cacheName, PokemonSpriteKey(25, 1, false).cacheName)
        assertEquals(null, PokeApiPixelSource.url(PokemonSpriteKey(25, 1, false)))
        assertTrue(PokeApiPixelSource.url(PokemonSpriteKey(25, 0, true))!!.contains("/shiny/25.png"))
    }

    @Test fun diskHitSurvivesRepositoryRecreationWithoutDownload() {
        val folder = Files.createTempDirectory("sprite-test").toFile()
        try {
            var downloads = 0
            val fetcher = PokemonSpriteFetcher { downloads++; png }
            val key = PokemonSpriteKey(323, 0, false)
            assertArrayEquals(png, PokemonSpriteRepository(folder, fetcher = fetcher).load(key))
            assertArrayEquals(png, PokemonSpriteRepository(folder, fetcher = fetcher).load(key))
            assertEquals(1, downloads)
        } finally { folder.deleteRecursively() }
    }

    @Test fun missingAndMalformedSpritesUsePlaceholderWithoutDownloadLoop() {
        val folder = Files.createTempDirectory("sprite-missing-test").toFile()
        try {
            var downloads = 0
            var time = 1_000L
            val repo = PokemonSpriteRepository(folder, fetcher = PokemonSpriteFetcher { downloads++; byteArrayOf(1, 2) }, now = { time })
            val key = PokemonSpriteKey(999, 0, false)
            assertNull(repo.load(key))
            assertNull(repo.load(key))
            assertEquals(1, downloads)
            time += 60_000
            assertNull(repo.load(key))
            assertEquals(2, downloads)
            assertNull(repo.load(PokemonSpriteKey(999, 2, false)))
            assertEquals(2, downloads)
        } finally { folder.deleteRecursively() }
    }
}
