package com.dedhapp3n.pokeldn.frlg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dedhapp3n.pokeldn.MainActivity
import com.dedhapp3n.pokeldn.R
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioPhase
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.ldn.LdnDiscoveryController
import com.dedhapp3n.pokeldn.ldn.LdnDiscoverySnapshot
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

enum class FrlgForegroundKind { WALK_THROUGH_WALLS, SAVE_BACKUP, SAVE_RESTORE }

data class FrlgForegroundProgress(
    val kind: FrlgForegroundKind,
    val phase: FrlgOperationPhase,
    val detail: String? = null,
    val cartridge: String? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val snapshot: LdnDiscoverySnapshot? = null,
    val restorePlanIndex: Int? = null,
    val restorePlanTotal: Int = 0,
    val restoreWaitingForResponse: Boolean = false,
    val restoreLastProgressAtMillis: Long? = null,
) {
    val terminal: Boolean get() = phase in setOf(
        FrlgOperationPhase.COMPLETED, FrlgOperationPhase.FAILED, FrlgOperationPhase.CANCELLED, FrlgOperationPhase.REFUSED,
    )
}

internal object FrlgOperationNotificationText {
    fun title(progress: FrlgForegroundProgress): String = when {
        progress.phase == FrlgOperationPhase.COMPLETED && progress.kind == FrlgForegroundKind.SAVE_RESTORE -> "Save restore complete"
        progress.phase == FrlgOperationPhase.FAILED && progress.kind == FrlgForegroundKind.SAVE_RESTORE -> "Restore failed"
        progress.phase == FrlgOperationPhase.REFUSED && progress.kind == FrlgForegroundKind.SAVE_RESTORE -> "Restore refused"
        progress.kind == FrlgForegroundKind.SAVE_RESTORE -> "PokeLDN - Restoring ${progress.cartridge?.substringBefore(" / ") ?: "save"}"
        progress.kind == FrlgForegroundKind.SAVE_BACKUP -> "PokeLDN - Save Backup"
        else -> "PokeLDN - Walk Through Walls"
    }

    fun text(progress: FrlgForegroundProgress, nowMillis: Long = System.currentTimeMillis()): String = when {
        progress.phase == FrlgOperationPhase.FAILED && progress.kind == FrlgForegroundKind.SAVE_RESTORE && progress.restorePlanIndex != null ->
            "Restore failed at sector ${progress.restorePlanIndex} / ${progress.restorePlanTotal}"
        progress.phase == FrlgOperationPhase.FAILED -> progress.detail ?: "Operation failed"
        progress.phase == FrlgOperationPhase.REFUSED -> progress.detail ?: "Operation refused"
        progress.phase == FrlgOperationPhase.COMPLETED -> progress.detail ?: "Operation complete"
        progress.phase == FrlgOperationPhase.WAITING_FOR_CONSOLE -> "Waiting for console"
        progress.phase == FrlgOperationPhase.VALIDATING_SAVE -> "Validating save"
        progress.phase == FrlgOperationPhase.READING_CARTRIDGE && progress.kind == FrlgForegroundKind.SAVE_BACKUP -> "Reading cartridge"
        progress.phase == FrlgOperationPhase.RECEIVING_SAVE && progress.kind == FrlgForegroundKind.SAVE_BACKUP && progress.total > 0 ->
            "${progress.completed / 1024} / ${progress.total / 1024} KB"
        progress.phase == FrlgOperationPhase.RESTORING_SAVE && progress.restorePlanIndex != null -> {
            val action = if (progress.restoreWaitingForResponse) "verifying" else "sending"
            val quietSeconds = progress.restoreLastProgressAtMillis?.let {
                ((nowMillis - it).coerceAtLeast(0) / 1000).toInt()
            } ?: 0
            "Sector ${progress.restorePlanIndex} / ${progress.restorePlanTotal} - " +
                if (quietSeconds >= 10) "no progress for $quietSeconds s" else action
        }
        progress.phase == FrlgOperationPhase.RESTORING_SAVE && progress.total > 0 -> "Sector ${progress.completed + 1} / ${progress.total} - sending"
        progress.phase == FrlgOperationPhase.LOADING_RESTORED_SAVE -> "Loading restored save"
        progress.total > 0 -> "${progress.completed} / ${progress.total} sectors"
        else -> progress.detail ?: progress.phase.label
    }
}

