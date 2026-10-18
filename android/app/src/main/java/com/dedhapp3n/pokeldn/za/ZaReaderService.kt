package com.dedhapp3n.pokeldn.za

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dedhapp3n.pokeldn.MainActivity
import com.dedhapp3n.pokeldn.R
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Retains the USB/AP operation across Activity recreation and owns its CPU wake lease. */
internal class ZaReaderService : Service() {
    private val cleanup = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private var lastNotificationCount = -1
    private var lastNotificationPhase: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Legends: Z-A reading", NotificationManager.IMPORTANCE_LOW),
        )
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PokeLDN:ZaReader")
        lock.acquire()
        wake = lock
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(current))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active?.let { owned ->
            runCatching { owned.controller.close(); owned.session.stopAccessPoint(owned.operation) }
            if (active === owned) active = null
        }
        preparingTransport = null; preparingSession = null
        wake?.takeIf { it.isHeld }?.release(); wake = null
        cleanup.shutdownNow()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun updateNotification(state: ZaReaderState) {
        val count = state.snapshot.pokemon.size
        val now = System.currentTimeMillis()
        if (!state.terminal && now - lastNotificationAt < 1000 && count == lastNotificationCount && state.phase == lastNotificationPhase) return
        lastNotificationAt = now; lastNotificationCount = count; lastNotificationPhase = state.phase
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
    }

    private fun notification(state: ZaReaderState?): Notification {
        val text = state?.let {
            val count = it.snapshot.pokemon.size
            if (count > 0) "${it.snapshot.trainer?.name ?: "Trainer"} - $count Pokémon captured"
            else if (it.phase == "WAITING FOR Z-A") "Waiting for game" else it.phase
        } ?: "Preparing network"
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("PokeLDN - Reading Legends: Z-A")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state?.terminal != true)
            .build()
    }

    private fun complete(state: ZaReaderState) {
        val owned = active ?: return
        if (active !== owned) return
        cleanup.execute {
            runCatching { owned.controller.close(); owned.session.stopAccessPoint(owned.operation) }
            if (active === owned) active = null
            current = state
            listeners.forEach { it(state) }
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL = "pokeldn_za_reader"
        private const val NOTIFICATION = 0x5a412001
        private data class Owned(
            val transport: UsbSerialTransport,
            val session: Esp32RadioSession,
            val operation: Esp32RadioOperation,
            val controller: ZaReaderController,
        )
        @Volatile private var instance: ZaReaderService? = null
        @Volatile private var active: Owned? = null
        @Volatile private var preparingTransport: UsbSerialTransport? = null
        @Volatile private var preparingSession: Esp32RadioSession? = null
        @Volatile private var current: ZaReaderState? = null
        private val listeners = CopyOnWriteArraySet<(ZaReaderState) -> Unit>()

        fun begin(context: Context, transport: UsbSerialTransport, session: Esp32RadioSession, state: ZaReaderState) {
            check(active == null)
            preparingTransport = transport; preparingSession = session; current = state
            ContextCompat.startForegroundService(context, Intent(context, ZaReaderService::class.java))
            listeners.forEach { it(state) }
        }

        fun attach(transport: UsbSerialTransport, session: Esp32RadioSession, operation: Esp32RadioOperation, controller: ZaReaderController) {
            active = Owned(transport, session, operation, controller)
            preparingTransport = null; preparingSession = null
        }

        fun update(state: ZaReaderState) {
            current = state
            listeners.forEach { it(state) }
            instance?.updateNotification(state)
            if (state.terminal) instance?.complete(state)
        }

        fun finish() { active?.controller?.finish() }
        fun ownedTransport(): UsbSerialTransport? = active?.transport ?: preparingTransport
        fun ownedSession(): Esp32RadioSession? = active?.session ?: preparingSession
        fun owns(session: Esp32RadioSession?): Boolean = session != null && (active?.session === session || preparingSession === session)
        fun isActive() = active != null || preparingSession != null
        fun subscribe(listener: (ZaReaderState) -> Unit): AutoCloseable {
            listeners += listener
            current?.let(listener)
            return AutoCloseable { listeners -= listener }
        }
        fun clearPreparation() { if (active == null) { preparingTransport = null; preparingSession = null; instance?.stopSelf() } }
    }
}
