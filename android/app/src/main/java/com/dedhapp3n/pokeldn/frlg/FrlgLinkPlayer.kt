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

internal data class FrlgLinkExchangeSnapshot(
    val childBlockReceived: Boolean,
    val childMpId: Int?,
    val hostInitCount: Int,
    val hostFragmentIndexes: String,
    val hostBlockComplete: Boolean,
    val standbyReceived: Boolean,
    val standbyLatched: Boolean,
    val standbyEchoesSent: Int,
    val barrierComplete: Boolean,
)

/** Task_PlayerExchange behavior shared with HostMysteryGiftEngine and block.BlockSender. */
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
    private var childMpId: Int? = null
    private var hostInitSends = 0
    private var hostFragment = 0
    private var hostFragmentRepeat = 0
    private val hostFragmentSends = IntArray(HOST_FRAGMENT_COUNT)
    private var hostBlockComplete = false
    private var standby: Int? = null
    private var standbyReceived = false
    private var standbyLatched = false
    private var standbyEchoesSent = 0
    val standbyCount: Int? get() = standby
    val readyForGift: Boolean get() = stage == FrlgLinkStage.ESTABLISHED && commands.isEmpty()

    init {
        repeat(PLAYER_IDS_REPEAT) { commands += FrlgRfu.playerIds() }
        commands += FrlgRfu.blockRequest() // Exactly once: a duplicate restarts the child's send.
    }

    fun receive(slot: ByteArray) {
        if (slot.size < 2 || slot.all { it == 0.toByte() }) return
        val word0 = le16Lp(slot)
        when (word0 and FrlgRfu.MASK) {
            FrlgRfu.SEND_BLOCK_INIT -> {
                val count = le16Lp(slot, 2)
                val owner = slot.getOrElse(4) { 0 }.toInt() and 0xff
                childMpId = owner and 0x7f
                // The child can repeat its completed transfer until row one echoes it. Upstream
                // keeps receiving those repeats without moving the host sender back a phase.
                if (child != null) return
                if (count != receiveCount || receiveBuffer.isEmpty()) {
                    receiveCount = count
                    receiveOwner = owner
                    receiveFlags = 0
                    receiveBuffer = ByteArray(count.coerceAtLeast(0) * FRAGMENT_SIZE)
                }
                stage = FrlgLinkStage.RECEIVING_PLAYER
                if (childMpId != 1) repeat(PLAYER_IDS_REPEAT) { commands += FrlgRfu.playerIds() }
            }
            FrlgRfu.SEND_BLOCK -> {
                if (child != null) return
                val index = word0 and 31
                if (index < receiveCount && receiveBuffer.isNotEmpty()) {
                    slot.copyOfRange(2, minOf(14, slot.size)).copyOf(FRAGMENT_SIZE)
                        .copyInto(receiveBuffer, index * FRAGMENT_SIZE)
                    receiveFlags = receiveFlags or (1 shl index)
                    if (receiveCount in 1..30 && receiveFlags == (1 shl receiveCount) - 1) finishChildBlock()
                }
            }
            FrlgRfu.READY_EXIT_STANDBY -> onStandby(if (slot.size >= 4) le16Lp(slot, 2) else 0)
        }
    }

    fun tick(): IntArray {
        if (commands.isNotEmpty()) {
            val command = commands.removeFirst()
            if (command[0] and FrlgRfu.MASK == FrlgRfu.READY_EXIT_STANDBY) standbyEchoesSent++
            return command
        }
        if (stage == FrlgLinkStage.SENDING_HOST) {
            if (hostInitSends < HOST_INIT_REPEAT) {
                hostInitSends++
                return FrlgRfu.blockInit(HOST_FRAGMENT_COUNT)
            }
            val index = hostFragment
            val start = index * FRAGMENT_SIZE
            val words = FrlgRfu.blockFragment(
                index,
                HOST_BLOCK_200.copyOfRange(start, minOf(HOST_BLOCK_200.size, start + FRAGMENT_SIZE)),
            )
            hostFragmentSends[index]++
            hostFragmentRepeat++
            if (hostFragmentRepeat == BLOCK_FRAGMENT_REPEAT) {
                hostFragmentRepeat = 0
                hostFragment++
                if (hostFragment == HOST_FRAGMENT_COUNT) finishHostBlock()
            }
            return words
        }
        return IntArray(7)
    }

    fun snapshot() = FrlgLinkExchangeSnapshot(
        childBlockReceived = child != null,
        childMpId = childMpId,
        hostInitCount = hostInitSends,
        hostFragmentIndexes = hostFragmentSends.withIndex()
            .filter { it.value > 0 }
            .joinToString(",") { "${it.index}x${it.value}" },
        hostBlockComplete = hostBlockComplete,
        standbyReceived = standbyReceived,
        standbyLatched = standbyLatched,
        standbyEchoesSent = standbyEchoesSent,
        barrierComplete = stage == FrlgLinkStage.ESTABLISHED,
    )

    private fun finishChildBlock() {
        if (receiveCount != HOST_FRAGMENT_COUNT || receiveOwner and 0x7f != 1) {
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

    private fun onStandby(count: Int) {
        standbyReceived = true
        repeat(STANDBY_ECHO_REPEAT) { commands += exitStandby(count) }
        if (child == null) return // Upstream records but never latches a premature standby.
        standby = count
        if (hostBlockComplete) completeBarrier() else standbyLatched = true
    }

    private fun finishHostBlock() {
        hostBlockComplete = true
        if (standby != null) completeBarrier() else stage = FrlgLinkStage.WAITING_STANDBY
    }

    private fun completeBarrier() {
        standbyLatched = false
        stage = FrlgLinkStage.ESTABLISHED
    }

    private fun fail(message: String) { error = message; stage = FrlgLinkStage.FAILED }

    companion object {
        private const val FRAGMENT_SIZE = 12
        private const val HOST_FRAGMENT_COUNT = 17
        private const val PLAYER_IDS_REPEAT = 8
        private const val HOST_INIT_REPEAT = 4
        private const val BLOCK_FRAGMENT_REPEAT = 2
        private const val STANDBY_ECHO_REPEAT = 4
        private val MAGIC = "GameFreak inc.".encodeToByteArray()
        // Upstream LinkPlayer(name="EMU", FireRed, English), padded to the 200-byte RFU buffer.
        private val HOST_BLOCK_200 = hexLp(
            "47616d65467265616b20696e632e0000044000802288ed47bfc7cfffffffffff" +
                "00000000000000000000020047616d65467265616b20696e632e0000").copyOf(200)

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
