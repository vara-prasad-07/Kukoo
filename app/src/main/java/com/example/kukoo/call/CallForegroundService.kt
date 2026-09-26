package com.example.kukoo.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive (and marked important to aggressive OEM task killers) while a call is ringing
 * or a voice session is running. Two modes: [MODE_RINGING] promotes the incoming-call notification itself
 * to the foreground notification; [MODE_SESSION] shows a quiet "call in progress" notification.
 * If the system refuses to start it, ringing still falls back to a plain notification.
 */
class CallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopSelfRunnable = Runnable { shutDown() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = intent?.getStringExtra(EXTRA_MODE) == MODE_SESSION
        handler.removeCallbacks(stopSelfRunnable)

        val id = if (session) IncomingCallNotifier.SESSION_NOTIFICATION_ID else IncomingCallNotifier.NOTIFICATION_ID
        val notification = if (session) IncomingCallNotifier.buildSessionNotification(this)
        else IncomingCallNotifier.buildRingingNotification(this)

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(id, notification)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException: the call must still be announced.
            Log.w(TAG, "Could not go foreground: ${e.message}")
            if (!session) IncomingCallNotifier.notifyRinging(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (session) {
            // The ringing notification is no longer needed once the call is answered.
            NotificationManagerCompat.from(this).cancel(IncomingCallNotifier.NOTIFICATION_ID)
        } else {
            handler.postDelayed(stopSelfRunnable, IncomingCallNotifier.RING_TIMEOUT_MS)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopSelfRunnable)
        super.onDestroy()
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "CallForegroundService"
        private const val EXTRA_MODE = "mode"
        private const val MODE_RINGING = "ringing"
        private const val MODE_SESSION = "session"

        /** Starts ringing in the foreground. Returns false when the system refused to start the service. */
        fun startRinging(context: Context): Boolean = start(context, MODE_RINGING)

        fun startSession(context: Context): Boolean = start(context, MODE_SESSION)

        fun stop(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, CallForegroundService::class.java))
        }

        private fun start(context: Context, mode: String): Boolean = try {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, CallForegroundService::class.java).putExtra(EXTRA_MODE, mode)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not start service: ${e.message}")
            false
        }
    }
}
