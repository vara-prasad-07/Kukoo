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

/** Where a plan was computed. Everything runs on the device. */
enum class PlanSource(val label: String) {
    LOCAL("Computed on this device")
}

/**
 * One task on the day's timeline. A task the user scheduled sits at its own start time; a
 * [proposed] one has no start time yet and is only a suggestion, saved when the user accepts it.
 * [overlapsWith] names the other tasks this one shares time with.
 */
data class ScheduledBlock(
    val taskId: Long,
    val title: String,
    val priority: Priority,
    val start: Long,
    val end: Long,
    val proposed: Boolean = false,
    val overlapsWith: List<String> = emptyList()
) {
    val minutes: Int get() = ((end - start) / MILLIS_PER_MINUTE).toInt()
}

/** Two tasks whose time overlaps, for [minutes] minutes starting at [start]. */
data class Conflict(
    val firstId: Long,
    val firstTitle: String,
    val secondId: Long,
    val secondTitle: String,
    val start: Long,
    val end: Long
) {
    val minutes: Int get() = ((end - start + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
    fun involves(taskId: Long) = firstId == taskId || secondId == taskId
}

data class Plan(
    val scope: PlanScope,
    val date: LocalDate,
    val blocks: List<ScheduledBlock>,
    val conflicts: List<Conflict>,
    /** Tasks without a start time that there was no free room for on this day. */
    val unplaced: List<String>,
    val source: PlanSource,
    val generatedAt: Long
) {
    val isEmpty: Boolean get() = blocks.isEmpty() && conflicts.isEmpty() && unplaced.isEmpty()
    val totalMinutes: Int get() = blocks.sumOf { it.minutes }
}

data class PlannerConfig(
    /** Slots and "did you mean" checks use the hours the user is awake. */
    val awakeStartHour: Int = 7,
    val awakeEndHour: Int = 23,
    /** A start before this hour (0:00 to 4:59) is almost always a misheard AM/PM, so it is confirmed. */
    val oddHourEndHour: Int = 5,
    val afternoonStartHour: Int = 12,
    /** Suggested start times land on a multiple of this many minutes. */
    val slotMin: Int = 5,
    /** Start time used when only a day is given ("move it to tomorrow" on a task with no time). */
    val defaultDeadlineHour: Int = 18
)
