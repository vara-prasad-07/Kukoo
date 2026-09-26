package com.example.kukoo.call

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit

/** "Ignore battery optimizations" for Kukoo, so the scheduled call is not deferred or killed by the OEM. */
object BatteryOptimization {
    private const val PREFS = "battery_prompt"
    private const val KEY_DECLINED_AT = "declined_at"
    private const val ASK_AGAIN_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** True when the app is optimized and the user has not recently said "not now". */
    fun shouldAsk(context: Context, nowMillis: Long): Boolean {
        if (isIgnoring(context)) return false
        val declinedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DECLINED_AT, 0L)
        return declinedAt == 0L || nowMillis - declinedAt > ASK_AGAIN_AFTER_MS
    }

    fun rememberDeclined(context: Context, nowMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_DECLINED_AT, nowMillis) }
    }

    /** Opens the system prompt for this app; falls back to the general battery-optimization list. */
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
