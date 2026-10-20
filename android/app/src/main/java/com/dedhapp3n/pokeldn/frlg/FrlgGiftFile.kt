package com.dedhapp3n.pokeldn.frlg

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class FrlgGiftFile(
    val name: String,
    private val variants: Map<String, FrlgBufferTransfer>,
) {
    val gameCodes: List<String> = variants.keys.sorted()

    fun transferForGameCode(gameCode: String): FrlgBufferTransfer? = variants[gameCode]?.let { transfer ->
        FrlgBufferTransfer(
            transfer.leads.map(ByteArray::copyOf),
            transfer.payload.copyOf(),
            transfer.expectation,
            transfer.ackEachLead,
            transfer.leadExpectation,
        )
    }

    companion object {
        const val MAX_FILE_BYTES = 256 * 1024
        private val SUPPORTED_GAME_CODES = setOf(
            "BPRF", "BPGF", "BPRE", "BPGE", "BPRS", "BPGS",
            "BPRD", "BPGD", "BPRI", "BPGI", "BPRJ", "BPGJ",
        )
        private val LEAD_PATTERN = Regex("buffer_lead_([1-8])")
        private val HEX_PATTERN = Regex("[0-9a-fA-F]+")
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val INTEGER_PATTERN = Regex("-?(0|[1-9][0-9]*)")

        fun parse(bytes: ByteArray): FrlgGiftFile {
            require(bytes.size <= MAX_FILE_BYTES) { ".pokegift file exceeds 256 KiB" }
            return try {
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                Parser(JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }).parse()
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid .pokegift JSON: ${error.message ?: error::class.java.simpleName}", error)
            }
        }
    }

    private class Parser(private val json: JsonReader) {
        fun parse(): FrlgGiftFile {
            var format: String? = null
            var version: Int? = null
            var game: String? = null
            var name: String? = null
            var variants: Map<String, FrlgBufferTransfer>? = null
            objectFields("gift", setOf("format", "version", "game", "name", "variants")) { field ->
                when (field) {
                    "format" -> format = string("format")
                    "version" -> version = integer("version")
                    "game" -> game = string("game")
                    "name" -> name = string("name")
                    "variants" -> variants = readVariants()
                }
            }
            require(json.peek() == JsonToken.END_DOCUMENT) { "Unexpected data after the gift object" }
            require(format == "pokeldn.gift") { "format must be pokeldn.gift" }
            require(version == 2) { "Only .pokegift version 2 console-code files are supported" }
            require(game == "frlg") { "game must be frlg" }
            val checkedName = requireNotNull(name) { "Missing field name" }
            val nameLength = checkedName.codePointCount(0, checkedName.length)
            require(checkedName.isNotBlank() && nameLength <= 180) { "name must contain 1 to 180 characters" }
            val checkedVariants = requireNotNull(variants) { "Missing field variants" }
            require(checkedVariants.size in 1..16) { "variants must contain 1 to 16 entries" }
            return FrlgGiftFile(checkedName, checkedVariants)
        }

        private fun readVariants(): Map<String, FrlgBufferTransfer> {
            requireToken(JsonToken.BEGIN_OBJECT, "variants must be an object")
            val result = linkedMapOf<String, FrlgBufferTransfer>()
            while (json.hasNext()) {
                val code = json.nextName()
                require(code in SUPPORTED_GAME_CODES) { "Unsupported FRLG game code: $code" }
                require(code !in result) { "Duplicate variant $code" }
                result[code] = readVariant(code)
            }
            json.endObject()
            return result
        }

        private fun readVariant(code: String): FrlgBufferTransfer {
            var data: Map<String, ByteArray>? = null
            var optionsSeen = false
            var expectation: FrlgBufferExpectation? = null
            var ackEachLead = false
            var leadExpectation: Long? = null
            objectFields("variant $code", setOf("data", "options")) { field ->
                when (field) {
                    "data" -> data = readData(code)
                    "options" -> {
                        optionsSeen = true
                        objectFields(
                            "options for $code",
                            setOf("buffer_expect", "buffer_ack_each_lead", "buffer_lead_expect"),
                        ) { option ->
                            when (option) {
                                "buffer_expect" -> expectation = readBufferExpectation(code)
                                "buffer_ack_each_lead" -> ackEachLead = boolean("buffer_ack_each_lead for $code")
                                "buffer_lead_expect" -> leadExpectation = unsigned32("buffer_lead_expect for $code")
                            }
                        }
                    }
                }
            }
            require(optionsSeen) { "Missing field options in variant $code" }
            val components = requireNotNull(data) { "Missing field data in variant $code" }
            val payload = components["buffer_code"]
                ?: throw IllegalArgumentException("Variant $code is missing buffer_code")
            val leadNumbers = components.keys.mapNotNull { LEAD_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()
            require(leadNumbers == (1..leadNumbers.size).toList()) {
                "Variant $code buffer leads must be numbered contiguously from buffer_lead_1"
            }
            if (ackEachLead) {
                require(leadNumbers.isNotEmpty()) {
                    "buffer_ack_each_lead for $code needs at least one buffer_lead"
                }
                require(leadExpectation != null) {
                    "buffer_ack_each_lead for $code needs buffer_lead_expect"
                }
            } else {
                require(leadExpectation == null) {
                    "buffer_lead_expect for $code needs buffer_ack_each_lead=true"
                }
            }
            return FrlgBufferTransfer(
                leadNumbers.map { requireNotNull(components["buffer_lead_$it"]) },
                payload,
                expectation,
                ackEachLead,
                leadExpectation,
            )
        }

        private fun readBufferExpectation(code: String): FrlgBufferExpectation = when (json.peek()) {
            JsonToken.NUMBER -> {
                val value = json.nextString()
                require(INTEGER_PATTERN.matches(value)) {
                    "buffer_expect for $code must be trainer-id or a 32-bit unsigned integer"
                }
                val parsed = value.toLongOrNull()
                require(parsed != null && parsed in 0..0xffff_ffffL) {
                    "buffer_expect for $code must be trainer-id or a 32-bit unsigned integer"
                }
                FrlgBufferExpectation.Status(parsed)
            }
            JsonToken.STRING -> {
                val value = json.nextString()
                require(value == "trainer-id") {
                    "buffer_expect for $code must be trainer-id or a 32-bit unsigned integer"
                }
                FrlgBufferExpectation.TrainerId
            }
            else -> throw IllegalArgumentException(
                "buffer_expect for $code must be trainer-id or a 32-bit unsigned integer",
            )
        }

        private fun readData(code: String): Map<String, ByteArray> {
            requireToken(JsonToken.BEGIN_OBJECT, "data for $code must be an object")
            val result = linkedMapOf<String, ByteArray>()
            while (json.hasNext()) {
                val component = json.nextName()
                require(component == "buffer_code" || LEAD_PATTERN.matches(component)) {
                    "Unsupported component $component in variant $code"
                }
                val value = readComponent(code, component)
                require(result.put(component, value) == null) { "Duplicate component $component in variant $code" }
            }
            json.endObject()
            return result
        }

        private fun readComponent(code: String, component: String): ByteArray {
            var hex: String? = null
            var expectedHash: String? = null
            objectFields("component $component", setOf("hex", "sha256")) { field ->
                when (field) {
                    "hex" -> hex = string("$code/$component hex")
                    "sha256" -> expectedHash = string("$code/$component sha256")
                }
            }
            val encoded = requireNotNull(hex) { "Missing hex in $code/$component" }
            require(encoded.length % 2 == 0 && HEX_PATTERN.matches(encoded)) { "Invalid hex in $code/$component" }
            val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(bytes.size in 1..1024) { "$code/$component must contain 1 to 1024 bytes" }
            require(bytes.size % 4 == 0) { "$code/$component ARM code must be a multiple of 4 bytes" }
            val hash = requireNotNull(expectedHash) { "Missing sha256 in $code/$component" }
            require(SHA_PATTERN.matches(hash)) { "Invalid sha256 in $code/$component" }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            require(actual == hash) { "SHA-256 mismatch in $code/$component" }
            return bytes
        }

        private fun objectFields(context: String, allowed: Set<String>, read: (String) -> Unit) {
            requireToken(JsonToken.BEGIN_OBJECT, "$context must be an object")
            val seen = mutableSetOf<String>()
            while (json.hasNext()) {
                val field = json.nextName()
                require(field in allowed) { "Unknown field $field in $context" }
                require(seen.add(field)) { "Duplicate field $field in $context" }
                read(field)
            }
            json.endObject()
        }

        private fun string(field: String): String {
            requireToken(JsonToken.STRING, "$field must be a string")
            return json.nextString()
        }

        private fun integer(field: String): Int {
            requireToken(JsonToken.NUMBER, "$field must be an integer")
            val value = json.nextString()
            require(INTEGER_PATTERN.matches(value)) { "$field must be an integer" }
            return value.toIntOrNull() ?: throw IllegalArgumentException("$field is outside the supported integer range")
        }

        private fun boolean(field: String): Boolean {
            requireToken(JsonToken.BOOLEAN, "$field must be true or false")
            return json.nextBoolean()
        }

        private fun unsigned32(field: String): Long {
            requireToken(JsonToken.NUMBER, "$field must be a 32-bit unsigned integer")
            val value = json.nextString()
            require(INTEGER_PATTERN.matches(value)) { "$field must be a 32-bit unsigned integer" }
            val parsed = value.toLongOrNull()
            require(parsed != null && parsed in 0..0xffff_ffffL) {
                "$field must be a 32-bit unsigned integer"
            }
            return parsed
        }

        private fun requireToken(token: JsonToken, message: String) {
            require(json.peek() == token) { message }
            when (token) {
                JsonToken.BEGIN_OBJECT -> json.beginObject()
                else -> Unit
            }
        }
    }
}

internal data class FrlgCustomGiftUiState(
    val filename: String? = null,
    val gift: FrlgGiftFile? = null,
    val validationDetail: String? = null,
    val validating: Boolean = false,
) {
    val valid: Boolean get() = gift != null
}
