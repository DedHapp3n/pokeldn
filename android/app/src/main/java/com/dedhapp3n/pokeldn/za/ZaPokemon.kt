package com.dedhapp3n.pokeldn.za

/** Gen 9 party record layout and Gen 8 block cipher, ported from upstream gen9.py/za/pokemon.py. */
internal object ZaPokemon {
    private const val STORED = 0x148
    private const val PARTY = 0x158
    private val blockOrder = arrayOf(
        intArrayOf(0,1,2,3), intArrayOf(0,1,3,2), intArrayOf(0,2,1,3), intArrayOf(0,3,1,2), intArrayOf(0,2,3,1), intArrayOf(0,3,2,1),
        intArrayOf(1,0,2,3), intArrayOf(1,0,3,2), intArrayOf(2,0,1,3), intArrayOf(3,0,1,2), intArrayOf(2,0,3,1), intArrayOf(3,0,2,1),
        intArrayOf(1,2,0,3), intArrayOf(1,3,0,2), intArrayOf(2,1,0,3), intArrayOf(3,1,0,2), intArrayOf(2,3,0,1), intArrayOf(3,2,0,1),
        intArrayOf(1,2,3,0), intArrayOf(1,3,2,0), intArrayOf(2,1,3,0), intArrayOf(3,1,2,0), intArrayOf(2,3,1,0), intArrayOf(3,2,1,0),
    )
    private val nationalShifts = intArrayOf(
        65,-1,-1,-1,-1,31,31,47,47,29,29,53,31,31,46,44,30,30,-7,-7,-7,13,13,-2,-2,23,23,24,-21,-21,27,27,47,
        47,47,26,14,-33,-33,-33,-17,-17,3,-29,12,-12,-31,-31,-31,3,3,-24,-24,-44,-44,-30,-30,-28,-28,23,23,6,7,
        29,8,3,4,4,20,4,23,6,3,3,4,-1,13,9,7,5,7,9,9,-43,-43,-43,-68,-68,-68,-58,-58,-25,-29,-31,6,-1,6,0,0,0,3,3,4,
        2,3,3,-5,-12,-12,
    )

    fun decrypt(raw: ByteArray): ByteArray {
        require(raw.size == PARTY) { "A Z-A party record is 344 bytes" }
        val ec = raw.u32(0)
        val shuffled = raw.copyOf()
        crypt(shuffled, 8, STORED, ec)
        crypt(shuffled, STORED, PARTY, ec) // party tail restarts the stream
        val plain = shuffled.copyOf()
        val order = blockOrder[((ec ushr 13) and 31).toInt() % 24]
        order.forEachIndexed { target, source ->
            shuffled.copyInto(plain, 8 + target * 0x50, 8 + source * 0x50, 8 + (source + 1) * 0x50)
        }
        var sum = 0
        for (at in 8 until STORED step 2) sum = (sum + plain.u16(at)) and 0xffff
        require(sum == raw.u16(6)) { "Invalid Pokémon checksum" }
        return plain
    }

    fun read(plain: ByteArray): ZaPokemonInfo {
        require(plain.size == PARTY)
        val internal = plain.u16(8)
        val shift = internal - 917
        val species = internal + if (shift in nationalShifts.indices) nationalShifts[shift] else 0
        val tid = plain.u16(0x0c)
        val sid = plain.u16(0x0e)
        val pid = plain.u32(0x1c)
        val ivPacked = plain.u32(0x8c)
        return ZaPokemonInfo(
            species = species, form = plain.u16(0x24), level = plain[STORED].u(), gender = (plain[0x22].u() ushr 1) and 3,
            shiny = (tid xor sid xor (pid ushr 16).toInt() xor (pid and 0xffff).toInt()) < 16,
            nature = plain[0x20].u(), ability = plain.u16(0x14), heldItem = plain.u16(0x0a), ball = plain[0x124].u(),
            tid = tid, sid = sid, ot = plain.name(0xf8), nickname = plain.name(0x58), pid = pid,
            encryptionConstant = plain.u32(0), ivs = (0..5).map { ((ivPacked ushr (it * 5)) and 31).toInt() },
            evs = (0..5).map { plain[0x26 + it].u() }, moves = (0..3).map { plain.u16(0x72 + 2 * it) },
            language = plain[0xd5].u(), metLocation = plain.u16(0x122), metDate = (0..2).map { plain[0x11c + it].u() },
            height = plain[0x48].u(), weight = plain[0x49].u(), scale = plain[0x4a].u(),
        )
    }

    private fun crypt(bytes: ByteArray, from: Int, until: Int, constant: Long) {
        var seed = constant
        for (at in from until until step 2) {
            seed = (seed * 0x41c64e6dL + 0x6073L) and 0xffffffffL
            val word = bytes.u16(at) xor ((seed ushr 16) and 0xffff).toInt()
            bytes[at] = word.toByte(); bytes[at + 1] = (word ushr 8).toByte()
        }
    }
    private fun Byte.u() = toInt() and 0xff
    private fun ByteArray.u16(at: Int) = this[at].u() or (this[at + 1].u() shl 8)
    private fun ByteArray.u32(at: Int) = (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
    private fun ByteArray.name(at: Int) = String(this, at, 26, Charsets.UTF_16LE).substringBefore('\u0000')
}

data class ZaPokemonInfo(
    val species: Int, val form: Int, val level: Int, val gender: Int, val shiny: Boolean,
    val nature: Int, val ability: Int, val heldItem: Int, val ball: Int,
    val tid: Int, val sid: Int, val ot: String, val nickname: String, val pid: Long,
    val encryptionConstant: Long, val ivs: List<Int>, val evs: List<Int>, val moves: List<Int>,
    val language: Int, val metLocation: Int, val metDate: List<Int>, val height: Int, val weight: Int, val scale: Int,
)
