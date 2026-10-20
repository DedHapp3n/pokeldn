package com.dedhapp3n.pokeldn.frlg

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgGiftFileTest {
    @Test fun `valid minimal FRLG v2 gift parses`() {
        val gift = parse(giftJson(mapOf("BPGD" to mapOf("buffer_code" to bytes(1, 2, 3, 4)))))
        assertEquals("Test patch", gift.name)
        assertEquals(listOf("BPGD"), gift.gameCodes)
        assertArrayEquals(bytes(1, 2, 3, 4), gift.transferForGameCode("BPGD")!!.payload)
    }

    @Test fun `wrong format is rejected`() = rejects("format must") {
        parse(giftJson().replace("pokeldn.gift", "other.gift"))
    }

    @Test fun `wrong version is rejected`() = rejects("version 2") {
        parse(giftJson().replace("\"version\":2", "\"version\":1"))
    }

    @Test fun `wrong game is rejected`() = rejects("game must be frlg") {
        parse(giftJson().replace("\"game\":\"frlg\"", "\"game\":\"emerald\""))
    }

    @Test fun `file over 256 KiB is rejected before JSON parsing`() = rejects("exceeds 256 KiB") {
        FrlgGiftFile.parse(ByteArray(FrlgGiftFile.MAX_FILE_BYTES + 1))
    }

    @Test fun `wrong component digest is rejected`() = rejects("SHA-256 mismatch") {
        parse(giftJson().replace(sha(bytes(1, 2, 3, 4)), "0".repeat(64)))
    }

    @Test fun `unknown component is rejected`() = rejects("Unsupported component card") {
        parse(giftJson(mapOf("BPGD" to mapOf("buffer_code" to bytes(1, 2, 3, 4), "card" to bytes(2)))))
    }

    @Test fun `missing buffer code is rejected`() = rejects("missing buffer_code") {
        parse(giftJson(mapOf("BPGD" to mapOf("buffer_lead_1" to bytes(1, 2, 3, 4)))))
    }

    @Test fun `buffer code over 1024 bytes is rejected`() = rejects("1 to 1024 bytes") {
        parse(giftJson(mapOf("BPGD" to mapOf("buffer_code" to ByteArray(1025) { 1 }))))
    }

    @Test fun `valid contiguous leads preserve order`() {
        val gift = parse(giftJson(mapOf("BPGD" to linkedMapOf(
            "buffer_code" to bytes(9, 9, 9, 9),
            "buffer_lead_1" to bytes(1, 1, 1, 1),
            "buffer_lead_2" to bytes(2, 2, 2, 2),
        ))))
        val transfer = gift.transferForGameCode("BPGD")!!
        assertEquals(2, transfer.leads.size)
        assertArrayEquals(bytes(1, 1, 1, 1), transfer.leads[0])
        assertArrayEquals(bytes(2, 2, 2, 2), transfer.leads[1])
    }

    @Test fun `lead gap is rejected`() = rejects("contiguously") {
        parse(giftJson(mapOf("BPGD" to mapOf(
            "buffer_code" to bytes(9, 9, 9, 9),
            "buffer_lead_1" to bytes(1, 1, 1, 1),
            "buffer_lead_3" to bytes(3, 3, 3, 3),
        ))))
    }

    @Test fun `lead over 1024 bytes is rejected`() = rejects("1 to 1024 bytes") {
        parse(giftJson(mapOf("BPGD" to mapOf(
            "buffer_code" to bytes(9, 9, 9, 9), "buffer_lead_1" to ByteArray(1025) { 2 },
        ))))
    }

    @Test fun `missing cartridge variant returns no transfer`() {
        val gift = parse(giftJson())
        assertNull(gift.transferForGameCode("BPRD"))
    }

    @Test fun `exact German FireRed and LeafGreen variants are selected`() {
        val gift = parse(giftJson(mapOf(
            "BPGD" to mapOf("buffer_code" to bytes(0x47, 0, 0, 0)),
            "BPRD" to mapOf("buffer_code" to bytes(0x52, 0, 0, 0)),
        )))
        assertArrayEquals(bytes(0x47, 0, 0, 0), gift.transferForGameCode("BPGD")!!.payload)
        assertArrayEquals(bytes(0x52, 0, 0, 0), gift.transferForGameCode("BPRD")!!.payload)
    }

    @Test fun `ARM components must be whole words`() = rejects("multiple of 4") {
        parse(giftJson(mapOf("BPGD" to mapOf("buffer_code" to bytes(1, 2, 3)))))
    }

    @Test fun `integer buffer expectation parses`() {
        val transfer = parse(giftJson(options = mapOf("buffer_expect" to "305419896")))
            .transferForGameCode("BPGD")!!
        assertEquals(FrlgBufferExpectation.Status(0x12345678L), transfer.expectation)
    }

    @Test fun `zero buffer expectation parses`() {
        val transfer = parse(giftJson(options = mapOf("buffer_expect" to "0")))
            .transferForGameCode("BPGD")!!
        assertEquals(FrlgBufferExpectation.Status(0), transfer.expectation)
    }

    @Test fun `maximum unsigned buffer expectation parses`() {
        val transfer = parse(giftJson(options = mapOf("buffer_expect" to "4294967295")))
            .transferForGameCode("BPGD")!!
        assertEquals(FrlgBufferExpectation.Status(0xffff_ffffL), transfer.expectation)
    }

    @Test fun `negative buffer expectation is rejected`() = rejects("32-bit unsigned integer") {
        parse(giftJson(options = mapOf("buffer_expect" to "-1")))
    }

    @Test fun `buffer expectation above unsigned word is rejected`() = rejects("32-bit unsigned integer") {
        parse(giftJson(options = mapOf("buffer_expect" to "4294967296")))
    }

    @Test fun `trainer id buffer expectation parses`() {
        val transfer = parse(giftJson(options = mapOf("buffer_expect" to "\"trainer-id\"")))
            .transferForGameCode("BPGD")!!
        assertEquals(FrlgBufferExpectation.TrainerId, transfer.expectation)
    }

    @Test fun `unknown string buffer expectation is rejected`() = rejects("32-bit unsigned integer") {
        parse(giftJson(options = mapOf("buffer_expect" to "\"player-id\"")))
    }

    @Test fun `non integer buffer expectation type is rejected`() = rejects("32-bit unsigned integer") {
        parse(giftJson(options = mapOf("buffer_expect" to "true")))
    }

    @Test fun `other unsupported options are rejected instead of ignored`() = rejects("Unknown field buffer_dump_size") {
        parse(giftJson(options = mapOf("buffer_dump_size" to "4")))
    }

    @Test fun `unknown fields and duplicate fields are rejected`() {
        rejects("Unknown field extra") {
            parse(giftJson().replace("\"format\":", "\"extra\":1,\"format\":"))
        }
        rejects("Duplicate field name") {
            parse(giftJson().replace("\"name\":\"Test patch\"", "\"name\":\"one\",\"name\":\"two\""))
        }
    }

    private fun giftJson(
        variants: Map<String, Map<String, ByteArray>> = mapOf("BPGD" to mapOf("buffer_code" to bytes(1, 2, 3, 4))),
        options: Map<String, String> = emptyMap(),
    ): String = """{"format":"pokeldn.gift","version":2,"game":"frlg","name":"Test patch","variants":{${
        variants.entries.joinToString(",") { (code, data) ->
            "\"$code\":{\"data\":{${data.entries.joinToString(",") { (key, value) -> "\"$key\":${component(value)}" }}}," +
                "\"options\":{${options.entries.joinToString(",") { (key, value) -> "\"$key\":$value" }}}}"
        }
    }}}"""

    private fun component(data: ByteArray) = """{"hex":"${data.hex()}","sha256":"${sha(data)}"}"""
    private fun parse(json: String) = FrlgGiftFile.parse(json.encodeToByteArray())
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun rejects(message: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue("Expected rejection containing '$message', got ${error?.message}", error is IllegalArgumentException)
        assertTrue("Expected rejection containing '$message', got ${error?.message}", error!!.message.orEmpty().contains(message))
    }
}
