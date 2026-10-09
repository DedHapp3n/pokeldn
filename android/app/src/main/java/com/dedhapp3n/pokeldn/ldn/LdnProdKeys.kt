package com.dedhapp3n.pokeldn.ldn

class LdnKeyException(message: String) : Exception(message)

class LdnProdKeys internal constructor(private val values: Map<String, ByteArray>) {
    internal fun value(name: String): ByteArray = values[name]?.copyOf()
        ?: throw LdnKeyException("Missing required key: $name")
}

object LdnProdKeysParser {
    val requiredKeyNames: Set<String> = linkedSetOf(
        "aes_kek_generation_source",
        "aes_key_generation_source",
        "master_key_00",
        "master_key_12",
    )

    fun parse(bytes: ByteArray): LdnProdKeys {
        val values = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) return@mapNotNull null
            val parts = clean.split('=', limit = 2).map(String::trim)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
        val invalid = requiredKeyNames.filter { values[it]?.matches(KEY_PATTERN) != true }
        if (invalid.isNotEmpty()) {
            throw LdnKeyException("Missing or invalid: ${invalid.joinToString()}")
        }
        return LdnProdKeys(requiredKeyNames.associateWith { values.getValue(it).hexToBytes() })
    }

    private val KEY_PATTERN = Regex("[0-9a-fA-F]{32}")

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
