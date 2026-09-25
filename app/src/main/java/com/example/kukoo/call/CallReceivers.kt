package com.example.kukoo.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.kukoo.KukooApp

/** Fires when the scheduled time arrives: rings in-app if visible, otherwise posts the call notification. */
class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as KukooApp).container
        if (intent.getBooleanExtra(EXTRA_DAILY, false)) container.scheduler.rescheduleDaily()

        if (container.appVisible) {
            container.incomingCalls.tryEmit(Unit)
        } else {
            IncomingCallNotifier.show(context)
        }
    }

    companion object {
        const val EXTRA_DAILY = "daily"
    }
}

/** Alarms do not survive a reboot; re-arm the daily call. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            (context.applicationContext as KukooApp).container.scheduler.rescheduleDaily()
        }
    }
}
