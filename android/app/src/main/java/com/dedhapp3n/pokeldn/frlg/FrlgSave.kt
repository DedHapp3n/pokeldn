package com.dedhapp3n.pokeldn.frlg

/** Read-only FireRed/LeafGreen 128 KiB flash-save parsing, matching upstream sav.py. */
internal object FrlgSave {
    const val SAVE_SIZE = 0x20000
    const val SECTOR_SIZE = 0x1000
    const val SECTOR_COUNT = 32
    const val SLOT_SECTORS = 14
    const val EMULATOR_FOOTER_SIZE = 16
    const val SECTOR_ID_OFFSET = 0xff4
    const val SECTOR_CHECKSUM_OFFSET = 0xff6
    const val SECTOR_SIGNATURE_OFFSET = 0xff8
    const val SECTOR_COUNTER_OFFSET = 0xffc
    const val SECTOR_SIGNATURE = 0x08012025L

    enum class Layout { LATIN, JAPANESE }

    private val chunkSizes = mapOf(
        Layout.LATIN to intArrayOf(3876, 3968, 3968, 3968, 3816, 3968, 3968, 3968, 3968, 3968, 3968, 3968, 3968, 2000),
        Layout.JAPANESE to intArrayOf(3876, 3968, 3968, 3968, 3776, 3968, 3968, 3968, 3968, 3968, 3968, 3968, 3968, 2000),
    )

    data class SectorFooter(val id: Int, val checksum: Int, val signature: Long, val counter: Long)
    data class Slot(val index: Int, val sound: Boolean, val counter: Long?, val layouts: Set<Layout>)
    data class Summary(val slots: List<Slot>, val newest: Slot?) {
        val sound: Boolean get() = newest != null
        val japanese: Boolean get() = newest?.layouts == setOf(Layout.JAPANESE)
    }
    data class SectorWrite(val physicalSector: Int, val data: ByteArray)

    class SaveException(message: String) : IllegalArgumentException(message)

    fun normalize(input: ByteArray): ByteArray {
        val data = if (input.size == SAVE_SIZE + EMULATOR_FOOTER_SIZE) input.copyOf(SAVE_SIZE) else input.copyOf()
        if (data.size != SAVE_SIZE) {
            throw SaveException("a FireRed or LeafGreen save is 128 KB; this file is ${data.size} bytes")
        }
        return data
    }

    fun sector(data: ByteArray, physicalIndex: Int): ByteArray {
        require(physicalIndex in 0 until SECTOR_COUNT)
        return data.copyOfRange(physicalIndex * SECTOR_SIZE, (physicalIndex + 1) * SECTOR_SIZE)
    }

    fun footer(data: ByteArray, physicalIndex: Int): SectorFooter {
        val at = physicalIndex * SECTOR_SIZE
        return SectorFooter(
            u16(data, at + SECTOR_ID_OFFSET),
            u16(data, at + SECTOR_CHECKSUM_OFFSET),
            u32(data, at + SECTOR_SIGNATURE_OFFSET),
            u32(data, at + SECTOR_COUNTER_OFFSET),
        )
    }

    /** Game checksum: wrapping sum of little-endian u32s, folded to 16 bits once. */
    fun sectorChecksum(data: ByteArray, size: Int): Int {
        require(size >= 0 && size % 4 == 0 && size <= data.size)
        var total = 0L
        var at = 0
        while (at < size) {
            total = (total + u32(data, at)) and 0xffffffffL
            at += 4
        }
        return (((total ushr 16) + total) and 0xffff).toInt()
    }

    fun validLayouts(data: ByteArray, physicalIndex: Int): Set<Layout> {
        val footer = footer(data, physicalIndex)
        if (footer.signature != SECTOR_SIGNATURE || footer.id !in 0 until SLOT_SECTORS) return emptySet()
        val raw = sector(data, physicalIndex)
        return Layout.entries.filterTo(linkedSetOf()) { layout ->
            sectorChecksum(raw, chunkSizes.getValue(layout)[footer.id]) == footer.checksum
        }
    }

