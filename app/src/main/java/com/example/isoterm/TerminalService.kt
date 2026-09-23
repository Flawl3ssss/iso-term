package com.example.isoterm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService

/**
 * Фон для Android 14-16:
 * - specialUse (не dataSync: у dataSync лимит 6ч + запрет старта из BOOT на API35+)
 * - startForeground в первые ~10с, иначе ForegroundServiceDidNotStartInTimeException
 * - START_STICKY + onTaskRemoved -> перезапуск через AlarmManager
 * - WakeLock PARTIAL только если юзер включил тумблер (иначе жор)
 */
class TerminalService : LifecycleService() {

    companion object {
        const val CH_ID = "isoterm_fg"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.example.isoterm.STOP"
        var holdWake = false

        fun start(c: Context) {
            val i = Intent(c, TerminalService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(c, i)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = buildNotif()
        // Немедленно в foreground — обязательно для API 31+
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        if (holdWake) acquireWake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWake()
            stopSelf()
            return START_NOT_STICKY
        }
        // повторный вызов startForeground на случай рестарта
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotif(),
            if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        return START_STICKY
    }

    /** Свайп из Recents на MIUI убивает даже STICKY — перепланируем себя. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restart = Intent(this, TerminalService::class.java)
            val pi = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        releaseWake()
        super.onDestroy()
    }

    // Для dataSync на API35 обязателен onTimeout; для specialUse не вызывается, но держим для lint.
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "Терминал в фоне", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, TerminalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("IsoTerm работает")
            .setContentText("Ubuntu-сессия в фоне. Свайп не убьёт, если стоит замок + без ограничений.")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", stop)
            .build()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWake() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IsoTerm:session")
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }
}
