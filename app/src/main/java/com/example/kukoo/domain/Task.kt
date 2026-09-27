package com.example.kukoo.domain

enum class Priority(val label: String, val rank: Int) {
    HIGH("High", 3),
    MEDIUM("Medium", 2),
    LOW("Low", 1);

    companion object {
        fun fromName(name: String): Priority =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
    }
}

enum class TaskStatus { OPEN, DONE }

/** How often a task comes back after it is completed. */
enum class Recurrence(val label: String) {
    NONE("Never"),
    DAILY("Daily"),
    WEEKDAYS("Weekdays"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly");

    companion object {
        fun fromName(name: String?): Recurrence =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

/**
 * A task as described in the implementation document:
 * title, deadline, duration, priority, status.
 * [deadline] is epoch millis, or null when the task has no deadline.
 */
data class Task(
    val id: Long = 0,
    val title: String,
    val deadline: Long? = null,
    val durationMin: Int = DEFAULT_DURATION_MIN,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.OPEN,
    val createdAt: Long,
    val completedAt: Long? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val notes: String? = null,
    /** Minutes before [deadline] that the assistant phones about this task; null means no reminder call. */
    val reminderMin: Int? = null
) {
    val isDone: Boolean get() = status == TaskStatus.DONE

    /** When the reminder call rings, or null when the task has no reminder or no deadline. */
    val reminderAt: Long? get() = reminderTime(deadline, reminderMin)

    companion object {
        const val MIN_REMINDER_MIN = 1
        const val MAX_REMINDER_MIN = 24 * 60
        val REMINDER_PRESETS_MIN = listOf(30, 15, 10, 5, 3)

        fun reminderTime(deadline: Long?, reminderMin: Int?): Long? =
            if (deadline == null || reminderMin == null) null else deadline - reminderMin * 60_000L

        const val DEFAULT_DURATION_MIN = 30
        const val MIN_DURATION_MIN = 5
        const val MAX_DURATION_MIN = 480
        const val MAX_TITLE_LENGTH = 120
        const val MAX_NOTES_LENGTH = 500
    }
}

/** Persistence seam. Synchronous on purpose; callers run it off the main thread. */
interface TaskStore {
    fun all(): List<Task>
    fun get(id: Long): Task?
    /** Inserts [task] (its id is ignored) and returns the stored copy with its new id. */
    fun insert(task: Task): Task
    fun update(task: Task)
    fun delete(id: Long): Boolean
    fun deleteAll()
}
