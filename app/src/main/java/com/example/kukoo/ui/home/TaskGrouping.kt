package com.example.kukoo.ui.home

import com.example.kukoo.domain.Task
import java.time.Instant
import java.time.ZoneId

enum class SectionKey(val title: String) {
    OVERDUE("Past start time"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    NO_DEADLINE("No start time"),
    DONE("Done")
}

data class TaskSection(val key: SectionKey, val tasks: List<Task>)

/** Buckets tasks for the Home list. Empty sections are omitted. */
fun groupTasks(tasks: List<Task>, now: Long, zone: ZoneId): List<TaskSection> {
    val endOfToday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    val byDeadline = compareBy<Task> { it.deadline ?: Long.MAX_VALUE }
        .thenByDescending { it.priority.rank }
        .thenBy { it.id }

    val open = tasks.filter { !it.isDone }
    val sections = listOf(
        TaskSection(SectionKey.OVERDUE, open.filter { it.deadline != null && it.deadline < now }.sortedWith(byDeadline)),
        TaskSection(SectionKey.TODAY, open.filter { it.deadline != null && it.deadline in now..endOfToday }.sortedWith(byDeadline)),
        TaskSection(SectionKey.UPCOMING, open.filter { it.deadline != null && it.deadline > endOfToday }.sortedWith(byDeadline)),
        TaskSection(SectionKey.NO_DEADLINE, open.filter { it.deadline == null }.sortedWith(byDeadline)),
        TaskSection(SectionKey.DONE, tasks.filter { it.isDone }.sortedByDescending { it.completedAt ?: 0L })
    )
    return sections.filter { it.tasks.isNotEmpty() }
}

/** "45 min", "1 hr", "1 hr 30 min". */
fun shortDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
}
