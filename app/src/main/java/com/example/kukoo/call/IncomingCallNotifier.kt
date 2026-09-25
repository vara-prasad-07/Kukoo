package com.example.kukoo.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.kukoo.MainActivity

/**
 * The background trigger: a call-category notification with a full-screen intent.
 * Locked / idle screen -> full-screen "Your Tasks" call. Screen in use -> heads-up banner.
 */
object IncomingCallNotifier {
    const val ACTION_INCOMING_CALL = "com.example.kukoo.action.INCOMING_CALL"
    private const val CHANNEL_ID = "incoming_call"
    private const val NOTIFICATION_ID = 1001
    private const val RING_TIMEOUT_MS = 30_000L

    /** Returns false when notifications are not allowed, so nothing could be shown. */
    @SuppressLint("MissingPermission")
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!canNotify(app)) return false
        ensureChannel(app)

        val launch = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java)
                .setAction(ACTION_INCOMING_CALL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Your Tasks")
            .setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setContentIntent(launch)
            .setFullScreenIntent(launch, true)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        return true
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Android 14+ can withhold full-screen notifications until the user allows them. */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Incoming task call", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "The scheduled \"Your Tasks\" call"
            setSound(null, null) // the call screen rings itself
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
