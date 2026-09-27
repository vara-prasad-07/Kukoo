package com.example.kukoo.call

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.example.kukoo.MainActivity
import com.example.kukoo.domain.Task
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Schedules the "Your Tasks" call with [AlarmManager.setAlarmClock]: exact, fires in Doze and is exempt from
 * background-start limits. [AlarmManager.setExactAndAllowWhileIdle] is the exact fallback if that is refused.
 * Needs the exact-alarm permission on Android 12+ (declared in the manifest; USE_EXACT_ALARM is
 * granted automatically on 13+). If it is ever missing we fall back to an inexact alarm.
 * Trade-off: Android shows an alarm icon while one is pending.
 */
class CallScheduler(context: Context, private val clock: Clock) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val prefs = this.context.getSharedPreferences("call_schedule", Context.MODE_PRIVATE)

    /** The daily call time, or null when the daily call is off. */
    fun dailyTime(): LocalTime? {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        return LocalTime.of(prefs.getInt(KEY_HOUR, 9), prefs.getInt(KEY_MINUTE, 0))
    }

    /** When the daily call will next ring, or null when it is off. */
    fun nextDailyAt(): Long? {
        val time = dailyTime() ?: return null
        val today = LocalDate.now(clock)
        var next = LocalDateTime.of(today, time)
        if (next.atZone(clock.zone).toInstant().toEpochMilli() <= clock.millis()) next = next.plusDays(1)
        return next.atZone(clock.zone).toInstant().toEpochMilli()
    }

    fun scheduleDaily(time: LocalTime) {
        prefs.edit {
            putBoolean(KEY_ENABLED, true)
            putInt(KEY_HOUR, time.hour)
            putInt(KEY_MINUTE, time.minute)
        }
        rescheduleDaily()
    }

    fun cancelDaily() {
        prefs.edit { putBoolean(KEY_ENABLED, false) }
        alarms.cancel(operation(REQUEST_DAILY, daily = true))
    }

    /** Arms the next daily alarm (called after it fires and after a reboot). No-op when off. */
    fun rescheduleDaily() {
        val at = nextDailyAt() ?: return
        arm(REQUEST_DAILY, at, daily = true)
    }

    /** One-off ring a few seconds from now, to demo the trigger without waiting. */
    fun ringIn(seconds: Int) {
        arm(REQUEST_DEMO, clock.millis() + seconds * 1000L, daily = false)
    }

    /**
     * Brings the per-task reminder alarms in line with [tasks]: every open task with a reminder still
     * in the future gets exactly one alarm (re-armed in place if its time changed), and alarms of
     * tasks that were deleted, finished or lost their reminder are cancelled.
     */
    fun syncReminders(tasks: List<Task>) {
        val now = clock.millis()
        val wanted = tasks.mapNotNull { t ->
            val at = t.reminderAt
            if (!t.isDone && at != null && at > now) t.id to at else null
        }.toMap()
        val armed = prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        (armed - wanted.keys).forEach { alarms.cancel(operation(reminderRequest(it), daily = false, taskId = it)) }
        wanted.forEach { (id, at) -> arm(reminderRequest(id), at, daily = false, taskId = id) }
        prefs.edit { putStringSet(KEY_ARMED, wanted.keys.map { it.toString() }.toSet()) }
    }

    /** Called when a reminder rings: its alarm is spent, so it no longer needs cancelling later. */
    fun reminderFired(taskId: Long) {
        val armed = prefs.getStringSet(KEY_ARMED, emptySet()).orEmpty()
        prefs.edit { putStringSet(KEY_ARMED, armed - taskId.toString()) }
    }

    /** Rings the reminder for [taskId] again in [minutes] (the call's Snooze button). */
    fun snoozeReminder(taskId: Long, minutes: Int) {
        arm(reminderRequest(taskId), clock.millis() + minutes * 60_000L, daily = false, taskId = taskId)
    }

    private fun reminderRequest(taskId: Long): Int = REQUEST_REMINDER_BASE + (taskId % REMINDER_ID_SPAN).toInt()

    private fun arm(requestCode: Int, atMillis: Long, daily: Boolean, taskId: Long = NO_TASK) {
        val operation = operation(requestCode, daily, taskId)
        val canBeExact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        if (canBeExact) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, show), operation)
                return
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call: fall through to the inexact alarm.
            }
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                return
            } catch (_: SecurityException) {
                // Still no exact access: use the inexact alarm below.
            }
        }
        // Without exact-alarm access the call may be a few minutes late, but it still rings.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
    }

    // Extras are ignored when PendingIntents are matched, so every task needs its own request code.
    private fun operation(requestCode: Int, daily: Boolean, taskId: Long = NO_TASK): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CallAlarmReceiver::class.java)
                .putExtra(CallAlarmReceiver.EXTRA_DAILY, daily)
                .putExtra(CallAlarmReceiver.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private companion object {
        const val KEY_ENABLED = "daily_enabled"
        const val KEY_HOUR = "daily_hour"
        const val KEY_MINUTE = "daily_minute"
        const val KEY_ARMED = "armed_reminders"
        const val REQUEST_DAILY = 1
        const val REQUEST_DEMO = 2
        const val NO_TASK = CallAlarmReceiver.NO_TASK
        const val REQUEST_REMINDER_BASE = 1_000
        const val REMINDER_ID_SPAN = 1_000_000L
    }
}