    fun slot(data: ByteArray, index: Int): Slot {
        require(index in 0..1)
        val ids = mutableSetOf<Int>()
        var counter: Long? = null
        val layouts = Layout.entries.toMutableSet()
        for (n in index * SLOT_SECTORS until (index + 1) * SLOT_SECTORS) {
            val valid = validLayouts(data, n)
            if (valid.isEmpty()) continue
            val footer = footer(data, n)
            ids += footer.id
            counter = footer.counter // Matches GetSaveValidStatus/upstream: last valid physical sector.
            layouts.retainAll(valid)
        }
        val sound = ids.size == SLOT_SECTORS && layouts.isNotEmpty()
        return Slot(index, sound, counter, if (sound) layouts.toSet() else emptySet())
    }

    fun describe(input: ByteArray): Summary {
        val data = normalize(input)
        val slots = listOf(slot(data, 0), slot(data, 1))
        val newest = slots.filter { it.sound }.fold<Slot, Slot?>(null) { best, candidate ->
            if (best == null) candidate else newer(best, candidate)
        }
        return Summary(slots, newest)
    }

    fun checkRestorable(input: ByteArray, japanese: Boolean): Summary {
        val data = normalize(input)
        val summary = describe(data)
        if (!summary.sound) throw SaveException("the save has no whole copy of a game in it")
        val spoken = language(data, summary)
        val wanted = if (japanese) Layout.JAPANESE else Layout.LATIN
        if ((spoken != null && (spoken == 1) != japanese) || wanted !in requireNotNull(summary.newest).layouts) {
            throw SaveException(if (japanese)
                "this save is from a Latin-alphabet cartridge; the console runs a Japanese one"
            else "this save is from a Japanese cartridge; the console runs another language")
        }
        return summary
    }

    /** Upstream sav.py chip_newest(): only signature and 14 distinct ids are required. */
    fun chipNewest(footers: ByteArray): Pair<Int, Long>? {
        require(footers.size >= 2 * SLOT_SECTORS * 12) { "the console returned incomplete save footers" }
        var best: Pair<Int, Long>? = null
        for (slot in 0..1) {
            val ids = mutableSetOf<Int>()
            var counter = 0L
            var valid = true
            for (n in slot * SLOT_SECTORS until (slot + 1) * SLOT_SECTORS) {
                val at = n * 12
                if (u32(footers, at + 4) != SECTOR_SIGNATURE) { valid = false; break }
                ids += u16(footers, at)
                counter = u32(footers, at + 8)
            }
            if (valid && ids.size == SLOT_SECTORS) {
                val candidate = slot to counter
                if (best == null || ((counter + 1) and 0xffffffffL) > ((best.second + 1) and 0xffffffffL)) best = candidate
            }
        }
        return best
    }

    /** Exact upstream safe restore order, including id-0 invalidation and extras. */
    fun sectorsToWrite(input: ByteArray, footers: ByteArray): List<SectorWrite> {
        val data = normalize(input)
        val summary = describe(data)
        val source = summary.newest ?: throw SaveException("the save has no whole copy of a game in it")
        val newest = chipNewest(footers)
        val counter = if (newest == null) 0L else (newest.second + 1) and 0xffffffffL
        val target = (counter % 2).toInt()
        val copy = (0 until SLOT_SECTORS).map { i ->
            val raw = sector(data, source.index * SLOT_SECTORS + i)
            putU32(raw, SECTOR_COUNTER_OFFSET, counter)
            SectorWrite(target * SLOT_SECTORS + i, raw)
        }
        val result = mutableListOf<SectorWrite>()
        for (i in 0 until SLOT_SECTORS) {
            val at = (target * SLOT_SECTORS + i) * 12
            if (u32(footers, at + 4) == SECTOR_SIGNATURE && u16(footers, at) == 0) {
                result += SectorWrite(target * SLOT_SECTORS + i, ByteArray(SECTOR_SIZE) { 0xff.toByte() })
            }
        }
        result += copy.filter { footer(it.data, 0).id != 0 }
        result += copy.filter { footer(it.data, 0).id == 0 }
        result += (28 until SECTOR_COUNT).map { SectorWrite(it, sector(data, it)) }
        return result
    }

