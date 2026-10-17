package com.dedhapp3n.pokeldn.frlg

/**
 * Pure, operation-scoped core for upstream's SaveBackupServer contract.
 * An RFU/Mystery Gift adapter executes [action], then calls [onSent] or [onReceived].
 * This class has no restore/write action and publishes the image only after all 128 KiB arrive.
 * Persisting and resuming partial images is deliberately left for a later Android storage milestone.
 */
internal class FrlgSaveBackupEngine(
    private val expectedGame: String? = null,
    private val expectedLanguage: String? = null,
) {
    enum class Stage { REQUESTING_GAME_DATA, TRANSFERRING, COMPLETED, FAILED }

    sealed interface Action {
        data class Send(val ident: Int, val payload: ByteArray) : Action
        data class Receive(val ident: Int) : Action
        data object Done : Action
    }

    data class Snapshot(
        val stage: Stage,
        val bytesReceived: Int,
        val totalBytes: Int = FrlgSave.SAVE_SIZE,
        val gameCode: String?,
        val game: String?,
        val language: String?,
        val hasWholeSlot: Boolean?,
        val detail: String?,
    )

    var action: Action = Action.Send(MG_LINKID_CLIENT_SCRIPT, CLIENT_SCRIPT_SEND_GAME_DATA.copyOf())
        private set
    private var stage = Stage.REQUESTING_GAME_DATA
    private var gameCode: String? = null
    private var game: String? = null
    private var language: String? = null
    private var detail: String? = "Requesting cartridge data"
    private var image = ByteArray(FrlgSave.SAVE_SIZE)
    private var at = 0
    private var passes = 0
    private var batchRemaining = 0
    private var backupCode: ByteArray? = null
    private var completedSave: ByteArray? = null
    private var summary: FrlgSave.Summary? = null

    /** Deliberately always false: backup uses the console's non-saving Mystery Gift exit. */
    val writesConsoleSave: Boolean get() = false

    fun snapshot() = Snapshot(stage, at, gameCode = gameCode, game = game, language = language,
        hasWholeSlot = summary?.sound, detail = detail)
    fun save(): ByteArray? = completedSave?.copyOf()

    fun onSent() {
        val sent = action as? Action.Send ?: return fail("onSent called without a send in flight")
        when {
            stage == Stage.REQUESTING_GAME_DATA && sent.ident == MG_LINKID_CLIENT_SCRIPT ->
                action = Action.Receive(MG_LINKID_GAME_DATA)
            stage == Stage.TRANSFERRING && sent.ident == MG_LINKID_CLIENT_SCRIPT ->
                action = Action.Send(MG_LINKID_RAM_SCRIPT, requireNotNull(backupCode).copyOf())
            stage == Stage.TRANSFERRING && sent.ident == MG_LINKID_RAM_SCRIPT ->
                action = Action.Receive(MG_LINKID_RESPONSE)
            else -> fail("Unexpected backup send completion")
        }
    }

    fun onReceived(ident: Int, payload: ByteArray) {
        val expected = (action as? Action.Receive)?.ident
            ?: return fail("onReceived called without a receive in flight")
        if (ident != expected) return fail("Expected Mystery Gift ident $expected, received $ident")
        try {
            when (ident) {
                MG_LINKID_GAME_DATA -> acceptGameData(payload)
                MG_LINKID_RESPONSE -> acceptTokens(payload)
                else -> fail("Unexpected Mystery Gift response $ident")
            }
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid save backup data")
        }
    }

    private fun acceptGameData(data: ByteArray) {
        if (!validGameData(data)) return fail("Console Mystery Gift game data is invalid")
        val code = data.copyOfRange(0x5c, 0x60).toString(Charsets.US_ASCII)
        if (code !in FrlgSaveBackupPayload.supportedGameCodes) return fail("Unsupported FRLG cartridge: $code")
        val detectedGame = when {
            code.startsWith("BPR") -> "FireRed"
            code.startsWith("BPG") -> "LeafGreen"
            else -> return fail("Unsupported FRLG cartridge: $code")
        }
        val detectedLanguage = LANGUAGES[code.lastOrNull()]
            ?: return fail("Unsupported FRLG cartridge language: $code")
        val versionCode = (u32(data, 16) and 15L).toInt()
        val expectedVersionCode = if (detectedGame == "FireRed") 1 else 2
        if (versionCode != expectedVersionCode ||
            expectedGame?.let { it != detectedGame } == true ||
            expectedLanguage?.let { it != detectedLanguage } == true) {
            return fail("Cartridge identity mismatch: $detectedGame / $detectedLanguage / $code")
        }
        gameCode = code
        game = detectedGame
        language = detectedLanguage
        backupCode = FrlgSaveBackupPayload.build(code)
        stage = Stage.TRANSFERRING
        detail = "Receiving save backup"
        beginBatch()
    }

    private fun beginBatch() {
        val count = if (passes > 0 && at > 0) {
            minOf(MAX_PASSES, ceilDiv((FrlgSave.SAVE_SIZE - at).toLong() * passes, at.toLong()).toInt() + 1)
        } else MAX_PASSES
        batchRemaining = count
        action = Action.Send(MG_LINKID_CLIENT_SCRIPT, passesScript(count))
    }

    private fun acceptTokens(tokens: ByteArray) {
        passes++
        batchRemaining--
        if (at < FrlgSave.SAVE_SIZE) at = FrlgSave.inflate(tokens, image, at)
        if (batchRemaining > 0) {
            action = Action.Receive(MG_LINKID_RESPONSE)
            return
        }
        if (at < FrlgSave.SAVE_SIZE) {
            beginBatch()
            return
        }
        completedSave = image.copyOf()
        summary = FrlgSave.describe(image)
        stage = Stage.COMPLETED
        detail = if (summary!!.sound) "Save backup complete" else "Save backup complete; neither save slot is whole"
        action = Action.Done
    }

    private fun fail(message: String) {
        stage = Stage.FAILED
        detail = message
        completedSave = null
        summary = null
        action = Action.Done
    }

    companion object {
        const val MG_LINKID_CLIENT_SCRIPT = 16
        const val MG_LINKID_GAME_DATA = 17
        const val MG_LINKID_RESPONSE = 19
        const val MG_LINKID_RAM_SCRIPT = 25
        private const val MAX_PASSES = 32
        private val LANGUAGES = mapOf(
            'F' to "French", 'E' to "English", 'S' to "Spanish", 'D' to "German",
            'I' to "Italian", 'J' to "Japanese",
        )

        private val CLIENT_SCRIPT_SEND_GAME_DATA = hexBackup(
            "0800000000000000030000000000000002000000100000000400000000000000"
        )

        /** Upstream passes_script(): receive payload, then run/send [count] responses. */
        internal fun passesScript(count: Int): ByteArray {
            require(count in 1..MAX_PASSES)
            val commands = ArrayList<Pair<Int, Int>>(2 + count * 3)
            commands += 2 to MG_LINKID_RAM_SCRIPT // CLI_RECV
            repeat(count) {
                commands += 14 to 0 // CLI_LOAD_TOSS_RESPONSE
                commands += 21 to 0 // CLI_RUN_BUFFER_SCRIPT
                commands += 3 to 0  // CLI_SEND_LOADED
            }
            commands += 2 to MG_LINKID_CLIENT_SCRIPT
            commands += 4 to 0 // CLI_COPY_RECV
            return ByteArray(commands.size * 8).also { out ->
                commands.forEachIndexed { index, (instruction, parameter) ->
                    putU32(out, index * 8, instruction)
                    putU32(out, index * 8 + 4, parameter)
                }
            }
        }

        private fun validGameData(data: ByteArray): Boolean = data.size >= 0x64 &&
            u32(data, 0) == 0x101L && u16(data, 4) and 1 != 0 &&
            u32(data, 8) and 1L != 0L && u16(data, 12) and 1 != 0 &&
            u32(data, 16) and 15L != 0L

        private fun ceilDiv(n: Long, d: Long): Long = (n + d - 1) / d
        private fun putU32(data: ByteArray, at: Int, value: Int) = repeat(4) { i -> data[at + i] = (value ushr (8 * i)).toByte() }
        private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
        private fun u32(data: ByteArray, at: Int) = (0 until 4).fold(0L) { v, i -> v or ((data[at + i].toInt() and 0xff).toLong() shl (8 * i)) }
        private fun hexBackup(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
