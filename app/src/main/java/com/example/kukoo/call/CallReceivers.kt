package com.example.kukoo.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.kukoo.KukooApp

/**
 * Fires when a scheduled time arrives: rings in-app if visible, otherwise posts the call notification.
 * A per-task reminder carries [EXTRA_TASK_ID] so the call is about that one task.
 */
class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as KukooApp).container
        if (intent.getBooleanExtra(EXTRA_DAILY, false)) container.scheduler.rescheduleDaily()

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, NO_TASK)
        if (taskId != NO_TASK) {
            container.scheduler.reminderFired(taskId)
            // Finished or deleted since the alarm was set: nobody needs a call about it.
            val task = container.store.get(taskId)
            if (task == null || task.isDone) return
        }

        if (container.appVisible) {
            container.incomingCalls.tryEmit(taskId)
        } else {
            IncomingCallNotifier.show(context, taskId)
        }
    }

    companion object {
        const val EXTRA_DAILY = "daily"
        const val EXTRA_TASK_ID = "task_id"

        /** "Not about one task": the daily call, which reads out everything that is due. */
        const val NO_TASK = -1L
    }
}

/** Alarms do not survive a reboot; re-arm the daily call and every task reminder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val container = (context.applicationContext as KukooApp).container
            container.scheduler.rescheduleDaily()
            container.scheduler.syncReminders(container.store.all())
        }
    }
}
