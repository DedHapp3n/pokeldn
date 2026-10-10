package com.dedhapp3n.pokeldn.frlg

internal data class FrlgCartridge(
    val game: String,
    val language: String,
    val version: Int,
    val languageId: Int,
)

internal data class FrlgLinkPlayer(val version: Int, val language: Int, val trainerId: Long) {
    val cartridge: FrlgCartridge?
        get() {
            val game = when (version) { 0x4004 -> "FireRed"; 0x4005 -> "LeafGreen"; else -> return null }
            val languageName = LANGUAGES[language] ?: return null
            return FrlgCartridge(game, languageName, version, language)
        }

    companion object {
        private val LANGUAGES = mapOf(1 to "Japanese", 2 to "English", 3 to "French",
            4 to "Italian", 5 to "German", 7 to "Spanish")
    }
}

internal enum class FrlgLinkStage { EXCHANGING_IDS, RECEIVING_PLAYER, SENDING_HOST, WAITING_STANDBY, ESTABLISHED, FAILED }

/** The Task_PlayerExchange subset used before the Mystery Gift client starts. */
internal class FrlgLinkPlayerExchange {
    var stage = FrlgLinkStage.EXCHANGING_IDS
        private set
    var child: FrlgLinkPlayer? = null
        private set
    var error: String? = null
        private set
    private val commands = ArrayDeque<IntArray>()
    private var receiveCount = 0
    private var receiveOwner = 0
    private var receiveFlags = 0
    private var receiveBuffer = ByteArray(0)
    private var hostInitSends = 0
    private var hostFragment = 0
    private var standby: Int? = null
    val standbyCount: Int? get() = standby

    init {
        repeat(8) { commands += FrlgRfu.playerIds() }
        commands += FrlgRfu.blockRequest()
    }

    fun receive(slot: ByteArray) {
        if (slot.size < 2 || slot.all { it == 0.toByte() }) return
        val word0 = le16Lp(slot)
        when (word0 and FrlgRfu.MASK) {
            FrlgRfu.SEND_BLOCK_INIT -> {
                val count = le16Lp(slot, 2)
                val owner = slot.getOrElse(4) { 0 }.toInt() and 0xff
                if (count != receiveCount || receiveBuffer.isEmpty()) {
                    receiveCount = count
                    receiveOwner = owner
                    receiveFlags = 0
                    receiveBuffer = ByteArray(count.coerceAtLeast(0) * 12)
                }
                stage = FrlgLinkStage.RECEIVING_PLAYER
                if (owner and 0x7f != 1) repeat(8) { commands += FrlgRfu.playerIds() }
            }
            FrlgRfu.SEND_BLOCK -> {
                val index = word0 and 31
                if (index < receiveCount && receiveBuffer.isNotEmpty()) {
                    slot.copyOfRange(2, minOf(14, slot.size)).copyOf(12)
                        .copyInto(receiveBuffer, index * 12)
                    receiveFlags = receiveFlags or (1 shl index)
                    if (receiveCount in 1..30 && receiveFlags == (1 shl receiveCount) - 1) finishChildBlock()
                }
            }
            FrlgRfu.READY_EXIT_STANDBY -> {
                standby = if (slot.size >= 4) le16Lp(slot, 2) else 0
                if (stage == FrlgLinkStage.WAITING_STANDBY) stage = FrlgLinkStage.ESTABLISHED
                repeat(4) { commands += exitStandby(standby!!) }
            }
        }
    }

    fun tick(): IntArray {
        if (commands.isNotEmpty()) return commands.removeFirst()
        if (stage == FrlgLinkStage.SENDING_HOST) {
            if (hostInitSends++ < 4) return FrlgRfu.blockInit(17)
            val start = hostFragment * 12
            val words = FrlgRfu.blockFragment(hostFragment, HOST_BLOCK_200.copyOfRange(start, start + 12))
            hostFragment++
            if (hostFragment == 17) {
                stage = if (standby != null) FrlgLinkStage.ESTABLISHED else FrlgLinkStage.WAITING_STANDBY
            }
            return words
        }
        return IntArray(7)
    }

    private fun finishChildBlock() {
        if (receiveCount != 17 || receiveOwner and 0x7f != 1) {
            fail("Invalid LinkPlayer block owner/count")
            return
        }
        if (!validMagic(receiveBuffer, 0) || !validMagic(receiveBuffer, 44)) {
            fail("Invalid LinkPlayer GameFreak magic")
            return
        }
        val struct = 16
        child = FrlgLinkPlayer(
            version = le16Lp(receiveBuffer, struct),
            trainerId = le32Lp(receiveBuffer, struct + 4),
            language = le16Lp(receiveBuffer, struct + 26),
        )
        if (child!!.cartridge == null) {
            fail("Unsupported FRLG cartridge version/language")
            return
        }
        stage = FrlgLinkStage.SENDING_HOST
    }

    private fun fail(message: String) { error = message; stage = FrlgLinkStage.FAILED }

    companion object {
        private val MAGIC = "GameFreak inc.".encodeToByteArray()
        // Upstream LinkPlayer(name="EMU", FireRed, English), padded to the RFU 200 byte buffer.
        private val HOST_BLOCK_200 = hexLp(
            "47616d65467265616b20696e632e0000044000802288ed47bfc7cfffffffffff" +
                "0000000000000000020047616d65467265616b20696e632e0000").copyOf(204)

        private fun validMagic(data: ByteArray, offset: Int): Boolean =
            data.size >= offset + MAGIC.size && data.copyOfRange(offset, offset + MAGIC.size).contentEquals(MAGIC)

        private fun exitStandby(count: Int) = intArrayOf(FrlgRfu.READY_EXIT_STANDBY, count, 0, 0, 0, 0, 0)
    }
}

private fun le16Lp(data: ByteArray, offset: Int = 0) =
    (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
private fun le32Lp(data: ByteArray, offset: Int): Long = (0 until 4).fold(0L) { value, index ->
    value or ((data[offset + index].toInt() and 0xff).toLong() shl (8 * index))
}
private fun hexLp(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
