package com.example.kukoo.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * The single place where task state changes. Voice, typed input and the UI forms all go
 * through [execute]; the LLM never touches the store. Every command is validated first and
 * answered with the exact sentence describing what happened.
 */
class TaskEngine(
    private val store: TaskStore,
    private val replanner: Replanner,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val config: PlannerConfig = PlannerConfig()
) {
    private val mutex = Mutex()
    private val resolver = DeadlineResolver(clock, config)
    val format = TimeFormat(clock)

    /** The task most recently talked about, so "change the deadline to 6 PM" has a target. */
    private var lastTaskId: Long? = null

    /** Reverses the last successful mutation. Single step; cleared when a session starts or ends. */
    private var undoAction: (() -> Unit)? = null

    /** The new task being set up by voice, or null. Only ever changed while holding [mutex]. */
    @Volatile
    private var draft: TaskDraft? = null

    /**
     * What is known about the task being set up, so a parser can read a short answer ("an hour",
     * "high") in the context of the question that was just asked.
     */
    val pendingDraft: TaskDraft? get() = draft

    fun resetContext() {
        lastTaskId = null
        undoAction = null
        draft = null
    }

    fun tasks(): List<Task> = store.all()

    /**
     * Runs one command exactly as given. Forms and the UI use this: an [TaskCommand.AddTask] here is
     * added immediately, with defaults for whatever it leaves out.
     */
    suspend fun execute(command: TaskCommand): EngineResult = mutex.withLock { dispatch(command) }

    /**
     * Runs a command that came from what the user said or typed. Unlike [execute], adding a task
     * this way needs a name, a deadline, a duration and a priority: anything missing is asked for,
     * one detail at a time, and the task is only added once all four are known.
     */
    suspend fun executeSpoken(command: TaskCommand): EngineResult = mutex.withLock { dispatchSpoken(command) }

    private suspend fun dispatch(command: TaskCommand): EngineResult = when (command) {
        is TaskCommand.QueryTasks -> query(command.scope)
        is TaskCommand.AddTask -> add(command)
        is TaskCommand.StartTask -> startDraft(command.draft)
        is TaskCommand.FillTask -> fillDraft(command.draft)
        TaskCommand.DiscardDraft -> discardDraft()
        is TaskCommand.UpdateTask -> update(command)
        is TaskCommand.CompleteTask -> setDone(command.ref, done = true)
        is TaskCommand.ReopenTask -> setDone(command.ref, done = false)
        is TaskCommand.DeleteTask -> delete(command)
        is TaskCommand.Replan -> replan(command.scope)
        TaskCommand.Undo -> undo()
        is TaskCommand.Snooze -> snooze(command.minutes)
        TaskCommand.EndCall -> EngineResult(Outcome.END_CALL, "Okay, goodbye. Your tasks are up to date.")
        is TaskCommand.AskWhatToChange -> askWhatToChange(command.ref)
        is TaskCommand.Chat -> chat(command.kind)
        is TaskCommand.Unsupported -> EngineResult(Outcome.UNSUPPORTED, CAPABILITIES)
        TaskCommand.NotReady -> EngineResult(
            Outcome.REJECTED,
            "I'm still getting the on-device model ready. Give me a few seconds and say that again.",
        )
    }

    /** "Change the gym" — the task is clear, the change is not, so ask instead of guessing. */
    private fun askWhatToChange(ref: TaskRef): EngineResult {
        val task = when (val r = resolve(ref, prefer = { !it.isDone })) {
            is Resolved.Ok -> r.task
            is Resolved.Fail -> return r.result
        }
        lastTaskId = task.id
        val when_ = task.deadline?.let { " It is due ${format.deadline(it)}." }.orEmpty()
        return EngineResult(
            Outcome.NEEDS_INFO,
            "What would you like to change about ${task.title}?$when_ " +
                "You can move it to another time, change how long it takes, or change its priority.",
            taskIds = listOf(task.id)
        )
    }

    private fun chat(kind: ChatKind): EngineResult {
        val pending = draft
        val message = when (kind) {
            ChatKind.GREETING -> "${format.greeting()}. What would you like to do?"
            ChatKind.THANKS -> "You're welcome."
            ChatKind.ACKNOWLEDGE -> "Okay."
            ChatKind.HELP -> CAPABILITIES
        }
        // Mid-dialog the question still needs answering, so repeat it after the pleasantry.
        if (pending != null && kind != ChatKind.HELP) return ask(pending, prefix = "$message ")
        val tail = when (kind) {
            ChatKind.THANKS, ChatKind.ACKNOWLEDGE -> " Anything else?"
            else -> ""
        }
        return EngineResult(Outcome.OK, message + tail)
    }

    private suspend fun dispatchSpoken(command: TaskCommand): EngineResult {
        val pending = draft
        return when (command) {
            is TaskCommand.AddTask -> startDraft(TaskDraft.of(command))
            // A misheard "gym every day at 12pm" is still a new task, and needs the same questions.
            is TaskCommand.UpdateTask ->
                selfHealToAdd(command)?.let { startDraft(TaskDraft.of(it)) } ?: withDraftReminder(command)
            // chat() re-asks the pending question itself, so it must not also get a draft reminder.
            is TaskCommand.StartTask, is TaskCommand.FillTask, TaskCommand.DiscardDraft,
            is TaskCommand.Chat -> dispatch(command)
            is TaskCommand.Unsupported ->
                if (pending != null) ask(pending, prefix = "Sorry, I didn't catch that. ") else dispatch(command)
            TaskCommand.EndCall -> {
                if (pending == null) return dispatch(command)
                draft = null
                EngineResult(
                    Outcome.END_CALL,
                    "Okay, goodbye. I didn't add ${pending.title ?: "the new task"} because it wasn't finished."
                )
            }
            else -> withDraftReminder(command)
        }
    }

    /** Spoken opening of a scheduled call. */
    fun briefing(): String {
        val today = dueToday()
        val core = querySentence(today)
        val workload = today.due.sumOf { it.durationMin } + today.overdue.sumOf { it.durationMin }
        val extra = if (today.due.size + today.overdue.size >= 2) {
            " That is about ${format.duration(roundToFive(workload))} of work."
        } else ""
        return "${format.greeting()}. $core$extra What would you like to do?"
    }

    /**
     * Spoken opening of a reminder call: only the task the reminder is for, never the rest of the list.
     * Null when that task no longer needs a call (deleted or already done).
     */
    fun taskBriefing(taskId: Long): String? {
        val task = store.get(taskId)?.takeIf { !it.isDone } ?: return null
        lastTaskId = task.id
        val now = clock.millis()
        val deadline = task.deadline
        val lead = when {
            deadline == null -> "Reminder: ${task.title}."
            deadline > now -> {
                val left = ((deadline - now + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
                "Reminder: ${task.title} is due ${format.deadline(deadline)}, in ${spanWord(left)}."
            }
            else -> "Reminder: ${task.title} was due ${format.deadline(deadline)}."
        }
        val notes = task.notes?.let { " Your note says: $it." }.orEmpty()
        return "$lead It takes ${format.duration(task.durationMin)}, ${task.priority.label.lowercase()} priority.$notes " +
            "Want to mark it done, move it, or change something?"
    }

    private fun spanWord(minutes: Int) = if (minutes < 60) format.minutes(minutes) else format.duration(minutes)

    /** Why [reminderMin] cannot be used for a task due at [deadline], or null when it is fine. */
    private fun reminderError(title: String, deadline: Long?, reminderMin: Int, now: Long): String? {
        if (reminderMin < Task.MIN_REMINDER_MIN || reminderMin > Task.MAX_REMINDER_MIN) {
            return "A reminder should be between ${Task.MIN_REMINDER_MIN} minute and ${Task.MAX_REMINDER_MIN / 60} hours before the deadline."
        }
        if (deadline == null) return "A reminder needs a deadline to count back from, so set a deadline for $title first."
        val rings = deadline - reminderMin * MILLIS_PER_MINUTE
        if (rings <= now) {
            return "A ${spanWord(reminderMin)} reminder for $title would ring ${format.deadline(rings)}, " +
                "which has already passed. Pick a shorter reminder."
        }
        return null
    }

    // ---- queries -------------------------------------------------------------------------

    private class DueToday(val overdue: List<Task>, val due: List<Task>)

    private fun dueToday(): DueToday {
        val now = clock.millis()
        val endOfToday = resolver.endOfDay(resolver.today())
        val open = store.all().filter { !it.isDone && it.deadline != null }.sortedBy { it.deadline }
        return DueToday(
            overdue = open.filter { it.deadline!! < now },
            due = open.filter { it.deadline!! in now..endOfToday }
        )
    }

    private fun querySentence(d: DueToday): String {
        if (d.overdue.isEmpty() && d.due.isEmpty()) return "Nothing is due today."
        val parts = mutableListOf<String>()
        if (d.overdue.isNotEmpty()) {
            val verb = if (d.overdue.size == 1) "task is" else "tasks are"
            parts += "${d.overdue.size} $verb overdue: ${listWithTimes(d.overdue, withDay = true)}."
        }
        parts += if (d.due.isNotEmpty()) {
            val noun = if (d.due.size == 1) "task" else "tasks"
            "You have ${d.due.size} $noun due today: ${listWithTimes(d.due, withDay = false)}."
        } else {
            "Nothing else is due today."
        }
        return parts.joinToString(" ")
    }

    private fun query(scope: QueryScope): EngineResult {
        return when (scope) {
            QueryScope.TODAY -> {
                val d = dueToday()
                val listed = d.overdue + d.due
                if (listed.size == 1) lastTaskId = listed.first().id
                EngineResult(Outcome.OK, querySentence(d), taskIds = listed.map { it.id })
            }
            QueryScope.ALL_OPEN -> {
                val open = store.all().filter { !it.isDone }
                    .sortedWith(compareBy<Task> { it.deadline ?: Long.MAX_VALUE }.thenBy { it.id })
                if (open.isEmpty()) return EngineResult(Outcome.OK, "You have no open tasks.")
                val noun = if (open.size == 1) "task" else "tasks"
                if (open.size == 1) lastTaskId = open.first().id
                EngineResult(
                    Outcome.OK,
                    "You have ${open.size} open $noun: ${listWithTimes(open, withDay = true)}.",
                    taskIds = open.map { it.id }
                )
            }
        }
    }

    private fun listWithTimes(tasks: List<Task>, withDay: Boolean): String {
        val shown = tasks.take(MAX_SPOKEN_ITEMS).map { t ->
            val d = t.deadline
            when {
                d == null -> "${t.title}, no deadline"
                withDay -> "${t.title}, ${format.deadline(d)}"
                else -> "${t.title} at ${format.clockTime(d)}"
            }
        }
        val more = tasks.size - shown.size
        return naturalJoin(if (more > 0) shown + "$more more" else shown)
    }

    // ---- mutations -----------------------------------------------------------------------

    /**
     * "gym every day at 12pm" can be misheard as an edit of a task that doesn't exist. When the target title
     * matches nothing and the patch says when / how important / how often, treat it as the task the user
     * meant to create. A patch that only renames or edits notes still reports "couldn't find".
     */
    private fun selfHealToAdd(cmd: TaskCommand.UpdateTask): TaskCommand.AddTask? {
        val ref = cmd.ref as? TaskRef.ByTitle ?: return null
        val patch = cmd.patch
        val describesATask = (patch.deadline != null && !patch.clearDeadline) ||
            patch.priority != null || (patch.recurrence != null && patch.recurrence != Recurrence.NONE)
        if (!describesATask || cleanTitle(ref.query).isEmpty()) return null
        if (TitleMatcher.resolve(ref.query, store.all()) != TitleMatcher.Match.None) return null
        return TaskCommand.AddTask(
            title = ref.query,
            deadline = patch.deadline,
            durationMin = patch.durationMin,
            priority = patch.priority,
            recurrence = patch.recurrence ?: Recurrence.NONE,
            notes = patch.notes
        )
    }

    /** Next due time for a repeating task, moved past "now" so finishing a stale one doesn't spawn an overdue copy. */
    private fun nextDeadline(task: Task): Long? {
        var next = task.deadline?.let { resolver.nextOccurrence(it, task.recurrence) } ?: return null
        val now = clock.millis()
        var guard = 0
        while (next <= now && guard++ < MAX_CATCH_UP) next = resolver.nextOccurrence(next, task.recurrence)
        return next
    }

    private fun snooze(minutes: Int): EngineResult {
        if (minutes < 1 || minutes > MAX_SNOOZE_MIN) {
            return reject("I can snooze for between 1 minute and ${MAX_SNOOZE_MIN / 60} hours.")
        }
        val endOfToday = resolver.endOfDay(resolver.today())
        val targets = store.all().filter { !it.isDone && it.deadline != null && it.deadline <= endOfToday }
        if (targets.isEmpty()) return EngineResult(Outcome.OK, "Nothing is due today, so there is nothing to snooze.")

        val shift = minutes * MILLIS_PER_MINUTE
        targets.forEach { store.update(it.copy(deadline = it.deadline!! + shift)) }
        undoAction = { targets.forEach { store.update(it) } }
        return EngineResult(Outcome.OK, "Snoozed tasks by $minutes minutes.", taskIds = targets.map { it.id })
    }

    private fun recurrenceWord(r: Recurrence) = when (r) {
        Recurrence.NONE -> "never"
        Recurrence.DAILY -> "daily"
        Recurrence.WEEKDAYS -> "on weekdays"
        Recurrence.WEEKLY -> "weekly"
        Recurrence.MONTHLY -> "monthly"
    }

    private fun repeatWord(r: Recurrence) = when (r) {
        Recurrence.NONE -> "never"
        Recurrence.DAILY -> "every day"
        Recurrence.WEEKDAYS -> "every weekday"
        Recurrence.WEEKLY -> "every week"
        Recurrence.MONTHLY -> "every month"
    }

    private fun cleanNotes(raw: String?): String? = raw?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    private fun undo(): EngineResult {
        val action = undoAction ?: return reject("There is nothing to undo.")
        undoAction = null
        action()
        return EngineResult(Outcome.OK, "Undid last action.")
    }

    // ---- adding a task by conversation ---------------------------------------------------

    /** What one turn taught us: the updated draft, what to read back, and what was refused. */
    private class Absorbed(val draft: TaskDraft, val heard: List<String>, val problems: List<String>)

    /**
     * Folds [incoming] into [base], checking each new detail on its own so a bad one is refused
     * right away ("that time has passed") and asked for again, instead of failing at the very end.
     */
    private fun absorb(base: TaskDraft, incoming: TaskDraft): Absorbed {
        var d = base
        val heard = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val now = clock.millis()

        incoming.title?.let { raw ->
            val title = cleanTitle(raw)
            when {
                title.isEmpty() -> Unit
                title.length > Task.MAX_TITLE_LENGTH -> problems += "That task name is too long."
                else -> { d = d.copy(title = title); heard += title }
            }
        }
        incoming.deadline?.let { spec ->
            val at = resolver.resolve(spec, existing = null)
            if (at < now) problems += "${format.deadline(at).replaceFirstChar { it.uppercase() }} has already passed."
            else { d = d.copy(deadline = spec); heard += "due ${format.deadline(at)}" }
        }
        incoming.durationMin?.let { minutes ->
            val error = durationError(minutes)
            if (error != null) problems += error
            else { d = d.copy(durationMin = minutes); heard += format.duration(minutes) }
        }
        incoming.priority?.let { d = d.copy(priority = it); heard += "${it.label.lowercase()} priority" }
        if (incoming.recurrence != Recurrence.NONE) {
            d = d.copy(recurrence = incoming.recurrence)
            heard += "repeats ${repeatWord(incoming.recurrence)}"
        }
        cleanNotes(incoming.notes)?.let { notes ->
            if (notes.length > Task.MAX_NOTES_LENGTH) problems += "Those notes are too long."
            else d = d.copy(notes = notes)
        }
        incoming.reminderMin?.let { minutes -> d = d.copy(reminderMin = minutes) }

        // A reminder counts back from the deadline, and time keeps moving while the questions are
        // answered, so it is checked on every turn: one that would already have rung is refused
        // and asked for again rather than failing when the task is finally saved.
        val reminder = d.reminderMin
        if (reminder == TaskDraft.NO_REMINDER) {
            if (incoming.reminderMin == reminder) heard += "no reminder call"
        } else if (reminder != null) {
            // Without a deadline yet there is nothing to clash with; it is checked when the deadline arrives.
            val at = d.deadline?.let { resolver.resolve(it, existing = null) }
                ?: (now + (Task.MAX_REMINDER_MIN + 1) * MILLIS_PER_MINUTE)
            val error = reminderError(d.title ?: "the task", at, reminder, now)
            if (error != null) {
                problems += error
                d = d.copy(reminderMin = null)
            } else if (incoming.reminderMin == reminder) {
                heard += "a reminder call ${spanWord(reminder)} before"
            }
        }
        return Absorbed(d, heard, problems)
    }

    /** "Add ..." from the user: a new task, unless it is really the name they were just asked for. */
    private fun startDraft(incoming: TaskDraft): EngineResult {
        val pending = draft
        val incomingTitle = incoming.title?.let(::cleanTitle)
        // While the assistant is waiting for a name, whatever names a task completes that draft.
        val continuing = pending != null &&
            (pending.title == null || pending.title.equals(incomingTitle, ignoreCase = true))
        val base = if (continuing) pending!! else TaskDraft()
        val dropped = if (pending != null && !continuing && pending.title != null) {
            "Dropping the unfinished ${pending.title}. "
        } else ""
        return progress(base, incoming, prefix = dropped, apologizeIfNothingNew = false)
    }

    /** The user's reply to a question. With no task in progress it simply starts one. */
    private fun fillDraft(incoming: TaskDraft): EngineResult =
        progress(draft ?: TaskDraft(), incoming, prefix = "", apologizeIfNothingNew = true)

    private fun progress(
        base: TaskDraft,
        incoming: TaskDraft,
        prefix: String,
        apologizeIfNothingNew: Boolean
    ): EngineResult {
        val absorbed = absorb(base, incoming)
        var merged = absorbed.draft
        // Too close to the deadline for any reminder call: say so instead of asking a question with no answer.
        val noRoomForReminder = merged.nextMissing() == DraftField.REMINDER && reminderOptions(merged).isEmpty()
        if (noRoomForReminder) merged = merged.copy(reminderMin = TaskDraft.NO_REMINDER)
        if (merged.nextMissing() == null) return finishDraft(merged, noRoomForReminder)

        draft = merged
        val sorry = if (apologizeIfNothingNew && absorbed.heard.isEmpty() && absorbed.problems.isEmpty()) {
            "Sorry, I didn't catch that. "
        } else ""
        return ask(merged, prefix = prefix + sorry, heard = absorbed.heard, problems = absorbed.problems)
    }

    /** The question for the next missing detail, after reading back what was just understood. */
    private fun ask(
        d: TaskDraft,
        prefix: String = "",
        heard: List<String> = emptyList(),
        problems: List<String> = emptyList()
    ): EngineResult {
        val spoken = buildString {
            append(prefix)
            problems.forEach { append(it).append(' ') }
            if (heard.isNotEmpty()) append("Got it: ").append(heard.joinToString(", ")).append(". ")
            append(question(d))
        }
        return EngineResult(Outcome.NEEDS_INFO, spoken)
    }

    private fun question(d: TaskDraft): String = when (d.nextMissing()) {
        DraftField.TITLE, null -> "What should I call the task?"
        DraftField.DEADLINE -> "When is ${d.title} due?"
        DraftField.DURATION -> "How long will ${d.title} take?"
        DraftField.PRIORITY -> "Is ${d.title} high, medium or low priority?"
        DraftField.REMINDER -> reminderQuestion(d)
    }

    /** Only offers lead times that would still ring in the future, so no suggestion can clash with now. */
    private fun reminderQuestion(d: TaskDraft): String {
        val options = reminderOptions(d)
        val ask = "Do you want a reminder call before ${d.title}?"
        return if (options.isEmpty()) "$ask Say how many minutes before, or say none."
        else "$ask I can call you ${orJoin(options.map { it.toString() })} minutes before, or say none."
    }

    /** The preset lead times that still fit between now and the draft's deadline, longest first. */
    private fun reminderOptions(d: TaskDraft): List<Int> {
        val due = d.deadline?.let { resolver.resolve(it, existing = null) } ?: return emptyList()
        val now = clock.millis()
        return Task.REMINDER_PRESETS_MIN.filter { due - it * MILLIS_PER_MINUTE > now }.take(MAX_SUGGESTED_REMINDERS)
    }

    /** Appended to the answer of an unrelated command so the user is brought back to the open question. */
    private suspend fun withDraftReminder(command: TaskCommand): EngineResult {
        val pending = draft
        val result = dispatch(command)
        if (pending == null) return result
        val lead = pending.title?.let { "Back to $it." } ?: "Back to the new task."
        return result.copy(spoken = "${result.spoken} $lead ${question(pending)}")
    }

    private fun discardDraft(): EngineResult {
        val pending = draft ?: return reject("There is no new task to cancel.")
        draft = null
        return EngineResult(Outcome.OK, "Okay, I won't add ${pending.title ?: "the new task"}.")
    }

    private fun finishDraft(complete: TaskDraft, noRoomForReminder: Boolean = false): EngineResult {
        draft = null
        val result = add(complete.toAddTask()!!)
        // The plain add() sentence never mentions priority, which is now always a spoken answer.
        return if (result.isSuccess) {
            val noReminder = if (noRoomForReminder) " There isn't enough time before the deadline for a reminder call." else ""
            result.copy(spoken = "${result.spoken} ${complete.priority!!.label} priority.$noReminder")
        } else result
    }

    private fun add(cmd: TaskCommand.AddTask): EngineResult {
        val now = clock.millis()
        val title = cleanTitle(cmd.title)
        if (title.isEmpty()) return reject("I need a name for the task.")
        if (title.length > Task.MAX_TITLE_LENGTH) return reject("That task name is too long.")

        val duration = cmd.durationMin ?: Task.DEFAULT_DURATION_MIN
        durationError(duration)?.let { return reject(it) }

        val deadline = cmd.deadline?.let { resolver.resolve(it, existing = null) }
        if (deadline != null && deadline < now) {
            return reject("${format.deadline(deadline)} has already passed, so I didn't add $title.")
        }

        val notes = cleanNotes(cmd.notes)
        if (notes != null && notes.length > Task.MAX_NOTES_LENGTH) return reject("Those notes are too long.")

        if (cmd.reminderMin != null) {
            reminderError(title, deadline, cmd.reminderMin, now)?.let { return reject(it) }
        }

        // A task the user already has, added again, is nearly always a misunderstanding rather than
        // an intention. It is still added — refusing would be worse — but it is pointed out, so
        // three identical "Gym" tasks cannot pile up without the user ever being told.
        val duplicate = store.all().firstOrNull { !it.isDone && it.title.equals(title, ignoreCase = true) }

        val saved = store.insert(
            Task(
                title = title,
                deadline = deadline,
                durationMin = duration,
                priority = cmd.priority ?: Priority.MEDIUM,
                createdAt = now,
                recurrence = cmd.recurrence,
                notes = notes,
                reminderMin = cmd.reminderMin
            )
        )
        lastTaskId = saved.id
        undoAction = { store.delete(saved.id); if (lastTaskId == saved.id) lastTaskId = null }
        val whenPart = if (deadline != null) "due ${format.deadline(deadline)}" else "with no deadline"
        if (cmd.recurrence != Recurrence.NONE && deadline != null &&
            (cmd.deadline as? DeadlineSpec.Relative)?.day.let { it == null || it == DayRef.Today } &&
            cmd.deadline is DeadlineSpec.Relative &&
            resolver.toLocal(deadline).toLocalDate() == resolver.today().plusDays(1)
        ) {
            // Today's time had already passed, so the series starts tomorrow.
            return EngineResult(
                Outcome.OK,
                "Added '$title' starting ${format.deadline(deadline)} (repeats ${recurrenceWord(cmd.recurrence)}).",
                taskIds = listOf(saved.id)
            )
        }
        val repeats = if (cmd.recurrence != Recurrence.NONE) " Repeats ${repeatWord(cmd.recurrence)}." else ""
        val alsoHave = duplicate?.let { other ->
            val other_ = other.deadline?.let { format.deadline(it) } ?: "no deadline"
            " You already had another $title, $other_."
        }.orEmpty()
        val reminder = cmd.reminderMin?.let { " I'll call you ${spanWord(it)} before." }.orEmpty()
        return EngineResult(
            Outcome.OK,
            "Added $title, $whenPart, ${format.duration(duration)}.$repeats$reminder$alsoHave",
            taskIds = listOf(saved.id)
        )
    }

    private fun update(cmd: TaskCommand.UpdateTask): EngineResult {
        if (cmd.patch.isEmpty) return reject("There was nothing to change.")
        selfHealToAdd(cmd)?.let { return add(it) }
        val task = when (val r = resolve(cmd.ref, prefer = { !it.isDone })) {
            is Resolved.Fail -> return r.result
            is Resolved.Ok -> r.task
        }
        if (task.isDone) return reject("${task.title} is already done. Reopen it first to change it.")

        val patch = cmd.patch
        val now = clock.millis()
        val changes = mutableListOf<String>()
        var updated = task

        patch.title?.let {
            val title = cleanTitle(it)
            if (title.isEmpty()) return reject("I need a name for the task.")
            if (title.length > Task.MAX_TITLE_LENGTH) return reject("That task name is too long.")
            if (title != task.title) {
                updated = updated.copy(title = title)
                changes += "renamed to $title"
            }
        }

        var deadlineOnly = false
        if (patch.clearDeadline) {
            if (task.deadline != null) {
                updated = updated.copy(deadline = null)
                changes += "no deadline"
                deadlineOnly = true
            }
        } else if (patch.deadline != null) {
            val resolved = resolver.resolve(patch.deadline, existing = task.deadline)
            if (resolved != task.deadline) {
                if (resolved < now) {
                    return reject("${format.deadline(resolved)} has already passed, so I left ${task.title} unchanged.")
                }
                updated = updated.copy(deadline = resolved)
                changes += "due ${format.deadline(resolved)}"
                deadlineOnly = true
            }
        }

        // The reminder is only checked when it or the deadline changed: saving an unrelated edit
        // (say, new notes) shortly before a reminder was due must not be refused for it.
        if (patch.clearReminder || (updated.deadline == null && patch.reminderMin == null)) {
            if (task.reminderMin != null) {
                updated = updated.copy(reminderMin = null)
                changes += "no reminder"
                deadlineOnly = false
            }
        } else if (patch.reminderMin != null) {
            if (patch.reminderMin != task.reminderMin || updated.deadline != task.deadline) {
                reminderError(updated.title, updated.deadline, patch.reminderMin, now)?.let { return reject(it) }
            }
            if (patch.reminderMin != task.reminderMin) {
                updated = updated.copy(reminderMin = patch.reminderMin)
                changes += "reminder ${spanWord(patch.reminderMin)} before"
                deadlineOnly = false
            }
        } else if (task.reminderMin != null && updated.deadline != task.deadline) {
            // The deadline moved under an existing reminder: keep it only if it still rings in the future.
            val rings = Task.reminderTime(updated.deadline, task.reminderMin)
            if (rings == null || rings <= now) {
                updated = updated.copy(reminderMin = null)
                changes += "reminder removed because it would already have passed"
                deadlineOnly = false
            }
        }

        patch.durationMin?.let {
            durationError(it)?.let { message -> return reject(message) }
            if (it != task.durationMin) {
                updated = updated.copy(durationMin = it)
                changes += "takes ${format.duration(it)}"
                deadlineOnly = false
            }
        }
        patch.priority?.let {
            if (it != task.priority) {
                updated = updated.copy(priority = it)
                changes += "${it.label.lowercase()} priority"
                deadlineOnly = false
            }
        }
        patch.recurrence?.let {
            if (it != task.recurrence) {
                updated = updated.copy(recurrence = it)
                changes += if (it == Recurrence.NONE) "no longer repeats" else "repeats ${repeatWord(it)}"
                deadlineOnly = false
            }
        }
        patch.notes?.let {
            val notes = cleanNotes(it)
            if (notes != null && notes.length > Task.MAX_NOTES_LENGTH) return reject("Those notes are too long.")
            if (notes != task.notes) {
                updated = updated.copy(notes = notes)
                changes += if (notes == null) "notes cleared" else "notes updated"
                deadlineOnly = false
            }
        }
        if (patch.title != null && updated.title != task.title) deadlineOnly = false

        lastTaskId = task.id
        if (changes.isEmpty()) {
            return EngineResult(Outcome.OK, "${task.title} is already set that way.", taskIds = listOf(task.id))
        }
        store.update(updated)
        undoAction = { store.update(task) }

        val spoken = when {
            deadlineOnly && patch.clearDeadline -> "Done. ${updated.title} no longer has a deadline."
            deadlineOnly -> "Done. ${updated.title} is now due ${format.deadline(updated.deadline!!)}."
            else -> "Updated ${updated.title}: ${changes.joinToString(", ")}."
        }
        return EngineResult(Outcome.OK, spoken, taskIds = listOf(task.id))
    }

    private fun setDone(ref: TaskRef, done: Boolean): EngineResult {
        val task = when (val r = resolve(ref, prefer = { it.isDone == !done })) {
            is Resolved.Fail -> return r.result
            is Resolved.Ok -> r.task
        }
        lastTaskId = task.id
        if (task.isDone == done) {
            val state = if (done) "already done" else "already open"
            return EngineResult(Outcome.OK, "${task.title} is $state.", taskIds = listOf(task.id))
        }
        store.update(
            task.copy(
                status = if (done) TaskStatus.DONE else TaskStatus.OPEN,
                completedAt = if (done) clock.millis() else null
            )
        )
        undoAction = { store.update(task) }
        if (!done) return EngineResult(Outcome.OK, "Okay, ${task.title} is open again.", taskIds = listOf(task.id))

        var spoken = "Done. I marked ${task.title} as done."
        val ids = mutableListOf(task.id)
        if (task.recurrence != Recurrence.NONE) {
            val next = store.insert(
                task.copy(
                    id = 0,
                    deadline = nextDeadline(task),
                    status = TaskStatus.OPEN,
                    createdAt = clock.millis(),
                    completedAt = null
                )
            )
            ids += next.id
            undoAction = { store.update(task); store.delete(next.id) }
            spoken += next.deadline?.let { " The next one is due ${format.deadline(it)}." }
                ?: " It will come back ${repeatWord(task.recurrence)}."
        }
        return EngineResult(Outcome.OK, spoken, taskIds = ids)
    }

    private fun delete(cmd: TaskCommand.DeleteTask): EngineResult {
        val task = when (val r = resolve(cmd.ref, prefer = { !it.isDone })) {
            is Resolved.Fail -> return r.result
            is Resolved.Ok -> r.task
        }
        store.delete(task.id)
        if (lastTaskId == task.id) lastTaskId = null
        // The store assigns a new id on re-insert; the task itself comes back unchanged.
        undoAction = { lastTaskId = store.insert(task).id }
        return EngineResult(Outcome.OK, "Done. I deleted ${task.title}.", taskIds = listOf(task.id))
    }

    // ---- replanning ----------------------------------------------------------------------

    private suspend fun replan(scope: PlanScope): EngineResult {
        val now = clock.millis()
        val today = resolver.today()
        val startHour = if (scope == PlanScope.AFTERNOON) config.afternoonStartHour else config.workStartHour

        // If today's window is (almost) over, plan tomorrow's instead and say so.
        val todayEnd = resolver.at(today, config.workEndHour)
        val date = if (now > todayEnd - config.slotMin * MILLIS_PER_MINUTE) today.plusDays(1) else today
        val window = TimeRange(resolver.at(date, startHour), resolver.at(date, config.workEndHour))

        val endOfPlanDay = resolver.endOfDay(date)
        val tasks = store.all().filter { !it.isDone && (it.deadline == null || it.deadline <= endOfPlanDay) }

        val plan = try {
            replanner.replan(PlanRequest(tasks, window, emptyList(), scope, date, now))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return reject("I couldn't compute the plan: ${e.message ?: "unknown error"}.")
        }
        return EngineResult(Outcome.OK, narrate(plan), taskIds = plan.blocks.map { it.taskId }.distinct(), plan = plan)
    }

    private fun narrate(plan: Plan): String {
        val whenWord = if (plan.date == resolver.today()) "" else " for ${format.day(plan.date)}"
        if (plan.isEmpty) return "There is nothing to schedule in your ${plan.scope.label}$whenWord."

        val parts = mutableListOf("Here is your ${plan.scope.label} plan$whenWord.")
        val shown = plan.blocks.take(MAX_SPOKEN_ITEMS)
        shown.forEach {
            parts += "${format.clockTime(it.start)}, ${it.title}, ${format.duration(it.minutes)}."
        }
        if (plan.blocks.size > shown.size) parts += "And ${plan.blocks.size - shown.size} more."

        if (plan.conflicts.isEmpty()) {
            parts += "Everything fits before its deadline."
        } else {
            plan.conflicts.forEach { c ->
                parts += when (c.kind) {
                    ConflictKind.LATE -> "Conflict: ${c.title} finishes ${format.minutes(c.minutes)} after its deadline."
                    ConflictKind.NO_ROOM -> "Conflict: ${c.title} does not fit; ${format.minutes(c.minutes)} of it can't be scheduled."
                }
            }
        }
        return parts.joinToString(" ")
    }

    // ---- helpers -------------------------------------------------------------------------

    private sealed interface Resolved {
        data class Ok(val task: Task) : Resolved
        data class Fail(val result: EngineResult) : Resolved
    }

    /** Looks for the target among tasks satisfying [prefer] first, then among all tasks. */
    private fun resolve(ref: TaskRef, prefer: (Task) -> Boolean): Resolved {
        when (ref) {
            is TaskRef.ById -> {
                val t = store.get(ref.id) ?: return Resolved.Fail(reject("That task no longer exists."))
                return Resolved.Ok(t)
            }
            TaskRef.Last -> {
                val t = lastTaskId?.let { store.get(it) }
                    ?: return Resolved.Fail(
                        EngineResult(Outcome.NEEDS_CLARIFICATION, "Which task do you mean?")
                    )
                return Resolved.Ok(t)
            }
            is TaskRef.ByTitle -> {
                val all = store.all()
                for (pool in listOf(all.filter(prefer), all)) {
                    when (val m = TitleMatcher.resolve(ref.query, pool)) {
                        is TitleMatcher.Match.Found -> return Resolved.Ok(m.task)
                        is TitleMatcher.Match.Ambiguous -> return Resolved.Fail(
                            EngineResult(
                                Outcome.NEEDS_CLARIFICATION,
                                "I found ${m.candidates.size} matching tasks: " +
                                    "${naturalJoin(m.candidates.take(3).map { it.title })}. Which one do you mean?",
                                taskIds = m.candidates.map { it.id }
                            )
                        )
                        TitleMatcher.Match.None -> Unit
                    }
                }
                return Resolved.Fail(reject("I couldn't find a task called ${ref.query.trim()}."))
            }
        }
    }

    private fun reject(message: String) = EngineResult(Outcome.REJECTED, message)

    private fun durationError(minutes: Int): String? =
        if (minutes < Task.MIN_DURATION_MIN || minutes > Task.MAX_DURATION_MIN) {
            "A task should take between ${Task.MIN_DURATION_MIN} minutes and ${Task.MAX_DURATION_MIN / 60} hours."
        } else null

    /** Collapses whitespace; capitalises titles that came in all-lowercase (speech recognisers do that). */
    private fun cleanTitle(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        return if (t.isNotEmpty() && t == t.lowercase()) t.replaceFirstChar { it.uppercase() } else t
    }

    private fun roundToFive(minutes: Int): Int = ((minutes + 4) / 5) * 5

    private fun orJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} or ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
    }

    private fun naturalJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private companion object {
        const val CAPABILITIES =
            "I can tell you what's due, add a task, move one to a different time, change how long " +
                "it takes or how important it is, mark one done, delete one, or replan your afternoon. " +
                "What would you like to do?"
        const val MAX_SPOKEN_ITEMS = 4
        const val MAX_SUGGESTED_REMINDERS = 3
        const val MAX_SNOOZE_MIN = 24 * 60
        const val MAX_CATCH_UP = 400
    }
}