internal interface FrlgPowerLease { fun acquire(); fun release() }
internal interface FrlgForegroundSink { fun update(progress: FrlgForegroundProgress); fun stop() }

/** Pure ownership gate used by the service; Activity recreation never releases active hardware. */
internal class FrlgForegroundLease(
    private val power: FrlgPowerLease,
    private val foreground: FrlgForegroundSink,
) {
    var active = false
        private set
    fun begin(progress: FrlgForegroundProgress) {
        if (!active) { active = true; power.acquire() }
        foreground.update(progress)
    }
    fun update(progress: FrlgForegroundProgress) {
        if (!active) return
        foreground.update(progress)
        if (progress.terminal) finish()
    }
    fun activityRecreated() = Unit
    fun finish() {
        if (!active) return
        active = false
        foreground.stop()
        power.release()
    }
}

/**
 * Owns an active FRLG hardware operation after MainActivity has prepared the USB session.
 * The in-process handoff is deliberate: USB connections cannot be reconstructed after process death.
 */
class FrlgOperationService : Service() {
    data class OwnedOperation(
        val transport: UsbSerialTransport,
        val session: Esp32RadioSession,
        val radioOperation: Esp32RadioOperation,
        val discovery: LdnDiscoveryController,
        val kind: FrlgForegroundKind,
        val deviceName: String?,
    )

