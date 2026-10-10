package com.dedhapp3n.pokeldn.frlg

enum class FrlgGiftStage {
    WAITING_CLIENT, READING_CARTRIDGE, PREPARING_BOOST, SENDING_BOOST,
    WAITING_RESULT, CLOSING, COMPLETED, FAILED
}

internal data class FrlgGiftSnapshot(
    val stage: FrlgGiftStage,
    val cartridge: FrlgCartridge?,
    val gameCode: String?,
    val detail: String?,
    val disconnectRequested: Boolean,
)

/** Exact upstream buffer-script Mystery Gift flow used by the noclip resident installer. */
internal class FrlgMysteryGiftEngine(
    private val linkCartridge: FrlgCartridge,
    startupStandbyCount: Int = 0,
) {
    var stage = FrlgGiftStage.WAITING_CLIENT
        private set
    private var detail: String? = "Waiting for Mystery Gift client"
    private var idleFrames = 0
    private val words = ArrayDeque<IntArray>()
    private val blocks = ArrayDeque<Pair<ByteArray, Int>>()
    private var sender: RfuBlockSender? = null
    private var gap = 0
    private val receiver = RfuBlockReceiver()
    private val messageReceiver = MgMessageReceiver()
    private var expecting: Int? = null
    private var afterSend: (() -> Unit)? = null
    private var gameCode: String? = null
    private var detectedCartridge: FrlgCartridge? = null
    private var closeRetry = 0
    private var closeGrace: Int? = null
    var disconnectRequested = false
        private set
    private var operationSucceeded = false
    private var terminalDetail: String? = null

    init {
        repeat(4) { words += intArrayOf(FrlgRfu.READY_EXIT_STANDBY, startupStandbyCount, 0, 0, 0, 0, 0) }
    }

    fun receive(slot: ByteArray) {
        if (slot.all { it == 0.toByte() }) {
            if (stage == FrlgGiftStage.WAITING_CLIENT && ++idleFrames >= 20) beginGameData()
            return
        }
        idleFrames = 0
        val op = le16Mg(slot) and FrlgRfu.MASK
        if (op == FrlgRfu.READY_CLOSE_LINK) {
            val count = if (slot.size >= 4) le16Mg(slot, 2) else 0
            repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, count, 0, 0, 0, 0, 0) }
            if (stage == FrlgGiftStage.CLOSING && closeGrace == null) closeGrace = 300
            return
        }
        receiver.receive(slot)?.let(::onRfuBlock)
    }

    fun tick(): IntArray {
        if (stage == FrlgGiftStage.CLOSING && !disconnectRequested) {
            closeGrace?.let {
                if (it <= 1) disconnectRequested = true else closeGrace = it - 1
            }
            if (closeGrace == null && --closeRetry <= 0) {
                queueClose()
                closeRetry = 60
            }
        }
        if (words.isNotEmpty()) return words.removeFirst()
        if (gap > 0) { gap--; return IntArray(7) }
        if (sender == null && blocks.isNotEmpty()) {
            val (block, repeats) = blocks.removeFirst()
            sender = RfuBlockSender(block, repeats)
        }
        sender?.let { current ->
            val result = current.tick()
            if (current.done) {
                sender = null
                gap = 36
                if (blocks.isEmpty()) {
                    val continuation = afterSend
                    afterSend = null
                    continuation?.invoke()
                }
            }
            return result
        }
        return IntArray(7)
    }

    fun snapshot() = FrlgGiftSnapshot(stage, detectedCartridge ?: linkCartridge, gameCode, detail, disconnectRequested)

    private fun beginGameData() {
        stage = FrlgGiftStage.READING_CARTRIDGE
        detail = "Reading cartridge"
        sendMessage(16, CLIENT_SEND_GAME_DATA) { expect(17) }
    }

    private fun onRfuBlock(block: ByteArray) {
        val wanted = expecting ?: return
        val message = try { messageReceiver.receive(block, wanted) } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid Mystery Gift message")
            return
        } ?: return
        expecting = null
        when (wanted) {
            17 -> onGameData(message)
            19 -> onInstallResult(message)
            20 -> beginClose()
        }
    }

    private fun onGameData(data: ByteArray) {
        if (data.size < 0x64 || le32Mg(data, 0) != 0x101L ||
            le16Mg(data, 4) and 1 == 0 || le32Mg(data, 8) and 1L == 0L ||
            le16Mg(data, 12) and 1 == 0 || le32Mg(data, 16) and 15L == 0L) {
            fail("Console Mystery Gift game data is invalid")
            return
        }
        val code = data.copyOfRange(0x5c, 0x60).toString(Charsets.US_ASCII)
        val payload = WalkThroughWallsPayloads.forGameCode(code)
        if (payload == null) {
            fail("Unsupported FRLG cartridge: $code")
            return
        }
        val expectedGame = if (code.startsWith("BPR")) "FireRed" else if (code.startsWith("BPG")) "LeafGreen" else null
        val language = mapOf('F' to "French", 'E' to "English", 'S' to "Spanish", 'D' to "German",
            'I' to "Italian", 'J' to "Japanese")[code.lastOrNull()]
        val versionCode = (le32Mg(data, 16) and 15).toInt()
        val expectedVersionCode = if (expectedGame == "FireRed") 1 else 2
        if (expectedGame != linkCartridge.game || language != linkCartridge.language || versionCode != expectedVersionCode) {
            fail("Cartridge identity mismatch: ${linkCartridge.game} / $code")
            return
        }
        gameCode = code
        detectedCartridge = FrlgCartridge(expectedGame, language, linkCartridge.version, linkCartridge.languageId)
        stage = FrlgGiftStage.PREPARING_BOOST
        detail = "${linkCartridge.game} / ${linkCartridge.language} detected"
        sendMessage(16, CLIENT_RUN_BUFFER) {
            stage = FrlgGiftStage.SENDING_BOOST
            detail = "Sending Walk Through Walls"
            sendMessage(25, payload, repeats = 3) {
                stage = FrlgGiftStage.WAITING_RESULT
                detail = "Waiting for console install result"
                expect(19)
            }
        }
    }

    private fun onInstallResult(data: ByteArray) {
        val status = if (data.size >= 4) le32Mg(data).toInt() else 0xbad0bad0.toInt()
        val success = status != 0xbad0bad0.toInt()
        operationSucceeded = success
        terminalDetail = if (success) "Walk Through Walls installed" else "Console refused the resident install"
        detail = terminalDetail
        val script = if (success) CLIENT_SUCCESS else CLIENT_FAILURE
        val message = if (success) SUCCESS_MESSAGE else FAILURE_MESSAGE
        sendMessage(16, script) {
            sendMessage(21, message) { expect(20) }
        }
    }

    private fun beginClose() {
        stage = FrlgGiftStage.CLOSING
        detail = "Closing Mystery Gift link"
        queueClose()
        closeRetry = 60
    }

    private fun queueClose() { repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, 0, 0, 0, 0, 0, 0) } }
    private fun expect(ident: Int) { expecting = ident; messageReceiver.reset() }
    private fun sendMessage(ident: Int, payload: ByteArray, repeats: Int = 2, done: () -> Unit) {
        MgMessage.build(ident, payload).forEach { blocks += it to repeats }
        afterSend = done
    }
    private fun fail(message: String) { stage = FrlgGiftStage.FAILED; detail = message }

    fun markDisconnected() {
        stage = if (operationSucceeded) FrlgGiftStage.COMPLETED else FrlgGiftStage.FAILED
        detail = terminalDetail ?: detail
    }

    companion object {
        private val CLIENT_SEND_GAME_DATA = hexMg("0800000000000000030000000000000002000000100000000400000000000000")
        private val CLIENT_RUN_BUFFER = hexMg("020000001900000015000000000000000e00000000000000030000000000000002000000100000000400000000000000")
        private val CLIENT_SUCCESS = hexMg("02000000150000000c000000000000001400000000000000010000000d000000")
        private val CLIENT_FAILURE = hexMg("02000000150000000c000000000000001400000000000000010000000e000000")
        private val SUCCESS_MESSAGE = hexMg("cedcd900d7e3d8d900e6d5e200d5e2d800e6d9d5d800ede3e9e6fececcbbc3c8bfcc00c3be00d7e3e6e6d9d7e8e0edadff")
        private val FAILURE_MESSAGE = hexMg("cedcd900d7e3d8d900e6d5e200d6e9e800e6d9d5d800e8dcd9feebe6e3e2db00ead5e0e9d9adff")
    }
}

