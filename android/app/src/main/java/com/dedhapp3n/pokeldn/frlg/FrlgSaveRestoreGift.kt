package com.dedhapp3n.pokeldn.frlg

enum class FrlgSaveRestoreStage { WAITING_CLIENT, VALIDATING, READING_FOOTERS, WRITING, LOADING, CLOSING, COMPLETED, REFUSED, FAILED }

internal data class FrlgRestoreTransferDiagnostic(
    val planIndex: Int,
    val total: Int,
    val physicalSector: Int,
    val sectorId: Int?,
    val kind: FrlgSaveRestoreEngine.WriteKind,
    val tokenBytes: Int,
    val messageCount: Int,
    val blockCount: Int,
    val startedAtMillis: Long,
    val lastProgressAtMillis: Long,
    val responseAtMillis: Long?,
    val durationMillis: Long?,
    val currentMessageIndex: Int,
    val currentBlockIndex: Int,
    val blocksRemaining: Int,
    val senderActive: Boolean,
    val waitingForResponse: Boolean,
    val failureMask: Long?,
    val reliableOutstanding: Int,
    val reliableReceived: Long,
    val reliableSent: Long,
    val rfuIn: Long,
    val rfuOut: Long,
    val ethernetAccepted: Long,
    val ethernetCompleted: Long,
    val ethernetAcknowledged: Long,
    val result: String,
)

internal data class FrlgSaveRestoreGiftSnapshot(
    val stage: FrlgSaveRestoreStage,
    val cartridge: FrlgCartridge,
    val gameCode: String?,
    val sectorsWritten: Int,
    val totalSectors: Int,
    val failureMask: Long?,
    val loadResult: Int?,
    val detail: String?,
    val disconnectRequested: Boolean,
    val successful: Boolean,
    val transferDiagnostics: List<FrlgRestoreTransferDiagnostic>,
)

/** Advanced only by protocol progress, never by timer ticks or notification refreshes. */
internal class FrlgRestoreProgressWatchdog(private val timeoutMillis: Long) {
    private var lastProgress: Long? = null
    fun begin(now: Long) { lastProgress = now }
    fun progress(now: Long) { if (lastProgress != null) lastProgress = now }
    fun complete() { lastProgress = null }
    fun expired(now: Long): Boolean = lastProgress?.let { now - it >= timeoutMillis } == true
    val active: Boolean get() = lastProgress != null
    val lastProgressAtMillis: Long? get() = lastProgress
}

internal fun restorePeerDepartureCompletes(successful: Boolean, stage: FrlgSaveRestoreStage) =
    successful && stage == FrlgSaveRestoreStage.CLOSING

internal fun restoreTransportAdvanced(
    oldOutstanding: Int,
    newOutstanding: Int,
    oldReliableReceived: Long,
    newReliableReceived: Long,
    oldRfuIn: Long,
    newRfuIn: Long,
): Boolean = newReliableReceived > oldReliableReceived || newRfuIn > oldRfuIn || newOutstanding < oldOutstanding

private data class RestoreQueuedBlock(val data: ByteArray, val repeats: Int, val blockIndex: Int)

