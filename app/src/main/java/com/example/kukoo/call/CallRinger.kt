package com.example.kukoo.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Rings and vibrates while the incoming-call screen is showing. Holds transient audio focus while
 * ringing and gives it back in [stop], so speech output and recognition get a clear channel once
 * the call is answered. Failures are never fatal.
 */
class CallRinger(context: Context) {
    private val context = context.applicationContext
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null
    private var focusRequest: AudioFocusRequest? = null

    private val ringAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Synchronized
    fun start() {
        if (ringtone != null) return
        requestFocus()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also {
                it.audioAttributes = ringAttributes
                if (Build.VERSION.SDK_INT >= 28) it.isLooping = true
                it.play()
            }
        }
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        }
    }

    @Synchronized
    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { context.getSystemService(Vibrator::class.java)?.cancel() }
        abandonFocus()
    }

    private fun requestFocus() {
        runCatching {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ringAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            focusRequest = request
            audioManager?.requestAudioFocus(request)
        }
    }

    private fun abandonFocus() {
        runCatching { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }
}