private class RfuBlockReceiver {
    private var count = 0
    private var flags = 0
    private var buffer = ByteArray(0)
    fun receive(slot: ByteArray): ByteArray? {
        if (slot.size < 2) return null
        val word = le16Mg(slot)
        when (word and FrlgRfu.MASK) {
            FrlgRfu.SEND_BLOCK_INIT -> {
                val next = le16Mg(slot, 2)
                if (next !in 1..32) return null
                if (next != count || flags == (1 shl count) - 1) {
                    count = next; flags = 0; buffer = ByteArray(count * 12)
                }
            }
            FrlgRfu.SEND_BLOCK -> {
                val index = word and 31
                if (index >= count || buffer.isEmpty()) return null
                slot.copyOfRange(2, minOf(slot.size, 14)).copyOf(12).copyInto(buffer, index * 12)
                flags = flags or (1 shl index)
                if (flags == (1 shl count) - 1) {
                    val result = buffer.copyOf(); count = 0; flags = 0; buffer = ByteArray(0); return result
                }
            }
        }
        return null
    }
}

private class RfuBlockSender(private val data: ByteArray, private val repeat: Int) {
    private val count = maxOf(1, (data.size + 11) / 12)
    private var init = 0
    private var index = 0
    private var repeated = 0
    var done = false
        private set
    fun tick(): IntArray {
        if (done) return IntArray(7)
        if (init++ < 4) return FrlgRfu.blockInit(count)
        val start = index * 12
        val words = FrlgRfu.blockFragment(index, data.copyOfRange(start, minOf(data.size, start + 12)))
        if (++repeated >= repeat) {
            repeated = 0
            if (++index >= count) done = true
        }
        return words
    }
}

