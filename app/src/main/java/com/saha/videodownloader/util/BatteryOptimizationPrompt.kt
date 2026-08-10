package com.saha.videodownloader.util

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * HyperOS / Xiaomi (and some OEMs) aggressively restrict background CPU.
 * Prompt once so long HLS mux / Media3 downloads are less likely to be killed.
 */
object BatteryOptimizationPrompt {

    private const val PREFS = "oem_tips"
    private const val KEY_ASKED = "asked_battery_unrestricted"

    fun maybePrompt(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(appContext.packageName)) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply()
            return
        }

        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        AlertDialog.Builder(context)
            .setTitle("Xiaomi / HyperOS")
            .setMessage(
                "บน Xiaomi 14 (Android 16 / HyperOS) ระบบอาจหยุดแอปตอน mux HLS นานๆ\n\n" +
                    "แนะนำ: อนุญาตให้แอปนี้ทำงานโดยไม่จำกัดแบตเตอรี่ " +
                    "และเปิด Autostart ถ้ามีในตั้งค่าแอป"
            )
            .setPositiveButton("ตั้งค่าแบตเตอรี่") { _, _ ->
                openBatterySettings(appContext)
            }
            .setNegativeButton("ไว้ทีหลัง", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(request) }
            .recoverCatching { context.startActivity(fallback) }
    }
}
