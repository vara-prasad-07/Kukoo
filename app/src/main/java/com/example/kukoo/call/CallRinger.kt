package com.example.kukoo.call

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Rings and vibrates while the incoming-call screen is showing. Failures are never fatal. */
class CallRinger(context: Context) {
    private val context = context.applicationContext
    private var ringtone: Ringtone? = null

    @Suppress("DEPRECATION")
    fun start() {
        if (ringtone != null) return
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also {
                if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                it.play()
            }
        }
        runCatching {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { context.getSystemService(Vibrator::class.java)?.cancel() }
    }
}