/** RFU/Mystery Gift adapter for the isolated upstream-compatible restore core. */
internal class FrlgSaveRestoreGiftEngine(
    private val linkCartridge: FrlgCartridge,
    save: ByteArray,
    startupStandbyCount: Int = 0,
    echoStartupStandby: Boolean = true,
    private val responseTimeoutMillis: Long = RESTORE_PROGRESS_TIMEOUT_MS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val core = FrlgSaveRestoreEngine(save, linkCartridge.game, linkCartridge.language)
    private var stage = FrlgSaveRestoreStage.WAITING_CLIENT
    private var detail: String? = "Waiting for Mystery Gift client"
    private var idle = 0
    private val words = ArrayDeque<IntArray>()
    private val blocks = ArrayDeque<RestoreQueuedBlock>()
    private var sender: RfuBlockSender? = null
    private var gap = 0
    private val receiver = RfuBlockReceiver()
    private val messageReceiver = MgMessageReceiver()
    private var expecting: Int? = null
    private var afterSend: (() -> Unit)? = null
    private var closeRetry = 0
    private var closeGrace: Int? = null
    private var exitCount = startupStandbyCount + 1
    var disconnectRequested = false
        private set

    private val diagnostics = mutableListOf<FrlgRestoreTransferDiagnostic>()
    private val watchdog = FrlgRestoreProgressWatchdog(responseTimeoutMillis)
    private var trackedWrite: FrlgSaveRestoreEngine.WritePlanInfo? = null
    private var currentMessageIndex = 0
    private var currentBlockIndex = 0
    private var nextBlockIndex = 0
    private var reliableOutstanding = 0
    private var reliableReceived = 0L
    private var reliableSent = 0L
    private var rfuIn = 0L
    private var rfuOut = 0L
    private var ethernetAccepted = 0L
    private var ethernetCompleted = 0L
    private var ethernetAcknowledged = 0L

    init {
        if (echoStartupStandby) repeat(4) {
            words += intArrayOf(FrlgRfu.READY_EXIT_STANDBY, startupStandbyCount, 0, 0, 0, 0, 0)
        }
    }

    fun receive(slot: ByteArray) {
        if (slot.all { it == 0.toByte() }) {
            if (stage == FrlgSaveRestoreStage.WAITING_CLIENT && ++idle >= 20) pumpCore()
            return
        }
        idle = 0
        val op = u16Restore(slot) and FrlgRfu.MASK
        if (op == FrlgRfu.READY_CLOSE_LINK) {
            val count = if (slot.size >= 4) u16Restore(slot, 2) else 0
            exitCount = maxOf(exitCount, count)
            repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, count, 0, 0, 0, 0, 0) }
            if (stage == FrlgSaveRestoreStage.CLOSING && closeGrace == null) closeGrace = 300
            return
        }
        receiver.receive(slot)?.let(::onBlock)
    }

    fun checkLiveness() = checkTimeout()

    fun tick(): IntArray {
        checkTimeout()
        if (stage == FrlgSaveRestoreStage.CLOSING && !disconnectRequested) {
            closeGrace?.let { if (it <= 1) disconnectRequested = true else closeGrace = it - 1 }
            if (closeGrace == null && --closeRetry <= 0) { queueClose(); closeRetry = 60 }
        }
        if (words.isNotEmpty()) return words.removeFirst()
        if (gap > 0) { gap--; return IntArray(7) }
        if (sender == null && blocks.isNotEmpty()) {
            val block = blocks.removeFirst()
            currentBlockIndex = block.blockIndex
            sender = RfuBlockSender(block.data, block.repeats)
            noteProgress()
        }
        sender?.let { active ->
            val output = active.tick()
            noteProgress()
            if (active.done) {
                sender = null
                gap = 36
                noteProgress()
                if (blocks.isEmpty()) {
                    val next = afterSend
                    afterSend = null
                    noteProgress()
                    next?.invoke()
                }
            }
            return output
        }
        return IntArray(7)
    }

    fun snapshot(): FrlgSaveRestoreGiftSnapshot {
        val state = core.snapshot()
        refreshTracked()
        return FrlgSaveRestoreGiftSnapshot(
            stage,
            FrlgCartridge(state.game ?: linkCartridge.game, state.language ?: linkCartridge.language,
                linkCartridge.version, linkCartridge.languageId),
            state.gameCode, state.sectorsWritten, state.totalSectors, state.failureMask, state.loadResult,
            detail, disconnectRequested, core.successful, diagnostics.toList(),
        )
    }

    fun updateTransportCounters(outstanding: Int, received: Long, sent: Long, input: Long, output: Long) {
        // Outbound retries alone are not progress. Inbound data or a shrinking window is.
        val progressed = restoreTransportAdvanced(
            reliableOutstanding, outstanding, reliableReceived, received, rfuIn, input,
        )
        reliableOutstanding = outstanding
        reliableReceived = received
        reliableSent = sent
        rfuIn = input
        rfuOut = output
        if (progressed && watchdog.active) noteProgress() else refreshTracked()
    }

    fun updateEthernetCounters(accepted: Long, completed: Long, acknowledged: Long) {
        ethernetAccepted = accepted
        ethernetCompleted = completed
        ethernetAcknowledged = acknowledged
        refreshTracked()
    }

    fun onPeerLeft() {
        if (stage in setOf(FrlgSaveRestoreStage.COMPLETED, FrlgSaveRestoreStage.FAILED, FrlgSaveRestoreStage.REFUSED)) return
        if (restorePeerDepartureCompletes(core.successful, stage)) {
            stage = FrlgSaveRestoreStage.COMPLETED
            detail = "Save restored successfully"
        } else {
            val state = core.snapshot()
            val where = state.currentWrite?.let { " while restoring sector ${it.index} of ${it.total}" } ?: ""
            core.failConnection("Connection lost$where")
            stage = FrlgSaveRestoreStage.FAILED
            detail = "Connection lost$where"
            finishTracked("peer-left", null)
            abortTransfer()
        }
    }

    fun markDisconnected() = onPeerLeft()

    private fun onBlock(block: ByteArray) {
        val wanted = expecting ?: return
        val message = try { messageReceiver.receive(block, wanted) } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid Mystery Gift message")
            return
        } ?: return
        expecting = null
        noteProgress()
        watchdog.complete()
        val mask = if (message.size >= 4 && stage in setOf(FrlgSaveRestoreStage.WRITING, FrlgSaveRestoreStage.LOADING)) {
            u32Restore(message)
        } else null
        finishTracked(if (mask == 0L) "readback-ok" else "failed", mask, responseReceived = true)
        core.onReceived(wanted, message)
        pumpCore()
    }

    private fun pumpCore() {
        val state = core.snapshot()
        stage = when (state.stage) {
            FrlgSaveRestoreEngine.Stage.REQUESTING_GAME_DATA -> FrlgSaveRestoreStage.VALIDATING
            FrlgSaveRestoreEngine.Stage.VALIDATING -> FrlgSaveRestoreStage.VALIDATING
            FrlgSaveRestoreEngine.Stage.READING_FOOTERS -> FrlgSaveRestoreStage.READING_FOOTERS
            FrlgSaveRestoreEngine.Stage.WRITING -> FrlgSaveRestoreStage.WRITING
            FrlgSaveRestoreEngine.Stage.LOADING, FrlgSaveRestoreEngine.Stage.ENDING -> FrlgSaveRestoreStage.LOADING
            FrlgSaveRestoreEngine.Stage.COMPLETED -> FrlgSaveRestoreStage.COMPLETED
            FrlgSaveRestoreEngine.Stage.REFUSED -> FrlgSaveRestoreStage.REFUSED
            FrlgSaveRestoreEngine.Stage.FAILED -> FrlgSaveRestoreStage.FAILED
        }
        detail = state.detail
        state.currentWrite?.let(::trackWriteStart)
        if (stage == FrlgSaveRestoreStage.LOADING && !watchdog.active) watchdog.begin(clockMillis())
        when (val action = core.action) {
            is FrlgSaveRestoreEngine.Action.Send -> sendMessage(action.ident, action.payload,
                if (action.ident == FrlgSaveRestoreEngine.RAM_SCRIPT) 3 else 2) { core.onSent(); pumpCore() }
            is FrlgSaveRestoreEngine.Action.Receive -> expect(action.ident)
            FrlgSaveRestoreEngine.Action.Done -> beginClose()
        }
    }

    private fun beginClose() {
        watchdog.complete()
        stage = FrlgSaveRestoreStage.CLOSING
        detail = if (core.successful) "Save restored; closing link" else core.snapshot().detail
        queueClose()
        closeRetry = 60
    }

    private fun queueClose() {
        repeat(4) { words += intArrayOf(FrlgRfu.READY_CLOSE_LINK, exitCount, 0, 0, 0, 0, 0) }
    }

    private fun expect(id: Int) {
        expecting = id
        messageReceiver.reset()
        if (stage == FrlgSaveRestoreStage.WRITING || stage == FrlgSaveRestoreStage.LOADING) {
            noteProgress()
            refreshTracked(result = "sent", waiting = true)
        }
    }

    private fun sendMessage(id: Int, payload: ByteArray, repeats: Int, done: () -> Unit) {
        currentMessageIndex++
        MgMessage.build(id, payload).forEach { block ->
            nextBlockIndex++
            blocks += RestoreQueuedBlock(block, repeats, nextBlockIndex)
        }
        afterSend = done
        noteProgress()
    }

    private fun fail(text: String) {
        core.failConnection(text)
        stage = FrlgSaveRestoreStage.FAILED
        detail = text
        watchdog.complete()
        finishTracked("failed", null)
        abortTransfer()
    }

    private fun checkTimeout() {
        if (!watchdog.expired(clockMillis())) return
        val plan = trackedWrite
        val waiting = expecting != null
        val text = when {
            plan != null && waiting -> "Restore timed out waiting for sector ${plan.index} of ${plan.total} readback"
            plan != null -> "Restore stalled while sending sector ${plan.index} of ${plan.total}"
            waiting -> "Restore timed out waiting for the restored save to load"
            else -> "Restore stalled while sending the final load request"
        }
        core.failConnection(text)
        stage = FrlgSaveRestoreStage.FAILED
        detail = text
        expecting = null
        watchdog.complete()
        finishTracked(if (waiting) "timeout-readback" else "timeout-send", null)
        abortTransfer()
    }

    private fun finishTracked(result: String, mask: Long?, responseReceived: Boolean = false) {
        val plan = trackedWrite ?: return
        val now = clockMillis()
        val index = diagnostics.indexOfLast { it.planIndex == plan.index && it.responseAtMillis == null }
        if (index >= 0) {
            val old = diagnostics[index]
            diagnostics[index] = currentDiagnostic(old, result, false).copy(
                responseAtMillis = if (responseReceived) now else null,
                durationMillis = now - old.startedAtMillis,
                failureMask = mask,
            )
        }
        trackedWrite = null
    }

    private fun trackWriteStart(plan: FrlgSaveRestoreEngine.WritePlanInfo) {
        if (trackedWrite?.index == plan.index) return
        trackedWrite = plan
        currentMessageIndex = 0
        currentBlockIndex = 0
        nextBlockIndex = 0
        val now = clockMillis()
        watchdog.begin(now)
        diagnostics += FrlgRestoreTransferDiagnostic(
            plan.index, plan.total, plan.physicalSector, plan.sectorId, plan.kind,
            plan.tokenBytes, plan.messageCount, plan.blockCount, now, now, null, null,
            0, 0, 0, false, false, null,
            reliableOutstanding, reliableReceived, reliableSent, rfuIn, rfuOut,
            ethernetAccepted, ethernetCompleted, ethernetAcknowledged, "sending",
        )
    }

    private fun noteProgress() {
        val now = clockMillis()
        watchdog.progress(now)
        refreshTracked(lastProgress = now)
    }

    private fun refreshTracked(result: String? = null, waiting: Boolean = expecting != null, lastProgress: Long? = null) {
        val plan = trackedWrite ?: return
        val index = diagnostics.indexOfLast { it.planIndex == plan.index && it.responseAtMillis == null }
        if (index >= 0) {
            val old = diagnostics[index]
            diagnostics[index] = currentDiagnostic(old, result ?: old.result, waiting).copy(
                lastProgressAtMillis = lastProgress ?: watchdog.lastProgressAtMillis ?: old.lastProgressAtMillis,
            )
        }
    }

    private fun currentDiagnostic(old: FrlgRestoreTransferDiagnostic, result: String, waiting: Boolean) = old.copy(
        currentMessageIndex = currentMessageIndex,
        currentBlockIndex = currentBlockIndex,
        blocksRemaining = blocks.size + if (sender != null) 1 else 0,
        senderActive = sender != null,
        waitingForResponse = waiting,
        reliableOutstanding = reliableOutstanding,
        reliableReceived = reliableReceived,
        reliableSent = reliableSent,
        rfuIn = rfuIn,
        rfuOut = rfuOut,
        ethernetAccepted = ethernetAccepted,
        ethernetCompleted = ethernetCompleted,
        ethernetAcknowledged = ethernetAcknowledged,
        result = result,
    )

    private fun abortTransfer() {
        words.clear(); blocks.clear(); sender = null; afterSend = null; gap = 0; disconnectRequested = true
    }

    companion object { const val RESTORE_PROGRESS_TIMEOUT_MS = 120_000L }
}

private fun u16Restore(data: ByteArray, at: Int = 0) =
    (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)

private fun u32Restore(data: ByteArray, at: Int = 0) = (0..3).fold(0L) { value, index ->
    value or ((data[at + index].toInt() and 255).toLong() shl (8 * index))
}
