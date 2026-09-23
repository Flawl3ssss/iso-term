package com.example.isoterm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Xiaomi/MIUI/HyperOS + Doze. Все вызовы обёрнуты в try/catch — компоненты меняются от прошивки к прошивке. */
object XiaomiHelper {

    fun isIgnoringBattery(c: Context): Boolean {
        return try {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(c.packageName)
        } catch (_: Exception) { false }
    }

    /** Системный диалог «игнорировать оптимизации». Требует REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. */
    fun requestIgnoreBattery(c: Context) {
        try {
            c.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${c.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                c.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }

    /** Экран автозапуска MIUI. На HyperOS компонент тот же. */
    fun openAutostart(c: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        )
        for (cn in candidates) {
            try {
                c.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {}
        }
        // Fallback: детали приложения, там пункты Батарея/Автозапуск
        return try {
            c.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${c.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) { false }
    }
}
