package com.example.kukoo.domain

import java.time.DayOfWeek
import java.time.LocalTime

/** A calendar day named relative to "today", resolved deterministically by [DeadlineResolver]. */
sealed interface DayRef {
    data object Today : DayRef
    data object Tomorrow : DayRef
    /** The next occurrence of this weekday, strictly after today. */
    data class Weekday(val day: DayOfWeek) : DayRef
}

/** How a command says "when". The LLM/parser never computes timestamps itself. */
sealed interface DeadlineSpec {
    data class Exact(val epochMillis: Long) : DeadlineSpec

    /**
     * Partial description. A missing [day] keeps the task's current day (or today);
     * a missing [time] keeps the task's current time (or the end of the working day).
     */
    data class Relative(val day: DayRef? = null, val time: LocalTime? = null) : DeadlineSpec {
        init {
            require(day != null || time != null) { "Relative deadline needs a day or a time" }
        }
    }
}

sealed interface TaskRef {
    data class ById(val id: Long) : TaskRef
    data class ByTitle(val query: String) : TaskRef
    /** "Change the deadline to 6 PM" — the task most recently talked about. */
    data object Last : TaskRef
}

/** Fields to change on an existing task; null means "leave as is". */
data class TaskPatch(
    val title: String? = null,
    val deadline: DeadlineSpec? = null,
    val clearDeadline: Boolean = false,
    val durationMin: Int? = null,
    val priority: Priority? = null,
    val recurrence: Recurrence? = null,
    /** Blank clears the notes. */
    val notes: String? = null
) {
    val isEmpty: Boolean
        get() = title == null && deadline == null && !clearDeadline && durationMin == null && priority == null &&
            recurrence == null && notes == null
}

enum class QueryScope { TODAY, ALL_OPEN }

enum class PlanScope(val label: String) {
    AFTERNOON("afternoon"),
    DAY("day")
}

/**
 * The fixed tool contract between "whatever understood the user" (rule parser now,
 * local LLM later) and the [TaskEngine]. Nothing here touches the database.
 */
sealed interface TaskCommand {
    data class QueryTasks(val scope: QueryScope = QueryScope.TODAY) : TaskCommand

    data class AddTask(
        val title: String,
        val deadline: DeadlineSpec? = null,
        val durationMin: Int? = null,
        val priority: Priority? = null,
        val recurrence: Recurrence = Recurrence.NONE,
        val notes: String? = null
    ) : TaskCommand

    /**
     * The user wants a new task but has not named it yet (it may still carry other details, e.g.
     * "add something for tomorrow"). A named task arrives as [AddTask] instead. Spoken adds of either
     * kind go through [TaskEngine.executeSpoken], which asks for whatever is still missing.
     */
    data class StartTask(val draft: TaskDraft = TaskDraft()) : TaskCommand

    /** The user's answer to the question about the task being set up; merged into the pending draft. */
    data class FillTask(val draft: TaskDraft) : TaskCommand

    /** Drop the task being set up ("never mind", "cancel"). */
    data object DiscardDraft : TaskCommand

    data class UpdateTask(val ref: TaskRef, val patch: TaskPatch) : TaskCommand
    data class CompleteTask(val ref: TaskRef) : TaskCommand
    data class ReopenTask(val ref: TaskRef) : TaskCommand
    data class DeleteTask(val ref: TaskRef) : TaskCommand
    data class Replan(val scope: PlanScope) : TaskCommand
    /** Pushes every overdue and due-today open task back by [minutes]. */
    data class Snooze(val minutes: Int = 15) : TaskCommand

    data object EndCall : TaskCommand

    /** Reverts the most recent change made by add / update / complete / reopen / delete. */
    data object Undo : TaskCommand

    /** The utterance did not map to a supported command. */
    data class Unsupported(val heard: String) : TaskCommand
}

enum class Outcome {
    /** The command ran and state changed (or the query was answered). */
    OK,
    /** Understood, but refused by validation (e.g. deadline in the past, task not found). */
    REJECTED,
    /** More than one task matched, or the reference could not be resolved. */
    NEEDS_CLARIFICATION,
    /**
     * A new task is missing a required detail and the assistant is asking for it. Not a problem, so
     * it is shown like any other reply; the conversation just continues.
     */
    NEEDS_INFO,
    /** The user asked to hang up. */
    END_CALL,
    /** The utterance was not a supported command. */
    UNSUPPORTED
}

/**
 * Result of executing a command. [spoken] is the exact sentence for TTS / the transcript,
 * so the assistant only ever says what actually happened.
 */
data class EngineResult(
    val outcome: Outcome,
    val spoken: String,
    val taskIds: List<Long> = emptyList(),
    val plan: Plan? = null
) {
    val isSuccess: Boolean get() = outcome == Outcome.OK
}
