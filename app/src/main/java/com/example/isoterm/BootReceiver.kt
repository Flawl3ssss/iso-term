package com.example.isoterm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Рестарт фона после ребута / обновления. Стартует только если юзер включал автозапуск. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a == Intent.ACTION_BOOT_COMPLETED ||
            a == Intent.ACTION_USER_UNLOCKED ||
            a == "com.example.isoterm.RESTART_TERMINAL"
        ) {
            val prefs = c.getSharedPreferences("iso", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("autostart", false)) return
            try {
                ContextCompat.startForegroundService(c, Intent(c, TerminalService::class.java))
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException — юзер откроет вручную
            }
        }
    }
}