    private val binder = LocalBinder()
    private val cleanupWorker = Executors.newSingleThreadExecutor()
    private val traceWorker = Executors.newSingleThreadExecutor()
    private var owned: OwnedOperation? = null
    private var radioSubscription: AutoCloseable? = null
    private lateinit var traceStore: FrlgRestoreTraceStore
    private var lastNotificationSignature: String? = null
    private var lastTracePersistAtMillis = 0L
    @Volatile private var cleanupStarted = false
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val operation = owned ?: return
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && operation.deviceName != null && device.deviceName != operation.deviceName) return
            finish(FrlgOperationPhase.FAILED, "ESP32 disconnected during the operation")
        }
    }
    private lateinit var lease: FrlgForegroundLease

    inner class LocalBinder : Binder() { fun service(): FrlgOperationService = this@FrlgOperationService }

    override fun onCreate() {
        super.onCreate()
        instance = this
        traceStore = FrlgRestoreTraceStore(filesDir.resolve(FrlgRestoreTraceStore.FILE_NAME))
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED,
        )
        createChannel()
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "PokeLDN:FRLG operation",
        ).apply { setReferenceCounted(false) }
        lease = FrlgForegroundLease(
            object : FrlgPowerLease {
                override fun acquire() { if (!wake.isHeld) wake.acquire() }
                override fun release() { if (wake.isHeld) wake.release() }
            },
            object : FrlgForegroundSink {
                override fun update(progress: FrlgForegroundProgress) {
                    val signature = listOf(
                        FrlgOperationNotificationText.title(progress),
                        FrlgOperationNotificationText.text(progress),
                        progress.completed,
                        progress.total,
                    ).joinToString("|")
                    if (leaseStarted && signature == lastNotificationSignature) return
                    lastNotificationSignature = signature
                    val notification = notification(progress)
                    if (!leaseStarted) {
                        startForeground(NOTIFICATION_ID, notification)
                        leaseStarted = true
                    } else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                }
                override fun stop() {
                    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
                    leaseStarted = false
                    lastNotificationSignature = null
                }
            },
        )
        pendingProgress?.let(lease::begin)
        pendingOwned?.let { adopt(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val progress = pendingProgress ?: currentProgress
        if (progress != null) {
            if (!lease.active) lease.begin(progress) else lease.update(progress)
        }
        pendingOwned?.let { adopt(it) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        owned.also { owned = null }?.let { abandoned ->
            runCatching { abandoned.discovery.stop(); abandoned.session.stopAccessPoint(abandoned.radioOperation) }
            currentProgress?.copy(
                phase = FrlgOperationPhase.FAILED,
                detail = "Foreground operation service stopped",
            )?.let { failed ->
                currentProgress = failed
                pendingProgress = failed
                listeners.forEach { it(failed) }
            }
        }
        lease.finish()
        radioSubscription?.close()
        radioSubscription = null
        cleanupWorker.shutdownNow()
        traceWorker.shutdownNow()
        runCatching { unregisterReceiver(detachReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun adopt(value: OwnedOperation) {
        if (owned === value) return
        check(owned == null) { "A foreground FRLG operation is already active" }
        owned = value
        cleanupStarted = false
        radioSubscription?.close()
        radioSubscription = value.session.addStateListener { state ->
            if (owned === value && state.phase in setOf(
                    Esp32RadioPhase.DISCONNECTED, Esp32RadioPhase.ERROR, Esp32RadioPhase.CLEANUP_REQUIRED,
                )) {
                finish(FrlgOperationPhase.FAILED, state.detail ?: "ESP32 radio connection failed")
            }
        }
        pendingOwned = null
    }

    private fun cleanupOwned(terminal: FrlgOperationPhase, detail: String) {
        synchronized(this) {
            if (cleanupStarted) return
            cleanupStarted = true
        }
        // Keep ownership visible until STOP/STATUS finishes so Activity destruction during
        // cleanup cannot close the same serial session out from under the service.
        val operation = owned
        radioSubscription?.close()
        radioSubscription = null
        if (operation == null) {
            publish(currentProgress?.copy(phase = terminal, detail = detail) ?: return)
            stopSelf()
            return
        }
        cleanupWorker.execute {
            val cleanupError = runCatching {
                operation.discovery.stop()
                operation.session.stopAccessPoint(operation.radioOperation)
            }.exceptionOrNull()
            val final = currentProgress?.copy(
                phase = if (cleanupError == null) terminal else FrlgOperationPhase.FAILED,
                detail = cleanupError?.let { "$detail; adapter cleanup failed: ${it.message}" } ?: detail,
            ) ?: return@execute
            persistRestoreProgress(final)
            if (cleanupError == null) {
                retainedTransport = operation.transport
                retainedSession = operation.session
            } else {
                runCatching { operation.session.close() }
                runCatching { operation.transport.disconnect() }
                retainedTransport = null
                retainedSession = null
            }
            if (owned === operation) owned = null
            publish(final)
            stopSelf()
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "PokeLDN operations", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun persistRestoreProgress(progress: FrlgForegroundProgress) {
        if (progress.kind != FrlgForegroundKind.SAVE_RESTORE) return
        val trace = progress.snapshot?.saveRestoreTransferDiagnostics.orEmpty()
        if (trace.isBlank()) return
        val now = System.currentTimeMillis()
        if (!progress.terminal && now - lastTracePersistAtMillis < TRACE_PERSIST_INTERVAL_MS) return
        lastTracePersistAtMillis = now
        val content = buildString {
            appendLine("phase=${progress.phase} detail=${progress.detail ?: "none"}")
            appendLine("cartridge=${progress.cartridge ?: "unknown"} completed=${progress.completed}/${progress.total}")
            append(trace)
        }
        // Never make the time-sensitive RFU scheduler wait for app-private file I/O.
        if (progress.terminal) {
            // Cleanup runs off the protocol scheduler. Serialize the final write after any
            // queued heartbeat trace so stale progress can never overwrite the terminal result.
            traceWorker.submit { traceStore.replace(content) }.get()
        } else {
            traceWorker.execute { traceStore.replace(content) }
        }
    }

    private fun notification(progress: FrlgForegroundProgress): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(FrlgOperationNotificationText.title(progress))
            .setContentText(FrlgOperationNotificationText.text(progress))
            .setContentIntent(open)
            .setOngoing(!progress.terminal)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply {
                if (progress.total > 0 && !progress.terminal) {
                    setProgress(progress.total, progress.completed.coerceIn(0, progress.total), false)
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "pokeldn_operations"
        private const val NOTIFICATION_ID = 0x504f4b45
        private const val TRACE_PERSIST_INTERVAL_MS = 1_000L
        @Volatile private var instance: FrlgOperationService? = null
        @Volatile private var pendingOwned: OwnedOperation? = null
        @Volatile private var pendingProgress: FrlgForegroundProgress? = null
        @Volatile private var currentProgress: FrlgForegroundProgress? = null
        @Volatile private var retainedTransport: UsbSerialTransport? = null
        @Volatile private var retainedSession: Esp32RadioSession? = null
        @Volatile private var restoreSelection: FrlgSaveRestoreUiState? = null
        private val listeners = CopyOnWriteArraySet<(FrlgForegroundProgress) -> Unit>()
        private var leaseStarted = false

        fun begin(context: Context, progress: FrlgForegroundProgress) {
            if (progress.kind == FrlgForegroundKind.SAVE_RESTORE) {
                FrlgRestoreTraceStore(context.filesDir.resolve(FrlgRestoreTraceStore.FILE_NAME)).clear()
                instance?.lastTracePersistAtMillis = 0L
            }
            pendingProgress = progress
            currentProgress = progress
            ContextCompat.startForegroundService(context, Intent(context, FrlgOperationService::class.java))
            listeners.forEach { it(progress) }
        }

        fun adopt(context: Context, operation: OwnedOperation) {
            pendingOwned = operation
            instance?.adopt(operation) ?: ContextCompat.startForegroundService(context, Intent(context, FrlgOperationService::class.java))
        }

        fun update(progress: FrlgForegroundProgress) = publish(progress)

        fun updateFromSnapshot(snapshot: LdnDiscoverySnapshot) {
            val previous = currentProgress ?: return
            val phase = when (previous.kind) {
                FrlgForegroundKind.WALK_THROUGH_WALLS -> when (snapshot.giftStage) {
                    FrlgGiftStage.COMPLETED -> FrlgOperationPhase.COMPLETED
                    FrlgGiftStage.FAILED -> FrlgOperationPhase.FAILED
                    FrlgGiftStage.READING_CARTRIDGE -> FrlgOperationPhase.READING_CARTRIDGE
                    FrlgGiftStage.PREPARING_BOOST -> FrlgOperationPhase.PREPARING_BOOST
                    FrlgGiftStage.SENDING_BOOST -> FrlgOperationPhase.SENDING_BOOST
                    FrlgGiftStage.WAITING_RESULT -> FrlgOperationPhase.WAITING_FOR_CLOSE
                    FrlgGiftStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
                    else -> sharedPhase(snapshot)
                }
                FrlgForegroundKind.SAVE_BACKUP -> when (snapshot.saveBackupStage) {
                    FrlgSaveBackupStage.READING_CARTRIDGE -> FrlgOperationPhase.READING_CARTRIDGE
                    FrlgSaveBackupStage.RECEIVING_SAVE -> FrlgOperationPhase.RECEIVING_SAVE
                    FrlgSaveBackupStage.WAITING_READY_END -> FrlgOperationPhase.VALIDATING_SAVE
                    FrlgSaveBackupStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
                    FrlgSaveBackupStage.COMPLETED -> FrlgOperationPhase.COMPLETED
                    FrlgSaveBackupStage.FAILED -> FrlgOperationPhase.FAILED
                    else -> sharedPhase(snapshot)
                }
                FrlgForegroundKind.SAVE_RESTORE -> when (snapshot.saveRestoreStage) {
                    FrlgSaveRestoreStage.WAITING_CLIENT, FrlgSaveRestoreStage.VALIDATING -> FrlgOperationPhase.VALIDATING_SAVE
                    FrlgSaveRestoreStage.READING_FOOTERS -> FrlgOperationPhase.READING_CARTRIDGE
                    FrlgSaveRestoreStage.WRITING -> FrlgOperationPhase.RESTORING_SAVE
                    FrlgSaveRestoreStage.LOADING -> FrlgOperationPhase.LOADING_RESTORED_SAVE
                    FrlgSaveRestoreStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
                    FrlgSaveRestoreStage.COMPLETED -> FrlgOperationPhase.COMPLETED
                    FrlgSaveRestoreStage.REFUSED -> FrlgOperationPhase.REFUSED
                    FrlgSaveRestoreStage.FAILED -> FrlgOperationPhase.FAILED
                    null -> sharedPhase(snapshot)
                }
            }
            val detail = when {
                phase == FrlgOperationPhase.FAILED && previous.kind == FrlgForegroundKind.SAVE_RESTORE ->
                    snapshot.latestActivity ?: "Connection lost while restoring"
                phase == FrlgOperationPhase.COMPLETED && previous.kind == FrlgForegroundKind.WALK_THROUGH_WALLS ->
                    "Walk Through Walls installed. Hold R while moving."
                phase == FrlgOperationPhase.COMPLETED && previous.kind == FrlgForegroundKind.SAVE_RESTORE ->
                    "Save restored successfully"
                else -> snapshot.latestActivity ?: previous.detail
            }
            val next = previous.copy(
                phase = phase,
                detail = detail,
                cartridge = snapshot.detectedCartridge ?: previous.cartridge,
                completed = when (previous.kind) {
                    FrlgForegroundKind.SAVE_RESTORE -> snapshot.saveRestoreSectors
                    FrlgForegroundKind.SAVE_BACKUP -> snapshot.saveBackupBytes
                    else -> previous.completed
                },
                total = when (previous.kind) {
                    FrlgForegroundKind.SAVE_RESTORE -> snapshot.saveRestoreTotalSectors
                    FrlgForegroundKind.SAVE_BACKUP -> snapshot.saveBackupTotalBytes
                    else -> previous.total
                },
                snapshot = snapshot,
                restorePlanIndex = snapshot.saveRestoreCurrentPlanIndex,
                restorePlanTotal = snapshot.saveRestoreCurrentPlanTotal,
                restoreWaitingForResponse = snapshot.saveRestoreWaitingForResponse,
                restoreLastProgressAtMillis = snapshot.saveRestoreLastProgressAtMillis,
            )
            if (!next.terminal) instance?.persistRestoreProgress(next)
            if (next.terminal) {
                publish(next.copy(phase = FrlgOperationPhase.RETURNING_TO_READY))
                finish(next.phase, requireNotNull(next.detail))
            } else publish(next)
        }

        private fun sharedPhase(snapshot: LdnDiscoverySnapshot): FrlgOperationPhase = when {
            snapshot.linkPlayerExchanged -> FrlgOperationPhase.ESTABLISHING_GAME_LINK
            snapshot.rfuConnected -> FrlgOperationPhase.ESTABLISHING_RFU
            snapshot.reliableEstablished -> FrlgOperationPhase.ESTABLISHING_RELIABLE
            snapshot.participantRegistered -> FrlgOperationPhase.PARTICIPANT_REGISTERED
            snapshot.authenticationRequests > 0 -> FrlgOperationPhase.AUTHENTICATING
            snapshot.stationDetected -> FrlgOperationPhase.STATION_ASSOCIATED
            snapshot.discoveryActivityCount > 0 -> FrlgOperationPhase.CONSOLE_DETECTED
            else -> FrlgOperationPhase.WAITING_FOR_CONSOLE
        }

        private fun publish(progress: FrlgForegroundProgress) {
            currentProgress = progress
            pendingProgress = progress
            instance?.let { if (!it.lease.active) it.lease.begin(progress) else it.lease.update(progress) }
            listeners.forEach { it(progress) }
        }

        fun finish(terminal: FrlgOperationPhase, detail: String) {
            instance?.cleanupOwned(terminal, detail) ?: publish(currentProgress?.copy(phase = terminal, detail = detail) ?: return)
        }

        fun cancel(detail: String) = finish(FrlgOperationPhase.CANCELLED, detail)
        fun isActive(): Boolean = currentProgress?.terminal == false
        fun owns(session: Esp32RadioSession?): Boolean = session != null && (instance?.owned?.session === session || pendingOwned?.session === session)
        fun ownedTransport(): UsbSerialTransport? = instance?.owned?.transport ?: pendingOwned?.transport ?: retainedTransport
        fun ownedSession(): Esp32RadioSession? = instance?.owned?.session ?: pendingOwned?.session ?: retainedSession
        fun releaseRetained(session: Esp32RadioSession) {
            if (retainedSession === session) { retainedSession = null; retainedTransport = null }
        }
        fun progress(): FrlgForegroundProgress? = currentProgress
        fun rememberRestoreSelection(state: FrlgSaveRestoreUiState) { restoreSelection = state }
        fun restoreSelection(): FrlgSaveRestoreUiState? = restoreSelection
        fun subscribe(listener: (FrlgForegroundProgress) -> Unit): AutoCloseable {
            listeners += listener
            currentProgress?.let(listener)
            return AutoCloseable { listeners -= listener }
        }

        internal fun resetForTest() {
            pendingOwned = null; pendingProgress = null; currentProgress = null
            retainedTransport = null; retainedSession = null; listeners.clear()
            restoreSelection = null
        }
    }
}
