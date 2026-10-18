package com.dedhapp3n.pokeldn.sprites

import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_SPRITE_BYTES = 512 * 1024

internal data class PokemonSpriteKey(val species: Int, val form: Int, val shiny: Boolean) {
    init { require(species > 0 && form >= 0) }
    val cacheName: String get() = "$species-$form-${if (shiny) "shiny" else "normal"}.png"
}

/** Direct pixel PNGs. Unknown forms must not silently display the base form. */
internal fun interface PokemonSpriteSource {
    fun url(key: PokemonSpriteKey): String?
}

internal object PokeApiPixelSource : PokemonSpriteSource {
    override fun url(key: PokemonSpriteKey): String? {
        if (key.form != 0) return null
        val variant = if (key.shiny) "shiny/" else ""
        return "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$variant${key.species}.png"
    }
}

internal fun interface PokemonSpriteFetcher {
    fun fetch(url: String): ByteArray?
}

internal object HttpPokemonSpriteFetcher : PokemonSpriteFetcher {
    override fun fetch(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.contentLengthLong > MAX_SPRITE_BYTES) null
            else connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_SPRITE_BYTES) return null
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}

/** App-private persistent cache. A miss is remembered to avoid repeated downloads. */
internal class PokemonSpriteRepository(
    private val directory: File,
    private val source: PokemonSpriteSource = PokeApiPixelSource,
    private val fetcher: PokemonSpriteFetcher = HttpPokemonSpriteFetcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val failedAt = mutableMapOf<PokemonSpriteKey, Long>()

    @Synchronized fun load(key: PokemonSpriteKey): ByteArray? {
        val target = File(directory, key.cacheName)
        if (target.isFile) return target.takeIf { it.length() in 1..MAX_SPRITE_BYTES.toLong() }
            ?.readBytes()?.takeIf(::isPng)
        if (failedAt[key]?.let { now() - it < RETRY_AFTER_MS } == true) return null
        val url = source.url(key) ?: return null
        val bytes = runCatching { fetcher.fetch(url) }.getOrNull()?.takeIf(::isPng)
            ?: run { failedAt[key] = now(); return null }
        if (!directory.isDirectory && !directory.mkdirs()) return bytes
        val temp = File(directory, ".${key.cacheName}.tmp")
        return runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(bytes)
                temp.delete()
            }
            bytes
        }.getOrElse { temp.delete(); bytes }
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size in 8..MAX_SPRITE_BYTES &&
        PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