    data class Token(val encoded: ByteArray, val expandedSize: Int)
    fun deflate(input: ByteArray): List<Token> {
        val out = mutableListOf<Token>(); var at = 0
        while (at < input.size) {
            var run = 1
            while (at + run < input.size && run < 130 && input[at + run] == input[at]) run++
            if (run >= 3) { out += Token(byteArrayOf((0x80 + run - 3).toByte(), input[at]), run); at += run; continue }
            val start = at
            while (at < input.size && at - start < 128) {
                if (at > start && at + 2 < input.size && input[at] == input[at + 1] && input[at] == input[at + 2]) break
                at++
            }
            out += Token(byteArrayOf((at - start - 1).toByte()) + input.copyOfRange(start, at), at - start)
        }
        return out
    }

    private fun language(data: ByteArray, summary: Summary): Int? {
        val newest = requireNotNull(summary.newest)
        fun block(id: Int): ByteArray {
            val n = (newest.index * SLOT_SECTORS until (newest.index + 1) * SLOT_SECTORS)
                .first { footer(data, it).id == id }
            return sector(data, n).copyOfRange(0, SECTOR_ID_OFFSET)
        }
        val sb2 = block(0); val trainerId = sb2.copyOfRange(0x0a, 0x0e)
        val records = mutableListOf<ByteArray>()
        val sb1 = block(1); val partyCount = minOf(sb1[0x34].toInt() and 0xff, 6)
        repeat(partyCount) { records += sb1.copyOfRange(0x38 + it * 100, 0x38 + (it + 1) * 100) }
        val layout = newest.layouts.first()
        val storage = (5 until SLOT_SECTORS).fold(ByteArray(0)) { acc, id -> acc + block(id).copyOf(chunkSize(layout, id)) }
        repeat(420) { val a = 4 + it * 80; if (a + 80 <= storage.size) records += storage.copyOfRange(a, a + 80) }
        return records.filter { it.size >= 80 && it.copyOfRange(4, 8).contentEquals(trainerId) && !it.copyOfRange(0, 4).all { b -> b == 0.toByte() } }
            .groupingBy { it[0x12].toInt() and 0xff }.eachCount().maxByOrNull { it.value }?.key
    }

    private fun newer(a: Slot, b: Slot): Slot {
        val aCounter = requireNotNull(a.counter)
        val bCounter = requireNotNull(b.counter)
        return if (((bCounter + 1) and 0xffffffffL) > ((aCounter + 1) and 0xffffffffL)) b else a
    }

    /** Inflate one save-backup response into [out], returning the next output offset. */
    fun inflate(tokens: ByteArray, out: ByteArray, offset: Int): Int {
        var inputAt = 0
        var outAt = offset
        while (inputAt < tokens.size) {
            val token = tokens[inputAt++].toInt() and 0xff
            val length = if (token < 0x80) token + 1 else token - 0x80 + 3
            val encodedLength = if (token < 0x80) length else 1
            if (outAt + length > out.size || inputAt + encodedLength > tokens.size) {
                throw SaveException("the console sent a save block that does not fit")
            }
            if (token < 0x80) {
                tokens.copyInto(out, outAt, inputAt, inputAt + length)
                inputAt += length
            } else {
                out.fill(tokens[inputAt++], outAt, outAt + length)
            }
            outAt += length
        }
        return outAt
    }

    internal fun chunkSize(layout: Layout, id: Int): Int = chunkSizes.getValue(layout)[id]

    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, at: Int): Long = (0 until 4).fold(0L) { value, i ->
        value or ((data[at + i].toInt() and 0xff).toLong() shl (8 * i))
    }
    private fun putU32(data: ByteArray, at: Int, value: Long) = repeat(4) { i -> data[at + i] = (value ushr (8 * i)).toByte() }
}
