package com.example.kukoo.domain

/** What a new task needs before it can be added by voice, in the order the assistant asks for it. */
enum class DraftField { TITLE, DEADLINE, DURATION, PRIORITY, REMINDER }

/**
 * A task that is still being set up in conversation. Every field the user has not given yet is
 * null; the engine keeps asking until [nextMissing] is null and only then adds the task.
 *
 * [reminderMin] is asked for last, once the deadline is known: [NO_REMINDER] means the user said no
 * reminder call, a positive number is minutes before the deadline, and null means not asked yet.
 *
 * [recurrence] and [notes] are optional extras: they are kept when the user mentions them but the
 * assistant never asks for them.
 */
data class TaskDraft(
    val title: String? = null,
    val deadline: DeadlineSpec? = null,
    val durationMin: Int? = null,
    val priority: Priority? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val notes: String? = null,
    val reminderMin: Int? = null
) {
    /** The next required detail to ask for, or null when the task is complete. */
    fun nextMissing(): DraftField? = when {
        title.isNullOrBlank() -> DraftField.TITLE
        deadline == null -> DraftField.DEADLINE
        durationMin == null -> DraftField.DURATION
        priority == null -> DraftField.PRIORITY
        reminderMin == null -> DraftField.REMINDER
        else -> null
    }

    /** True when nothing at all is known, e.g. the user just said "add a task". */
    val isBlank: Boolean
        get() = title.isNullOrBlank() && deadline == null && durationMin == null && priority == null &&
            reminderMin == null && recurrence == Recurrence.NONE && notes.isNullOrBlank()

    /** Details in [other] win over the ones already here; anything [other] leaves out is kept. */
    fun mergedWith(other: TaskDraft): TaskDraft = TaskDraft(
        title = other.title?.takeIf { it.isNotBlank() } ?: title,
        deadline = other.deadline ?: deadline,
        durationMin = other.durationMin ?: durationMin,
        priority = other.priority ?: priority,
        recurrence = if (other.recurrence != Recurrence.NONE) other.recurrence else recurrence,
        notes = other.notes?.takeIf { it.isNotBlank() } ?: notes,
        reminderMin = other.reminderMin ?: reminderMin
    )

    /** The command that adds this task, or null while a required detail is still missing. */
    fun toAddTask(): TaskCommand.AddTask? {
        if (nextMissing() != null) return null
        return TaskCommand.AddTask(
            title!!, deadline, durationMin, priority, recurrence, notes,
            reminderMin = reminderMin?.takeIf { it != NO_REMINDER }
        )
    }

    companion object {
        /** The answer "no reminder", kept apart from null ("not asked yet"). */
        const val NO_REMINDER = 0

        fun of(add: TaskCommand.AddTask) = TaskDraft(
            title = add.title,
            deadline = add.deadline,
            durationMin = add.durationMin,
            priority = add.priority,
            recurrence = add.recurrence,
            notes = add.notes,
            reminderMin = add.reminderMin
        )
    }
}
