package com.dedhapp3n.pokeldn.frlg

enum class FrlgSaveBackupStage {
    WAITING_CLIENT,
    READING_CARTRIDGE,
    RECEIVING_SAVE,
    WAITING_READY_END,
    CLOSING,
    COMPLETED,
    FAILED,
}

enum class FrlgSaveDataResult { INCOMPLETE, COMPLETE_VALID, COMPLETE_WITH_WARNING }
enum class FrlgSaveCloseResult { PENDING, NORMAL, PEER_CLOSED, CLEANUP_WARNING, TRANSPORT_FAILURE }

internal data class FrlgSaveBackupGiftSnapshot(
    val stage: FrlgSaveBackupStage,
    val cartridge: FrlgCartridge,
    val gameCode: String?,
    val bytesReceived: Int,
    val totalBytes: Int,
    val hasWholeSlot: Boolean?,
    val detail: String?,
    val disconnectRequested: Boolean,
    val backupSucceeded: Boolean,
    val nonSavingCloseEntered: Boolean,
    val nonSavingCloseConfirmed: Boolean,
    val save: ByteArray?,
    val dataResult: FrlgSaveDataResult,
    val closeResult: FrlgSaveCloseResult,
    val persisted: Boolean,
)

/** RFU/Mystery Gift adapter for [FrlgSaveBackupEngine], using the proven WTW block pacing. */
internal class FrlgSaveBackupGiftEngine(
    private val linkCartridge: FrlgCartridge,
    startupStandbyCount: Int = 0,
    echoStartupStandby: Boolean = true,
    private val persistCompleted: (FrlgCompletedBackup) -> Unit = {},
) {
    private val core = FrlgSaveBackupEngine(linkCartridge.game, linkCartridge.language)
    private var stage = FrlgSaveBackupStage.WAITING_CLIENT
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
    private var closeRetry = 0
    private var closeGrace: Int? = null
    private var nonSavingCloseEntered = false
    private var nonSavingCloseConfirmed = false
    private var terminalDetail: String? = null
    private var completedSave: ByteArray? = null
    private var dataResult = FrlgSaveDataResult.INCOMPLETE
    private var closeResult = FrlgSaveCloseResult.PENDING
    private var persisted = false
    // Upstream carries the LinkPlayer standby sequence into READY_CLOSE_LINK.
    private var exitCount = startupStandbyCount + 1
    var disconnectRequested = false
        private set

    init {
        if (echoStartupStandby) {
            repeat(4) { words += intArrayOf(FrlgRfu.READY_EXIT_STANDBY, startupStandbyCount, 0, 0, 0, 0, 0) }
        }
    }

    fun receive(slot: ByteArray) {
        if (slot.all { it == 0.toByte() }) {
            if (stage == FrlgSaveBackupStage.WAITING_CLIENT && ++idleFrames >= 20) {
                stage = FrlgSaveBackupStage.READING_CARTRIDGE
                detail = "Reading cartridge"
                pumpCore()
            }
            return
        }
        idleFrames = 0
        val op = u16Backup(slot) and FrlgRfu.MASK
        if (op == FrlgRfu.READY_CLOSE_LINK) {
            val count = if (slot.size >= 4) u16Backup(slot, 2) else 0
            exitCount = maxOf(exitCount, count)
            repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, count, 0, 0, 0, 0, 0) }
            if (stage == FrlgSaveBackupStage.CLOSING) {
                nonSavingCloseConfirmed = true
                if (closeGrace == null) closeGrace = 300
            }
            return
        }
        receiver.receive(slot)?.let(::onRfuBlock)
    }

    fun tick(): IntArray {
        if (stage == FrlgSaveBackupStage.CLOSING && !disconnectRequested) {
            closeGrace?.let { if (it <= 1) disconnectRequested = true else closeGrace = it - 1 }
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

    fun snapshot(): FrlgSaveBackupGiftSnapshot {
        val coreState = core.snapshot()
        return FrlgSaveBackupGiftSnapshot(
            stage = stage,
            cartridge = FrlgCartridge(
                coreState.game ?: linkCartridge.game,
                coreState.language ?: linkCartridge.language,
                linkCartridge.version,
                linkCartridge.languageId,
            ),
            gameCode = coreState.gameCode,
            bytesReceived = coreState.bytesReceived,
            totalBytes = coreState.totalBytes,
            hasWholeSlot = coreState.hasWholeSlot,
            detail = detail,
            disconnectRequested = disconnectRequested,
            backupSucceeded = completedSave != null,
            nonSavingCloseEntered = nonSavingCloseEntered,
            nonSavingCloseConfirmed = nonSavingCloseConfirmed,
            save = completedSave?.copyOf(),
            dataResult = dataResult,
            closeResult = closeResult,
            persisted = persisted,
        )
    }

    fun onPeerLeft() {
        if (stage == FrlgSaveBackupStage.COMPLETED || stage == FrlgSaveBackupStage.FAILED) return
        if (completedSave != null) {
            stage = FrlgSaveBackupStage.COMPLETED
            closeResult = FrlgSaveCloseResult.PEER_CLOSED
            detail = if (nonSavingCloseEntered) terminalDetail ?: "Backup saved successfully"
                else "Backup saved - connection closed unexpectedly"
        } else {
            stage = FrlgSaveBackupStage.FAILED
            detail = terminalDetail ?: "Console disconnected before the save backup completed"
        }
    }

    fun markDisconnected() {
        if (completedSave != null) {
            stage = FrlgSaveBackupStage.COMPLETED
            closeResult = if (nonSavingCloseConfirmed) FrlgSaveCloseResult.NORMAL
                else FrlgSaveCloseResult.CLEANUP_WARNING
            detail = terminalDetail ?: "Backup saved successfully"
        } else onPeerLeft()
    }

    private fun onRfuBlock(block: ByteArray) {
        val wanted = expecting ?: return
        val message = try {
            messageReceiver.receive(block, wanted)
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid Mystery Gift message")
            return
        } ?: return
        expecting = null
        if (wanted == MG_LINKID_READY_END) {
            beginClose()
        } else {
            core.onReceived(wanted, message)
            pumpCore()
        }
    }

    private fun pumpCore() {
        when (val action = core.action) {
            is FrlgSaveBackupEngine.Action.Send -> {
                val coreState = core.snapshot()
                stage = if (coreState.gameCode == null) FrlgSaveBackupStage.READING_CARTRIDGE
                    else FrlgSaveBackupStage.RECEIVING_SAVE
                detail = coreState.detail
                sendMessage(action.ident, action.payload) {
                    core.onSent()
                    pumpCore()
                }
            }
            is FrlgSaveBackupEngine.Action.Receive -> expect(action.ident)
            FrlgSaveBackupEngine.Action.Done -> {
                val coreState = core.snapshot()
                if (coreState.stage == FrlgSaveBackupEngine.Stage.COMPLETED) {
                    val frozen = requireNotNull(core.save()).copyOf()
                    completedSave = frozen
                    dataResult = if (coreState.hasWholeSlot == true) FrlgSaveDataResult.COMPLETE_VALID
                        else FrlgSaveDataResult.COMPLETE_WITH_WARNING
                    terminalDetail = if (coreState.hasWholeSlot == true) "Save backup complete"
                    else "Save backup complete; neither save slot is whole"
                    try {
                        persistCompleted(FrlgCompletedBackup(
                            save = frozen.copyOf(),
                            game = coreState.game ?: linkCartridge.game,
                            language = coreState.language ?: linkCartridge.language,
                            gameCode = coreState.gameCode,
                            hasWholeSlot = coreState.hasWholeSlot == true,
                        ))
                        persisted = true
                    } catch (error: Exception) {
                        stage = FrlgSaveBackupStage.COMPLETED
                        closeResult = FrlgSaveCloseResult.CLEANUP_WARNING
                        detail = "Backup complete, but private storage failed: ${error.message ?: "unknown error"}"
                        return
                    }
                    stage = FrlgSaveBackupStage.WAITING_READY_END
                    detail = "Closing Mystery Gift without saving"
                    sendMessage(FrlgSaveBackupEngine.MG_LINKID_CLIENT_SCRIPT, NON_SAVING_END_SCRIPT) {
                        sendMessage(MG_LINKID_DYNAMIC_MSG, BACKED_UP_MESSAGE) { expect(MG_LINKID_READY_END) }
                    }
                } else fail(coreState.detail ?: "Save backup failed")
            }
        }
    }

    private fun beginClose() {
        nonSavingCloseEntered = true
        stage = FrlgSaveBackupStage.CLOSING
        detail = "Save copied; closing without writing cartridge data"
        queueClose()
        closeRetry = 60
    }

    private fun queueClose() {
        repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, exitCount, 0, 0, 0, 0, 0) }
    }

    private fun expect(ident: Int) {
        expecting = ident
        messageReceiver.reset()
    }

    private fun sendMessage(ident: Int, payload: ByteArray, done: () -> Unit) {
        MgMessage.build(ident, payload).forEach { blocks += it to 2 }
        afterSend = done
    }

    private fun fail(message: String) {
        if (completedSave != null) {
            stage = FrlgSaveBackupStage.COMPLETED
            closeResult = FrlgSaveCloseResult.TRANSPORT_FAILURE
            detail = "Backup saved - $message"
        } else {
            stage = FrlgSaveBackupStage.FAILED
            detail = message
        }
    }

    companion object {
        private const val MG_LINKID_READY_END = 20
        private const val MG_LINKID_DYNAMIC_MSG = 21
        // CLI_RECV dynamic message, COPY_MSG, SEND_READY_END, RETURN BUFFER_FAILURE (non-saving).
        private val NON_SAVING_END_SCRIPT = hexBackupGift(
            "02000000150000000c000000000000001400000000000000010000000e000000"
        )
        private val BACKED_UP_MESSAGE = hexBackupGift(
            "d3e3e9e600e7d5ead900ebd5e700d7e3e4ddd9d800e8e3fee8dcd900d7e3e1e4e9e8d9e6adff"
        )
        internal fun nonSavingEndScript() = NON_SAVING_END_SCRIPT.copyOf()
        internal fun backedUpMessage() = BACKED_UP_MESSAGE.copyOf()
    }
}

private fun u16Backup(data: ByteArray, at: Int = 0) =
    (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)

private fun hexBackupGift(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
