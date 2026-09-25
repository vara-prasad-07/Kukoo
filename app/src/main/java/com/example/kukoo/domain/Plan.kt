package com.example.kukoo.domain

import java.time.LocalDate

const val MILLIS_PER_MINUTE = 60_000L

data class TimeRange(val start: Long, val end: Long) {
    init {
        require(end >= start) { "end must not be before start" }
    }

    val length: Long get() = end - start
    val minutes: Int get() = (length / MILLIS_PER_MINUTE).toInt()
}

/** Where a plan was computed. Only [LOCAL] exists until the Office Kit handoff is built. */
enum class PlanSource(val label: String) {
    LOCAL("Computed on this device"),
    OFFICE_KIT("Computed on the laptop (Office Kit)")
}

/** One contiguous slot of work. A split task produces several blocks with the same [taskId]. */
data class ScheduledBlock(
    val taskId: Long,
    val title: String,
    val priority: Priority,
    val start: Long,
    val end: Long,
    val part: Int,
    val partCount: Int
) {
    val minutes: Int get() = ((end - start) / MILLIS_PER_MINUTE).toInt()
}

enum class ConflictKind {
    /** All the work fits but finishes after the deadline. */
    LATE,
    /** Part of the work does not fit in the free time at all. */
    NO_ROOM
}

/**
 * [minutes] is exact: for [ConflictKind.LATE] the minutes past the deadline (rounded up),
 * for [ConflictKind.NO_ROOM] the minutes of work that could not be placed.
 */
data class Conflict(
    val taskId: Long,
    val title: String,
    val kind: ConflictKind,
    val minutes: Int,
    val deadline: Long?
)

data class Plan(
    val scope: PlanScope,
    val date: LocalDate,
    val window: TimeRange,
    val blocks: List<ScheduledBlock>,
    val conflicts: List<Conflict>,
    val source: PlanSource,
    val generatedAt: Long
) {
    val isEmpty: Boolean get() = blocks.isEmpty() && conflicts.isEmpty()
    val totalMinutes: Int get() = blocks.sumOf { it.minutes }
    fun conflictFor(taskId: Long): Conflict? = conflicts.firstOrNull { it.taskId == taskId }
}

data class PlannerConfig(
    val workStartHour: Int = 9,
    val workEndHour: Int = 18,
    val afternoonStartHour: Int = 12,
    /** Smallest slice a task may be split into. */
    val minChunkMin: Int = 25,
    /** Plans start on the next multiple of this many minutes. */
    val slotMin: Int = 5,
    /** Deadline time used when only a day is given ("move it to tomorrow" on a task with no time). */
    val defaultDeadlineHour: Int = 18
)
