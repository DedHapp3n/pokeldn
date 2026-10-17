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
}
