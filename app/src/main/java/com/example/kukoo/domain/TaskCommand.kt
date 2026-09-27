package com.example.kukoo.domain

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.MonthDay

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
    /** "Change the time to 6 PM" — the task most recently talked about. */
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
    val notes: String? = null,
    val reminderMin: Int? = null,
    val clearReminder: Boolean = false,
    /** The user has seen the overlap this edit causes and wants it anyway. */
    val keepOverlaps: Boolean = false
) {
    val isEmpty: Boolean
        get() = title == null && deadline == null && !clearDeadline && durationMin == null && priority == null &&
            recurrence == null && notes == null && reminderMin == null && !clearReminder && !keepOverlaps
}

enum class QueryScope { TODAY, ALL_OPEN }

/** How the user answered "use that, pick another time, or keep both?". */
enum class ConflictChoice {
    /** Yes: use the time the assistant suggested. */
    ACCEPT,
    /** Keep both / that time is right: leave things as they are and stop asking. */
    KEEP,
    /** No, not that: the assistant asks for another time. */
    DECLINE
}

/** The kinds of non-task talk the assistant recognises and answers naturally. */
enum class ChatKind {
    /** "hi", "hello", "good morning" */
    GREETING,
    /** "thanks", "thank you" */
    THANKS,
    /** "okay", "yeah", "got it", "sure" — the user is acknowledging, not asking for anything. */
    ACKNOWLEDGE,
    /** "what can you do", "help" */
    HELP
}

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
        val notes: String? = null,
        /** Minutes before the deadline to phone about this task; null for no reminder. */
        val reminderMin: Int? = null,
        /** The user has seen that this overlaps other tasks and wants it anyway ("keep both"). */
        val keepOverlaps: Boolean = false
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

    /** "Any conflicts?": lists the overlaps and offers to fix the first. */
    data object CheckConflicts : TaskCommand

    /** "When am I free?" / "when can I fit an hour?": the next free slot. */
    data class FindTime(val durationMin: Int? = null) : TaskCommand

    /** The answer to a question about an overlap (or an odd start time) the assistant just asked. */
    data class Resolve(val choice: ConflictChoice) : TaskCommand
    /** Pushes every overdue and due-today open task back by [minutes]. */
    data class Snooze(val minutes: Int = 15) : TaskCommand

    data object EndCall : TaskCommand

    /**
     * The user named a task to change but not what to change about it ("modify the gym"). The
     * assistant asks, rather than guessing or refusing — guessing is what turned a misheard
     * "modify the gym" into a new task called "Mortify the gym".
     */
    data class AskWhatToChange(val ref: TaskRef) : TaskCommand

    /**
     * Conversation that is not a task command. A real assistant answers "thanks" and "hello"
     * instead of reciting the list of things it can do.
     */
    data class Chat(val kind: ChatKind) : TaskCommand

    /**
     * "Plan my day" (optionally "for tomorrow" / "for Friday" / "for October 5th"). Opens the planning
     * conversation; [items] are the tasks the user already listed in the same breath, if any.
     */
    data class PlanDay(
        val day: DayRef? = null,
        val date: MonthDay? = null,
        val items: List<PlanItemSpec> = emptyList()
    ) : TaskCommand

    /** More tasks for the plan being made ("also add cooking"). */
    data class PlanAdd(val items: List<PlanItemSpec>) : TaskCommand

    /**
     * A change to the plan being reviewed. With a [target] it edits (or, with [remove], drops) that one task;
     * without one it changes the plan itself: a later start ([at]) or another day ([day] / [date]). With
     * nothing at all it means "not like this" and the assistant asks what to change.
     */
    data class PlanChange(
        val target: String? = null,
        val at: LocalTime? = null,
        val durationMin: Int? = null,
        val priority: Priority? = null,
        val remove: Boolean = false,
        val day: DayRef? = null,
        val date: MonthDay? = null
    ) : TaskCommand

    /** "Yes, add it": the proposed plan becomes real tasks. */
    data object PlanApprove : TaskCommand

    /**
     * "Add one extra task for an hour based on my goals": the assistant picks the task, from the user's habits,
     * goals and interests. [durationMin] and [day] are only what the user said. Heard while a suggestion is open,
     * it means "something else" (or, with a length, "make it that long").
     */
    data class SuggestTask(val durationMin: Int? = null, val day: DayRef? = null) : TaskCommand

    /** Reverts the most recent change made by add / update / complete / reopen / delete. */
    data object Undo : TaskCommand

    /** The utterance did not map to a supported command. */
    data class Unsupported(val heard: String) : TaskCommand

    /**
     * The on-device model is not resident yet, so nothing has understood the request. Understanding
     * is the model's job alone, so the assistant says so plainly rather than guessing with patterns.
     */
    data object NotReady : TaskCommand
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
    val plan: Plan? = null,
    /** True when this reply wrote to the task list, whatever command it answered (so Undo can be offered). */
    val changedTasks: Boolean = false
) {
    val isSuccess: Boolean get() = outcome == Outcome.OK
}

/** What a question the assistant just asked about a conflict is about. */
enum class QuestionKind {
    /** The task overlaps another: use the suggested time, pick another, or keep both. */
    OVERLAP,
    /** The start time is in the middle of the night, so probably a misheard AM/PM. */
    ODD_HOUR,
    /** A task has no start time and the assistant suggested one. */
    SCHEDULE,
    /** The assistant picked a task from the user's habits and goals and asked whether to add it. */
    SUGGESTION
}

/**
 * The open question, as the parser needs to see it: a plain "yes" or "no" only means something once it
 * is known what was asked. [forDraft] is true while a new task is still being set up.
 */
data class ConflictQuestion(
    val kind: QuestionKind,
    val hasSuggestion: Boolean,
    val forDraft: Boolean,
    val taskTitle: String
)