private object MgMessage {
    fun build(ident: Int, payload: ByteArray): List<ByteArray> {
        require(payload.isNotEmpty() && payload.size <= 1024)
        val header = ByteArray(6)
        putLe16Mg(header, 0, ident); putLe16Mg(header, 2, crc16Mg(payload)); putLe16Mg(header, 4, payload.size)
        return listOf(header) + payload.asList().chunked(252).map { it.toByteArray() }
    }
}

private class MgMessageReceiver {
    private var ident: Int? = null
    private var crc = 0
    private var size = 0
    private val data = ArrayList<Byte>()
    fun reset() { ident = null; crc = 0; size = 0; data.clear() }
    fun receive(block: ByteArray, expected: Int): ByteArray? {
        if (ident == null) {
            require(block.size >= 6) { "Mystery Gift header is too short" }
            ident = le16Mg(block); crc = le16Mg(block, 2); size = le16Mg(block, 4).let { if (it == 0) 1024 else it }
            require(ident == expected) { "Expected Mystery Gift ident $expected, received $ident" }
            require(size in 1..1024) { "Invalid Mystery Gift message size $size" }
            return null
        }
        val take = minOf(size - data.size, 252)
        require(block.size >= take) { "Mystery Gift payload block is too short" }
        data.addAll(block.take(take))
        if (data.size < size) return null
        val result = data.toByteArray()
        require(crc16Mg(result) == crc) { "Mystery Gift CRC mismatch" }
        reset()
        return result
    }
}

private fun crc16Mg(data: ByteArray): Int {
    var crc = 0x1121
    data.forEach { byte ->
        crc = crc xor (byte.toInt() and 0xff)
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
    }
    return crc.inv() and 0xffff
}
private fun le16Mg(data: ByteArray, offset: Int = 0) = (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)
private fun le32Mg(data: ByteArray, offset: Int = 0) = (0 until 4).fold(0L) { v, i -> v or ((data[offset + i].toInt() and 255).toLong() shl (8 * i)) }
private fun putLe16Mg(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
private fun hexMg(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
