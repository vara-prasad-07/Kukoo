package com.example.kukoo.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The single place where task state changes. Voice, typed input and the UI forms all go
 * through [execute]; the LLM never touches the store. Every command is validated first and
 * answered with the exact sentence describing what happened.
 */
class TaskEngine(
    private val store: TaskStore,
    private val replanner: Replanner,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val config: PlannerConfig = PlannerConfig(),
    /** What the assistant knows about the user (goals, past activity); [NoProfile] until there is something. */
    private val profile: ProfileStore = NoProfile
) {
    private val mutex = Mutex()
    private val recommender = Recommender(clock, config)
    private val resolver = DeadlineResolver(clock, config)
    private val detector = ConflictDetector(resolver)
    private val slotFinder = SlotFinder(config, resolver)
    val format = TimeFormat(clock)

    /** What the assistant just offered to do about an overlap, good only for the user's next reply. */
    @Volatile
    private var offer: Offer? = null

    /** True while a spoken (or typed) turn is running: only then does an edit start a question. */
    private var spokenTurn = false

    /** The task most recently talked about, so "change the time to 6 PM" has a target. */
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

    /**
     * The question about a conflict that is waiting for an answer, or null. A parser needs it to read a
     * bare "yes", "no" or "keep both", which mean nothing without knowing what was asked.
     */
    val pendingConflict: ConflictQuestion?
        get() {
            draft?.let { d ->
                return draftIssue(d)?.let { ConflictQuestion(it.kind, it.suggestion != null, forDraft = true, d.title ?: "the task") }
            }
            return offer?.let {
                ConflictQuestion(
                    when (it.kind) {
                        OfferKind.SCHEDULE -> QuestionKind.SCHEDULE
                        OfferKind.SUGGEST -> QuestionKind.SUGGESTION
                        OfferKind.FIX_OVERLAP -> QuestionKind.OVERLAP
                    },
                    it.slot != null, forDraft = false, it.title
                )
            }
        }

    /** The day being planned by conversation, or null. Only ever changed while holding [mutex]. */
    @Volatile
    private var dayPlan: PlanSession? = null

    private val dayPlanner = DayPlanner(clock, config)

    /** Where the planning conversation stands, so a parser can read "yes" or "make it shorter" correctly. */
    val pendingPlan: PlanningState?
        get() = dayPlan?.let { s ->
            val titles = s.proposal?.let { p -> (p.placed + p.unplaced).map { it.title } } ?: s.items.map { it.title }
            PlanningState(s.stage, format.day(s.date), titles)
        }

    fun resetContext() {
        lastTaskId = null
        undoAction = null
        draft = null
        offer = null
        dayPlan = null
    }

    fun tasks(): List<Task> = store.all()

    /** What the assistant has learnt from the past: the user's repeated activities, most regular first. */
    fun habits(): List<Habit> = recommender.habits(store.all(), profile)

    fun goals(): List<Goal> = profile.goals()

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
    suspend fun executeSpoken(command: TaskCommand): EngineResult = mutex.withLock {
        spokenTurn = true
        try {
            dispatchSpoken(command)
        } finally {
            spokenTurn = false
        }
    }

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
        TaskCommand.CheckConflicts -> checkConflicts()
        is TaskCommand.FindTime -> findTime(command.durationMin)
        is TaskCommand.Resolve -> resolve(command.choice, null)
        is TaskCommand.PlanDay -> startPlan(command)
        is TaskCommand.PlanAdd -> planAdd(command)
        is TaskCommand.PlanChange -> planChange(command)
        TaskCommand.PlanApprove -> approvePlan()
        is TaskCommand.SuggestTask -> suggest(command, standing = null)
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
        val when_ = task.deadline?.let { " It starts ${format.deadline(it)}." }.orEmpty()
        return EngineResult(
            Outcome.NEEDS_INFO,
            "What would you like to change about ${task.title}?$when_ " +
                "You can move it to another time, change how long it takes, or change its priority.",
            taskIds = listOf(task.id)
        )
    }

    private fun chat(kind: ChatKind): EngineResult {
        val pending = draft
        val planning = dayPlan
        // "Okay" / "yeah" after a proposal is the yes it was waiting for.
        if (planning?.proposal != null && kind == ChatKind.ACKNOWLEDGE) return approvePlan()
        val message = when (kind) {
            ChatKind.GREETING -> "${format.greeting()}. What would you like to do?"
            ChatKind.THANKS -> "You're welcome."
            ChatKind.ACKNOWLEDGE -> "Okay."
            ChatKind.HELP -> CAPABILITIES
        }
        // Mid-dialog the question still needs answering, so repeat it after the pleasantry.
        if (pending != null && kind != ChatKind.HELP) return ask(pending, prefix = "$message ")
        if (planning != null && kind != ChatKind.HELP) {
            return EngineResult(Outcome.NEEDS_INFO, "$message ${planQuestion(planning)}")
        }
        val tail = when (kind) {
            ChatKind.THANKS, ChatKind.ACKNOWLEDGE -> " Anything else?"
            else -> ""
        }
        return EngineResult(Outcome.OK, message + tail)
    }

    private suspend fun dispatchSpoken(command: TaskCommand): EngineResult {
        val pending = draft
        val planning = dayPlan
        // A question about an overlap is only good for the very next thing the user says.
        val standing = offer
        offer = null
        return when (command) {
            is TaskCommand.Resolve -> resolve(command.choice, standing)
            // "okay" / "yeah" after "should I use that?" is a yes.
            is TaskCommand.Chat ->
                if (command.kind == ChatKind.ACKNOWLEDGE && (standing != null || (pending != null && draftIssue(pending) != null))) {
                    resolve(ConflictChoice.ACCEPT, standing)
                } else dispatch(command)
            // "never mind" after an offer means "leave it".
            TaskCommand.DiscardDraft ->
                if (pending == null && standing != null) resolve(ConflictChoice.KEEP, standing) else dispatch(command)
            is TaskCommand.Snooze, is TaskCommand.ReopenTask -> withNewOverlaps(command)
            // While a day is being planned, "add cooking" means add it to the plan, not start a second dialog.
            is TaskCommand.AddTask ->
                if (planning != null) planAdd(TaskCommand.PlanAdd(listOf(planItemOf(command))))
                else startDraft(TaskDraft.of(command))
            // A misheard "gym every day at 12pm" is still a new task, and needs the same questions.
            is TaskCommand.UpdateTask ->
                planEditFrom(command)?.let { dispatch(it) }
                    ?: (if (planning == null) selfHealToAdd(command) else null)?.let { startDraft(TaskDraft.of(it)) }
                    ?: withDraftReminder(command)
            is TaskCommand.DeleteTask -> planDropFrom(command)?.let { dispatch(it) } ?: withDraftReminder(command)
            is TaskCommand.StartTask, is TaskCommand.FillTask ->
                if (planning != null) EngineResult(Outcome.NEEDS_INFO, "Sure. ${planQuestion(planning)}") else dispatch(command)
            // chat() re-asks the pending question itself, so it must not also get a draft reminder.
            is TaskCommand.PlanDay, is TaskCommand.PlanAdd, is TaskCommand.PlanChange, TaskCommand.PlanApprove ->
                dispatch(command)
            // "Something else" / "make it 30 minutes" after a suggestion; a fresh request otherwise. Not while another
            // dialog is open: that one is finished first.
            is TaskCommand.SuggestTask -> when {
                pending != null -> ask(pending, prefix = "Let's finish this task first. ")
                planning != null -> EngineResult(Outcome.NEEDS_INFO, "Let's finish the plan first. ${planQuestion(planning)}")
                else -> suggest(command, standing)
            }
            is TaskCommand.Unsupported -> when {
                pending != null -> ask(pending, prefix = "Sorry, I didn't catch that. ")
                planning != null -> EngineResult(Outcome.NEEDS_INFO, "Sorry, I didn't catch that. ${planQuestion(planning)}")
                else -> dispatch(command)
            }
            TaskCommand.EndCall -> {
                if (pending == null && planning == null) return dispatch(command)
                draft = null
                dayPlan = null
                EngineResult(
                    Outcome.END_CALL,
                    if (pending != null) "Okay, goodbye. I didn't add ${pending.title ?: "the new task"} because it wasn't finished."
                    else "Okay, goodbye. I didn't add the plan because it wasn't approved."
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
        // The call is the moment to sort out a clash: say it and ask what to do about it.
        val clash = todaysClashQuestion()
        return "${format.greeting()}. $core$extra${clash ?: " What would you like to do?"}"
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
                "Reminder: ${task.title} starts ${format.deadline(deadline)}, in ${spanWord(left)}."
            }
            else -> "Reminder: ${task.title} was due to start ${format.deadline(deadline)}."
        }
        val notes = task.notes?.let { " Your note says: $it." }.orEmpty()
        val clash = task.deadline?.let { clashQuestionFor(task) }
        return "$lead It takes ${format.duration(task.durationMin)}, ${task.priority.label.lowercase()} priority.$notes " +
            (clash?.trimStart() ?: "Want to mark it done, move it, or change something?")
    }

    private fun spanWord(minutes: Int) = if (minutes < 60) format.minutes(minutes) else format.duration(minutes)

    /** Why [reminderMin] cannot be used for a task due at [deadline], or null when it is fine. */
    private fun reminderError(title: String, deadline: Long?, reminderMin: Int, now: Long): String? {
        if (reminderMin < Task.MIN_REMINDER_MIN || reminderMin > Task.MAX_REMINDER_MIN) {
            return "A reminder should be between ${Task.MIN_REMINDER_MIN} minute and ${Task.MAX_REMINDER_MIN / 60} hours before it starts."
        }
        if (deadline == null) return "A reminder needs a start time to count back from, so set a start time for $title first."
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
        if (d.overdue.isEmpty() && d.due.isEmpty()) return "Nothing is scheduled today."
        val parts = mutableListOf<String>()
        if (d.overdue.isNotEmpty()) {
            val verb = if (d.overdue.size == 1) "task is" else "tasks are"
            parts += "${d.overdue.size} $verb past their start time: ${listWithTimes(d.overdue, withDay = true)}."
        }
        parts += if (d.due.isNotEmpty()) {
            val noun = if (d.due.size == 1) "task" else "tasks"
            "You have ${d.due.size} $noun today: ${listWithTimes(d.due, withDay = false)}."
        } else {
            "Nothing else is scheduled today."
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
                d == null -> "${t.title}, no start time"
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
        if (targets.isEmpty()) return EngineResult(Outcome.OK, "Nothing is scheduled today, so there is nothing to snooze.")

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
            // "tomorrow" on its own moves the day and keeps the time already given.
            val current = base.deadline?.let { resolver.resolve(it, existing = null) }
            val at = resolver.resolve(spec, existing = current)
            if (at < now) problems += "${format.deadline(at).replaceFirstChar { it.uppercase() }} has already passed."
            else {
                val stored = if (current != null && spec is DeadlineSpec.Relative) DeadlineSpec.Exact(at) else spec
                d = d.copy(deadline = stored, keepOverlaps = false, hourConfirmed = false)
                heard += "starts ${format.deadline(at)}"
            }
        }
        incoming.durationMin?.let { minutes ->
            val error = durationError(minutes)
            if (error != null) problems += error
            else { d = d.copy(durationMin = minutes, keepOverlaps = false); heard += format.duration(minutes) }
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
        if (merged.nextMissing() == null && draftIssue(merged) == null) return finishDraft(merged, noRoomForReminder)

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
        // The question about a 3 AM start names the time itself, so it is not read back first as well.
        val told = if (draftIssue(d) is DraftIssue.OddHour) heard.filterNot { it.startsWith("starts ") } else heard
        val spoken = buildString {
            append(prefix)
            problems.forEach { append(it).append(' ') }
            if (told.isNotEmpty()) append("Got it: ").append(told.joinToString(", ")).append(". ")
            append(question(d))
        }
        return EngineResult(Outcome.NEEDS_INFO, spoken)
    }

    private fun question(d: TaskDraft): String {
        draftIssue(d)?.let { return issueQuestion(d, it) }
        return fieldQuestion(d)
    }

    private fun fieldQuestion(d: TaskDraft): String = when (d.nextMissing()) {
        DraftField.TITLE, null -> "What should I call the task?"
        DraftField.DEADLINE -> "When should ${d.title} start?"
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
        val planning = dayPlan
        val result = dispatch(command)
        if (pending != null) {
            val lead = pending.title?.let { "Back to $it." } ?: "Back to the new task."
            return result.copy(spoken = "${result.spoken} $lead ${question(pending)}")
        }
        // The command may itself have ended the plan, so only one that is still open needs the reminder.
        val open = dayPlan
        if (planning != null && open != null) {
            return result.copy(spoken = "${result.spoken} Back to planning ${format.day(open.date)}. ${planQuestion(open)}")
        }
        return result
    }

    private fun discardDraft(): EngineResult {
        val planning = dayPlan
        if (draft == null && planning != null) {
            dayPlan = null
            return EngineResult(Outcome.OK, "Okay, I've dropped the plan for ${format.day(planning.date)}. Nothing was added.")
        }
        val pending = draft ?: return reject("There is no new task to cancel.")
        draft = null
        return EngineResult(Outcome.OK, "Okay, I won't add ${pending.title ?: "the new task"}.")
    }

    private fun finishDraft(complete: TaskDraft, noRoomForReminder: Boolean = false): EngineResult {
        draft = null
        val result = add(complete.toAddTask()!!)
        // The plain add() sentence never mentions priority, which is now always a spoken answer.
        return if (result.isSuccess) {
            val noReminder = if (noRoomForReminder) " There isn't enough time before the start time for a reminder call." else ""
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
        val whenPart = if (deadline != null) "starting ${format.deadline(deadline)}" else "with no start time"
        if (cmd.recurrence != Recurrence.NONE && deadline != null &&
            (cmd.deadline as? DeadlineSpec.Relative)?.day.let { it == null || it == DayRef.Today } &&
            cmd.deadline is DeadlineSpec.Relative &&
            resolver.toLocal(deadline).toLocalDate() == resolver.today().plusDays(1)
        ) {
            // Today's time had already passed, so the series starts tomorrow.
            return EngineResult(
                Outcome.OK,
                "Added '$title' starting ${format.deadline(deadline)} (repeats ${recurrenceWord(cmd.recurrence)})." +
                    overlapNoteForNew(saved, cmd.keepOverlaps),
                taskIds = listOf(saved.id)
            )
        }
        val repeats = if (cmd.recurrence != Recurrence.NONE) " Repeats ${repeatWord(cmd.recurrence)}." else ""
        val alsoHave = duplicate?.let { other ->
            val other_ = other.deadline?.let { format.deadline(it) } ?: "no start time"
            " You already had another $title, $other_."
        }.orEmpty()
        val reminder = cmd.reminderMin?.let { " I'll call you ${spanWord(it)} before." }.orEmpty()
        return EngineResult(
            Outcome.OK,
            "Added $title, $whenPart, ${format.duration(duration)}.$repeats$reminder$alsoHave" +
                overlapNoteForNew(saved, cmd.keepOverlaps),
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
                changes += "no start time"
                deadlineOnly = true
            }
        } else if (patch.deadline != null) {
            val resolved = resolver.resolve(patch.deadline, existing = task.deadline)
            if (resolved != task.deadline) {
                if (resolved < now) {
                    return reject("${format.deadline(resolved)} has already passed, so I left ${task.title} unchanged.")
                }
                updated = updated.copy(deadline = resolved)
                changes += "starts ${format.deadline(resolved)}"
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
            if (patch.keepOverlaps) ackOverlaps(task)
            return EngineResult(Outcome.OK, "${task.title} is already set that way.", taskIds = listOf(task.id))
        }
        store.update(updated)
        undoAction = { store.update(task) }
        val overlapTail = overlapTailForEdit(task, updated, patch.keepOverlaps)

        val spoken = when {
            deadlineOnly && patch.clearDeadline -> "Done. ${updated.title} no longer has a start time."
            deadlineOnly -> "Done. ${updated.title} now starts ${format.deadline(updated.deadline!!)}."
            else -> "Updated ${updated.title}: ${changes.joinToString(", ")}."
        }
        return EngineResult(Outcome.OK, spoken + overlapTail, taskIds = listOf(task.id))
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
            spoken += next.deadline?.let { " The next one starts ${format.deadline(it)}." }
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

    // ---- planning a day by conversation --------------------------------------------------

    /**
     * A day being planned. [items] are the tasks the user named (the planner picks their slots); [proposal] is
     * the plan last shown, and while it is null the assistant is still waiting to hear the task list.
     */
    private data class PlanSession(
        val date: LocalDate,
        val items: List<PlanItemSpec> = emptyList(),
        /** Tasks already on the list that the user asked to leave out of this plan. */
        val skipped: Set<Long> = emptySet(),
        /** Tasks already on the list that are not due that day but were named, so they are planned anyway. */
        val pulled: Set<Long> = emptySet(),
        val startAt: LocalTime? = null,
        val proposal: DayProposal? = null
    ) {
        val stage: PlanStage get() = if (proposal == null) PlanStage.ASK_TASKS else PlanStage.REVIEW
    }

    private class AbsorbedPlan(val session: PlanSession, val added: List<String>, val problems: List<String>) {
        fun madeProgress(before: PlanSession) =
            added.isNotEmpty() || session.pulled != before.pulled || session.skipped != before.skipped
    }

    private class Built(val session: PlanSession, val problems: List<String>)

    private sealed interface ItemMatch {
        data class One(val title: String, val existingId: Long?) : ItemMatch
        data class Many(val titles: List<String>) : ItemMatch
        data object None : ItemMatch
    }

    /** "Plan my day": opens the conversation, or goes straight to a proposal when the tasks came with it. */
    private fun startPlan(cmd: TaskCommand.PlanDay): EngineResult {
        // "Plan for Friday instead" said mid-plan changes the plan; it must not start over and lose the list.
        val current = dayPlan
        if (current != null && cmd.items.isEmpty() && current.items.isNotEmpty()) {
            return if (cmd.day == null && cmd.date == null) {
                proposeAndAsk(current, "We're already planning ${format.day(current.date)}. ")
            } else {
                planChange(TaskCommand.PlanChange(day = cmd.day, date = cmd.date))
            }
        }
        val lead = StringBuilder()
        val unfinished = draft
        if (unfinished != null) {
            draft = null
            lead.append("Dropping the unfinished ${unfinished.title ?: "new task"}. ")
        } else if (dayPlan?.items?.isNotEmpty() == true) {
            lead.append("Starting a fresh plan. ")
        }

        val today = resolver.today()
        var date = resolver.dateOf(cmd.day, cmd.date)
        if (date == today && tooLateToday()) {
            date = today.plusDays(1)
            lead.append("It's too late to plan what's left of today, so I'll plan tomorrow. ")
        }

        val base = PlanSession(date)
        val absorbed = absorbItems(base, cmd.items)
        val session = absorbed.session
        absorbed.problems.forEach { lead.append(it).append(' ') }

        val gaveTasks = cmd.items.isNotEmpty()
        if (gaveTasks && (session.items.isNotEmpty() || existingFor(session).isNotEmpty())) {
            if (absorbed.added.isNotEmpty()) lead.append("Got it: ${naturalJoin(absorbed.added.distinct())}. ")
            return proposeAndAsk(session, lead.toString())
        }

        dayPlan = session
        val day = format.day(date)
        val existing = existingFor(session)
        val sorry = if (gaveTasks && absorbed.problems.isEmpty()) "Sorry, I didn't catch those tasks. " else ""
        val ask = if (existing.isEmpty()) {
            "Sure, let's plan $day. What's on your mind? Tell me the tasks you want to fit in, " +
                "and I'll suggest the best order and timing."
        } else {
            "Sure, let's plan $day. You already have ${listTitles(existing)} on your list for then, so I'll fit " +
                "${if (existing.size == 1) "that" else "those"} in too. What else is on your mind? " +
                "Tell me the tasks, or say that's all."
        }
        return EngineResult(Outcome.NEEDS_INFO, "$lead$sorry$ask")
    }

    /** More tasks for the plan. With no plan open it starts one. */
    private fun planAdd(cmd: TaskCommand.PlanAdd): EngineResult {
        val open = dayPlan ?: return startPlan(TaskCommand.PlanDay(items = cmd.items))
        val absorbed = absorbItems(open, cmd.items)
        val problems = absorbed.problems.joinToString("") { "$it " }
        if (!absorbed.madeProgress(open)) {
            val sorry = if (absorbed.problems.isEmpty()) "Sorry, I didn't catch any tasks. " else ""
            return EngineResult(Outcome.NEEDS_INFO, "$sorry$problems${planQuestion(open)}")
        }
        val heard = if (absorbed.added.isEmpty()) "" else "Got it: ${naturalJoin(absorbed.added.distinct())}. "
        return proposeAndAsk(absorbed.session, problems + heard)
    }

    /** A change to the plan under review: one task's time, length or priority, dropping it, or the plan itself. */
    private fun planChange(cmd: TaskCommand.PlanChange): EngineResult {
        val open = dayPlan ?: return reject("There is no plan in progress. Say plan my day to start one.")
        val nothingSaid = cmd.target == null && cmd.at == null && cmd.durationMin == null && cmd.priority == null &&
            !cmd.remove && cmd.day == null && cmd.date == null
        if (nothingSaid) {
            return EngineResult(
                Outcome.NEEDS_INFO,
                "Sure, what should I change? You can move a task, change how long it takes or how important it is, " +
                    "add or remove one, or say cancel to drop the plan."
            )
        }

        var s = open
        val said = mutableListOf<String>()
        val problems = mutableListOf<String>()

        // The plan itself: another day, or a later start.
        if (cmd.day != null || cmd.date != null) {
            val date = resolver.dateOf(cmd.day, cmd.date)
            when {
                date == s.date -> Unit
                date == resolver.today() && tooLateToday() -> problems += "It's too late to plan what's left of today."
                else -> {
                    s = s.copy(date = date, skipped = emptySet(), pulled = emptySet())
                    said += "Planning ${format.day(date)} instead."
                }
            }
        }
        val startAt = cmd.at
        if (cmd.target == null && startAt != null) {
            if (startAt.hour >= config.dayEndHour) {
                problems += "That's too late in the day to start."
            } else {
                s = s.copy(startAt = startAt)
                said += "Starting from ${format.clockTime(startAt)}."
            }
        }
        if (cmd.target == null && (cmd.durationMin != null || cmd.priority != null || cmd.remove)) {
            problems += "I wasn't sure which task you meant. ${planTitlesSentence(s)}"
        }

        // One task in the plan.
        val target = cmd.target
        if (target != null) {
            val one = when (val m = matchPlanItem(s, target)) {
                is ItemMatch.One -> m
                is ItemMatch.Many -> return EngineResult(
                    Outcome.NEEDS_CLARIFICATION,
                    "I found ${m.titles.size} matching tasks in the plan: ${naturalJoin(m.titles.take(3))}. Which one do you mean?"
                )
                ItemMatch.None -> return EngineResult(
                    Outcome.NEEDS_INFO,
                    "I couldn't find ${target.trim()} in the plan. ${planTitlesSentence(s)}"
                )
            }
            val edits = cmd.at != null || cmd.durationMin != null || cmd.priority != null || cmd.remove
            if (!edits) {
                return EngineResult(
                    Outcome.NEEDS_INFO,
                    "What would you like to change about ${one.title}? You can move it to another time, " +
                        "change how long it takes or how important it is, or remove it."
                )
            }
            val id = one.existingId
            if (id != null) {
                if (cmd.remove) {
                    s = s.copy(skipped = s.skipped + id, pulled = s.pulled - id)
                    said += "Left ${one.title} out of the plan."
                } else {
                    problems += "${one.title} is already on your list, so I can only leave it out of this plan. " +
                        "You can change it after we finish."
                }
            } else {
                val index = s.items.indexOfFirst { it.title.equals(one.title, ignoreCase = true) }
                if (index >= 0 && cmd.remove) {
                    s = s.copy(items = s.items.filterIndexed { i, _ -> i != index })
                    said += "Removed ${one.title}."
                } else if (index >= 0) {
                    var spec = s.items[index]
                    val minutes = cmd.durationMin
                    if (minutes != null) {
                        val error = durationError(minutes)
                        if (error != null) problems += error
                        else {
                            spec = spec.copy(durationMin = minutes)
                            said += "${one.title} now takes ${format.duration(minutes)}."
                        }
                    }
                    val at = cmd.at
                    if (at != null) {
                        val length = spec.durationMin ?: TaskEstimator.durationFor(spec.title)
                        val usable = usableStart(s.date, at, length, one.title, problems, "I left it where it was.")
                        if (usable != null) {
                            spec = spec.copy(at = usable)
                            said += "${one.title} is now at ${format.clockTime(usable)}."
                        }
                    }
                    val priority = cmd.priority
                    if (priority != null) {
                        spec = spec.copy(priority = priority)
                        said += "${one.title} is now ${priority.label.lowercase()} priority."
                    }
                    s = s.copy(items = s.items.mapIndexed { i, item -> if (i == index) spec else item })
                }
            }
        }

        val lead = (said + problems).joinToString(" ").let { if (it.isEmpty()) "" else "$it " }
        if (s.items.isEmpty() && existingFor(s).isEmpty()) {
            dayPlan = s.copy(proposal = null)
            return EngineResult(Outcome.NEEDS_INFO, "${lead}That leaves nothing to plan. What else is on your mind for ${format.day(s.date)}?")
        }
        if (s.items.isEmpty() && s.proposal == null) {
            dayPlan = s
            return EngineResult(Outcome.NEEDS_INFO, lead + planQuestion(s))
        }
        return proposeAndAsk(s, lead)
    }

    /** The user said yes: the proposed tasks are written to the list. */
    private fun approvePlan(): EngineResult {
        val open = dayPlan ?: return reject("There is no plan to add. Say plan my day to make one.")
        val proposal = open.proposal
        if (proposal == null) {
            // Still waiting for the task list, and the user says that is everything.
            if (open.items.isEmpty() && existingFor(open).isEmpty()) {
                return EngineResult(
                    Outcome.NEEDS_INFO,
                    "There's nothing to plan yet. Tell me the tasks you want to fit into ${format.day(open.date)}."
                )
            }
            return proposeAndAsk(open, "")
        }
        if (proposal.clashes.isNotEmpty()) {
            val pairs = proposal.clashes.joinToString(" ") { (a, b) -> "$a and $b overlap." }
            return EngineResult(
                Outcome.NEEDS_INFO,
                "I haven't added anything yet. $pairs Tell me a different time for one of them."
            )
        }

        val now = clock.millis()
        val fresh = proposal.newItems
        // Time kept moving while the plan was being talked over: a slot that has begun is not a plan any more.
        if (fresh.any { it.start < now }) {
            return proposeAndAsk(open, "Some of those times have passed while we were talking, so I've moved things. ")
        }
        if (fresh.isEmpty()) {
            dayPlan = null
            return EngineResult(Outcome.OK, "Okay, your day is set. Everything in it was already on your list, so nothing changed.")
        }

        val inserted = mutableListOf<Task>()
        try {
            fresh.forEach { item ->
                inserted += store.insert(
                    Task(
                        title = item.title,
                        deadline = item.start,
                        durationMin = item.durationMin,
                        priority = item.priority,
                        createdAt = now,
                        reminderMin = dayPlanner.reminderFor(item.start, item.start, now)
                    )
                )
            }
        } catch (e: Exception) {
            // All or nothing: half a plan on the list would be worse than none.
            inserted.forEach { store.delete(it.id) }
            throw e
        }
        dayPlan = null
        lastTaskId = inserted.last().id
        undoAction = { inserted.forEach { store.delete(it.id) }; lastTaskId = null }

        val called = inserted.count { it.reminderMin != null }
        val reminder = when (called) {
            inserted.size -> ", each with a reminder call shortly before it starts"
            0 -> ""
            else -> ", with reminder calls for $called of them"
        }
        val left = proposal.unplaced.filter { it.isNew }
        val leftOut = if (left.isEmpty()) "" else " I left out ${naturalJoin(left.map { it.title })} because there wasn't room."
        return EngineResult(
            Outcome.OK,
            "Done. I added ${naturalJoin(inserted.map { it.title })} for ${format.day(open.date)}, " +
                "each due when its slot starts$reminder.$leftOut Say undo if you want them gone.",
            taskIds = inserted.map { it.id },
            changedTasks = true
        )
    }

    /** Folds the tasks the user named into [base], refusing (and saying why) any that cannot be planned. */
    private fun absorbItems(base: PlanSession, incoming: List<PlanItemSpec>): AbsorbedPlan {
        val items = base.items.toMutableList()
        var skipped = base.skipped
        var pulled = base.pulled
        val added = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val open = store.all().filter { !it.isDone }
        val dueThatDay = existingFor(base.copy(skipped = emptySet(), pulled = emptySet())).map { it.id }.toSet()

        for (raw in incoming) {
            val title = cleanTitle(raw.title)
            if (title.isEmpty()) continue
            if (title.length > Task.MAX_TITLE_LENGTH) {
                problems += "That task name is too long."
                continue
            }
            // A task the user already has is planned around, never added a second time.
            val onList = open.firstOrNull { it.title.equals(title, ignoreCase = true) }
            if (onList != null) {
                skipped = skipped - onList.id
                if (onList.id !in dueThatDay) pulled = pulled + onList.id
                problems += "${onList.title} is already on your list, so I'll fit it in without adding it again."
                continue
            }
            val known = items.indexOfFirst { it.title.equals(title, ignoreCase = true) }
            if (known < 0 && items.size >= MAX_PLAN_ITEMS) {
                problems += "I can plan up to $MAX_PLAN_ITEMS tasks at a time, so I left out $title."
                continue
            }

            var minutes = raw.durationMin
            val badLength = minutes?.let { durationError(it) }
            if (badLength != null) {
                problems += badLength
                minutes = null
            }
            var at: LocalTime? = null
            val wanted = raw.at
            if (wanted != null) {
                val length = minutes ?: TaskEstimator.durationFor(title)
                at = usableStart(base.date, wanted, length, title, problems, "I'll fit it in wherever there's room.")
            }

            val spec = PlanItemSpec(title, minutes, raw.priority, at)
            if (known >= 0) {
                val old = items[known]
                items[known] = old.copy(
                    durationMin = spec.durationMin ?: old.durationMin,
                    priority = spec.priority ?: old.priority,
                    at = spec.at ?: old.at
                )
            } else {
                items += spec
            }
            added += title
        }
        return AbsorbedPlan(base.copy(items = items, skipped = skipped, pulled = pulled), added, problems)
    }

    /** [at] on [date] if a task of [minutes] starting then still lies ahead and ends the same day; else says why not. */
    private fun usableStart(
        date: LocalDate,
        at: LocalTime,
        minutes: Int,
        title: String,
        problems: MutableList<String>,
        otherwise: String
    ): LocalTime? {
        val start = resolver.at(date, at)
        return when {
            start < clock.millis() -> {
                problems += "${format.deadline(start).replaceFirstChar { it.uppercase() }} has already passed. $otherwise"
                null
            }
            start + minutes * MILLIS_PER_MINUTE > resolver.endOfDay(date) + 1 -> {
                problems += "$title wouldn't finish before midnight if it started at ${format.clockTime(at)}. $otherwise"
                null
            }
            else -> at
        }
    }

    private fun tooLateToday(): Boolean =
        clock.millis() + config.planLeadMin * MILLIS_PER_MINUTE >
            resolver.at(resolver.today(), config.dayEndHour) - MIN_PLAN_ROOM_MIN * MILLIS_PER_MINUTE

    /** Open tasks that belong in the day's plan: due that day (or overdue, for today), plus any the user named. */
    private fun existingFor(s: PlanSession): List<Task> {
        val start = resolver.startOfDay(s.date)
        val end = resolver.endOfDay(s.date)
        val isToday = s.date == resolver.today()
        return store.all().filter { t ->
            val due = t.deadline
            !t.isDone && t.id !in s.skipped &&
                (t.id in s.pulled || (due != null && (if (isToday) due <= end else due in start..end)))
        }.sortedBy { it.deadline ?: Long.MAX_VALUE }
    }

    /** Works the day out and remembers it; the returned reply reads it back and asks what to do next. */
    private fun proposeAndAsk(session: PlanSession, lead: String): EngineResult {
        val built = buildProposal(session)
        dayPlan = built.session
        val proposal = built.session.proposal!!
        val problems = built.problems.joinToString("") { "$it " }
        return EngineResult(Outcome.NEEDS_INFO, lead + problems + narrate(built.session, proposal))
    }

    private fun buildProposal(s: PlanSession): Built {
        val now = clock.millis()
        val problems = mutableListOf<String>()
        // A fixed time that has slipped into the past while talking can't be honoured; the task floats instead.
        val items = s.items.map { item ->
            val at = item.at
            if (at != null && resolver.at(s.date, at) < now) {
                val ms = resolver.at(s.date, at)
                problems += "${format.deadline(ms).replaceFirstChar { it.uppercase() }} has already passed, " +
                    "so I'll fit ${item.title} in wherever there's room."
                item.copy(at = null)
            } else item
        }
        val candidates = items.map { item ->
            PlanCandidate(
                title = item.title,
                durationMin = item.durationMin ?: TaskEstimator.durationFor(item.title),
                priority = item.priority ?: TaskEstimator.priorityFor(item.title),
                at = item.at,
                existing = null,
                estimated = item.durationMin == null || item.priority == null
            )
        } + existingFor(s).map { t -> PlanCandidate(t.title, t.durationMin, t.priority, null, t, estimated = false) }
        val proposal = dayPlanner.propose(s.date, candidates, s.startAt, now)
        return Built(s.copy(items = items, proposal = proposal), problems)
    }

    private fun narrate(s: PlanSession, p: DayProposal): String {
        val now = clock.millis()
        val day = format.day(s.date)
        val parts = mutableListOf("Here's your plan for $day.")
        p.placed.forEach { parts += describe(it) }
        if (p.unplaced.isNotEmpty()) {
            val room = if (s.date == resolver.today()) "what's left of today" else day
            parts += "I couldn't fit ${naturalJoin(p.unplaced.map { it.title })} into $room."
        }
        p.clashes.forEach { (a, b) -> parts += "$a and $b overlap." }

        val fresh = p.newItems
        if (fresh.isNotEmpty()) {
            val without = fresh.filter { dayPlanner.reminderFor(it.start, it.start, now) == null }.map { it.title }
            parts += when {
                without.isEmpty() -> "Each new task is due when its slot starts, and I'll call you shortly before it starts."
                without.size == fresh.size -> "Each new task is due when its slot starts. There isn't time for reminder calls."
                else -> "Each new task is due when its slot starts. I'll call you shortly before each starts, " +
                    "except for ${naturalJoin(without)}."
            }
            if (fresh.any { it.estimated }) {
                parts += "I estimated how long things take and how important they are, so tell me if any is off."
            }
        }
        parts += reviewQuestion(p)
        return parts.joinToString(" ")
    }

    private fun describe(item: PlannedItem): String {
        val times = item.blocks.joinToString(" and ") { rangeWord(it) }
        val onList = if (item.isNew) "" else " It's already on your list."
        val late = if (item.lateMin > 0) " That is ${format.minutes(item.lateMin)} after its deadline." else ""
        return "${item.title}, $times, ${format.duration(item.durationMin)}, ${item.priority.label.lowercase()} priority.$onList$late"
    }

    /** "9 to 10 AM", or "11 AM to 1 PM" when the two ends fall on different sides of noon. */
    private fun rangeWord(r: TimeRange): String {
        val from = format.clockTime(r.start)
        val to = format.clockTime(r.end)
        return if (from.takeLast(2) == to.takeLast(2)) "${from.dropLast(3)} to $to" else "$from to $to"
    }

    private fun reviewQuestion(p: DayProposal): String = when {
        p.clashes.isNotEmpty() -> "Tell me a different time for one of them."
        p.newItems.isNotEmpty() -> "Say yes to add them, or tell me what to change."
        p.unplaced.any { it.isNew } -> "Tell me what to shorten or drop, or another day to try."
        else -> "Tell me any more tasks to add, or say that's all."
    }

    /** What the assistant is waiting for, worded so it can be repeated after an interruption. */
    private fun planQuestion(s: PlanSession): String = when (s.stage) {
        PlanStage.ASK_TASKS -> "What's on your mind for ${format.day(s.date)}? Tell me the tasks you want to fit in."
        PlanStage.REVIEW -> reviewQuestion(s.proposal!!)
    }

    private fun planEntries(s: PlanSession): List<Pair<String, Long?>> {
        val p = s.proposal
        return if (p != null) (p.placed + p.unplaced).map { it.title to it.existingId } else s.items.map { it.title to null }
    }

    private fun planTitlesSentence(s: PlanSession): String {
        val titles = planEntries(s).map { it.first }
        return if (titles.isEmpty()) "There's nothing in the plan yet." else "The plan has ${naturalJoin(titles)}."
    }

    /** Which task in the plan a spoken name means, allowing for a misheard word the way the task list does. */
    private fun matchPlanItem(s: PlanSession, query: String): ItemMatch {
        val entries = planEntries(s)
        val pool = entries.mapIndexed { i, e -> Task(id = i + 1L, title = e.first, createdAt = 0) }
        return when (val m = TitleMatcher.resolve(query, pool)) {
            is TitleMatcher.Match.Found -> entries[(m.task.id - 1).toInt()].let { ItemMatch.One(it.first, it.second) }
            is TitleMatcher.Match.Ambiguous -> ItemMatch.Many(m.candidates.map { it.title })
            TitleMatcher.Match.None -> ItemMatch.None
        }
    }

    /** "Make the doctor 30 minutes" heard as an edit of a task: while planning it means the plan's task. */
    private fun planEditFrom(cmd: TaskCommand.UpdateTask): TaskCommand.PlanChange? {
        val s = dayPlan ?: return null
        val ref = cmd.ref as? TaskRef.ByTitle ?: return null
        val at = (cmd.patch.deadline as? DeadlineSpec.Relative)?.time
        if (at == null && cmd.patch.durationMin == null && cmd.patch.priority == null) return null
        val one = matchPlanItem(s, ref.query) as? ItemMatch.One ?: return null
        return TaskCommand.PlanChange(one.title, at, cmd.patch.durationMin, cmd.patch.priority)
    }

    /** "Delete the doctor" while planning drops it from the plan; it must not delete a real task by surprise. */
    private fun planDropFrom(cmd: TaskCommand.DeleteTask): TaskCommand.PlanChange? {
        val s = dayPlan ?: return null
        val ref = cmd.ref as? TaskRef.ByTitle ?: return null
        val one = matchPlanItem(s, ref.query) as? ItemMatch.One ?: return null
        return TaskCommand.PlanChange(one.title, remove = true)
    }

    private fun planItemOf(add: TaskCommand.AddTask) = PlanItemSpec(
        title = add.title,
        durationMin = add.durationMin,
        priority = add.priority,
        at = (add.deadline as? DeadlineSpec.Relative)?.time
    )

    private fun listTitles(tasks: List<Task>): String {
        val shown = tasks.take(MAX_SPOKEN_ITEMS - 1).map { it.title }
        val more = tasks.size - shown.size
        return naturalJoin(if (more > 0) shown + "$more more" else shown)
    }

    // ---- personalised suggestions --------------------------------------------------------

    /**
     * "Add one extra task for an hour based on my goals": the [Recommender] picks from what the user really does
     * and wants, this reads the pick back with its reasons, and nothing is written until the user says yes. Heard
     * while a suggestion is open it moves on to the next one, or resizes this one when a length was given.
     */
    private fun suggest(cmd: TaskCommand.SuggestTask, standing: Offer?): EngineResult {
        val open = standing?.suggestion
        if (open != null) {
            val minutes = cmd.durationMin
            return if (minutes != null && minutes != open.current.durationMin) resizeSuggestion(open, minutes)
            else nextSuggestion(open, prefix = "")
        }

        cmd.durationMin?.let { minutes -> durationError(minutes)?.let { return reject(it) } }
        if (profile.goals().isEmpty() && profile.history().isEmpty() && store.all().none { it.isDone }) {
            return EngineResult(
                Outcome.OK,
                "I don't know your routine yet, so I'd only be guessing. Tell me what to add, or finish a few tasks " +
                    "and I'll start suggesting."
            )
        }

        val lead = StringBuilder()
        var date = resolver.dateOf(cmd.day, null)
        if (date == resolver.today() && tooLateToday()) {
            date = date.plusDays(1)
            lead.append("It's too late to fit anything more into today, so I looked at tomorrow. ")
        }
        val ranked = recommender.rank(store.all(), profile, date, cmd.durationMin)
        val best = ranked.firstOrNull() ?: return EngineResult(
            Outcome.OK,
            lead.toString() + "I couldn't find room for ${cmd.durationMin?.let { format.duration(it) } ?: "anything from your routine"} " +
                "${format.day(date)}. Try a shorter time, or another day."
        )
        return offerSuggestion(OpenSuggestion(date, cmd.durationMin, best, setOf(best.title.lowercase())), lead.toString())
    }

    private fun offerSuggestion(open: OpenSuggestion, prefix: String): EngineResult {
        val s = open.current
        offer = Offer(OfferKind.SUGGEST, 0, s.title, s.start, open)
        val why = suggestionReasons(s, open.date)
        val because = if (why.isEmpty()) "" else " ${naturalJoin(why).replaceFirstChar { it.uppercase() }}."
        return EngineResult(
            Outcome.NEEDS_INFO,
            "${prefix}Based on your routine, I'd add ${s.title} ${format.day(s.start)}, ${rangeWord(TimeRange(s.start, s.end))}, " +
                "${format.duration(s.durationMin)}, ${s.priority.label.lowercase()} priority.$because " +
                "Should I add it, or would you like something else?"
        )
    }

    private fun nextSuggestion(open: OpenSuggestion, prefix: String): EngineResult {
        val next = recommender.rank(store.all(), profile, open.date, open.wantedMin, exclude = open.shown).firstOrNull()
            ?: return EngineResult(
                Outcome.OK,
                "${prefix}That's everything I'd suggest from your routine ${format.day(open.date)}. " +
                    "Tell me what to add instead, or ask again later."
            )
        return offerSuggestion(open.copy(current = next, shown = open.shown + next.title.lowercase()), prefix)
    }

    /** "Make it 30 minutes": the same activity, another length, at the nearest time it fits. */
    private fun resizeSuggestion(open: OpenSuggestion, minutes: Int): EngineResult {
        durationError(minutes)?.let {
            offer = Offer(OfferKind.SUGGEST, 0, open.current.title, open.current.start, open)
            return reject(it)
        }
        val s = open.current
        val start = recommender.place(store.all(), open.date, minutes, s.near)
        if (start == null) {
            offer = Offer(OfferKind.SUGGEST, 0, s.title, s.start, open)
            return EngineResult(
                Outcome.NEEDS_INFO,
                "I can't fit ${format.duration(minutes)} of ${s.title} ${format.day(open.date)}. " +
                    "Shall I add it for ${format.duration(s.durationMin)} as planned, or would you like something else?"
            )
        }
        val resized = s.copy(durationMin = minutes, start = start)
        offer = Offer(OfferKind.SUGGEST, 0, s.title, start, open.copy(current = resized, wantedMin = minutes))
        return EngineResult(
            Outcome.NEEDS_INFO,
            "Okay, ${s.title} for ${format.duration(minutes)}, ${format.day(start)}, ${rangeWord(TimeRange(start, resized.end))}. " +
                "Should I add it?"
        )
    }

    private fun acceptSuggestion(open: OpenSuggestion): EngineResult {
        val s = open.current
        val now = clock.millis()
        // Time keeps moving while the user thinks, so the slot is checked again instead of trusted.
        val start = recommender.place(store.all(), open.date, s.durationMin, s.near)
        if (start == null) {
            offer = Offer(OfferKind.SUGGEST, 0, s.title, null, open)
            return EngineResult(
                Outcome.NEEDS_INFO,
                "There's no longer room for ${s.title} ${format.day(open.date)}. Say something else and I'll look again."
            )
        }
        val saved = store.insert(
            Task(
                title = s.title,
                deadline = start,
                durationMin = s.durationMin,
                priority = s.priority,
                createdAt = now,
                reminderMin = dayPlanner.reminderFor(start, start, now)
            )
        )
        lastTaskId = saved.id
        undoAction = { store.delete(saved.id); if (lastTaskId == saved.id) lastTaskId = null }
        val moved = if (start != s.start) " The time I first offered was taken, so it's at ${format.clockTime(start)}." else ""
        val call = saved.reminderMin?.let { " I'll call you ${spanWord(it)} before." }.orEmpty()
        return EngineResult(
            Outcome.OK,
            "Done. I added ${s.title} for ${format.day(start)}, ${rangeWord(TimeRange(start, start + s.durationMin * MILLIS_PER_MINUTE))}, " +
                "${format.duration(s.durationMin)}, ${s.priority.label.lowercase()} priority.$call$moved Say undo if you want it gone.",
            taskIds = listOf(saved.id),
            changedTasks = true
        )
    }

    /** Why this one, in the order that persuades best: how often, which goal, when, how long a streak, not yet today. */
    private fun suggestionReasons(s: Suggestion, date: LocalDate): List<String> {
        val e = s.evidence
        val out = mutableListOf<String>()
        if (e.fromGoalOnly) {
            out += when {
                e.daysSince != null -> "you haven't worked on ${e.goalName} in ${e.daysSince} days"
                else -> "nothing in your history serves ${e.goalName} yet"
            }
        } else {
            if (e.doneDays7 >= 2) out += "you did it on ${e.doneDays7} of the last 7 days"
            e.goalName?.let { out += if (e.goalKind == GoalKind.INTEREST) "it fits your $it interest" else "it supports your $it goal" }
            e.usualStart?.let { out += "you usually do it around ${format.clockTime(it)}" }
            if (e.streak >= 3) out += "you're on a ${e.streak}-day streak"
        }
        if (e.goalName != null && e.goalMinutesToday > 0 && e.goalMinutesToday < 24 * 60) {
            out += "you've done ${format.duration(e.goalMinutesToday)} towards it ${if (date == resolver.today()) "today" else "that day"} so far"
        }
        if (date == resolver.today() && !e.fromGoalOnly) out += "you haven't done it yet today"
        return out.take(MAX_REASONS)
    }

    // ---- replanning ----------------------------------------------------------------------

    private suspend fun replan(scope: PlanScope): EngineResult {
        val now = clock.millis()
        val today = resolver.today()

        // If today's waking hours are (almost) over, plan tomorrow's instead and say so.
        val awakeEnd = resolver.at(today, config.awakeEndHour)
        val date = if (now > awakeEnd - config.slotMin * MILLIS_PER_MINUTE) today.plusDays(1) else today

        val plan = try {
            replanner.replan(PlanRequest(store.all().filter { !it.isDone }, store.acks(), scope, date, now, clock.zone))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return reject("I couldn't compute the plan: ${e.message ?: "unknown error"}.")
        }
        val question = if (spokenTurn) planQuestion(plan) else ""
        return EngineResult(
            Outcome.OK, narrate(plan) + question,
            taskIds = plan.blocks.map { it.taskId }.distinct(), plan = plan
        )
    }

    private fun narrate(plan: Plan): String {
        val whenWord = if (plan.date == resolver.today()) "" else " for ${format.day(plan.date)}"
        if (plan.isEmpty) return "There is nothing scheduled in your ${plan.scope.label}$whenWord."

        val parts = mutableListOf("Here is your ${plan.scope.label} plan$whenWord.")
        val shown = plan.blocks.take(MAX_SPOKEN_ITEMS)
        shown.forEach {
            val lead = if (it.proposed) "Suggested: " else ""
            parts += "$lead${format.clockTime(it.start)}, ${it.title}, ${format.duration(it.minutes)}."
        }
        if (plan.blocks.size > shown.size) parts += "And ${plan.blocks.size - shown.size} more."

        if (plan.conflicts.isEmpty()) {
            parts += "No conflicts."
        } else {
            plan.conflicts.take(2).forEach { c ->
                parts += "Conflict: ${c.firstTitle} and ${c.secondTitle} overlap by ${spanWord(c.minutes)}, " +
                    "${whenRange(c.start, c.end)}."
            }
            if (plan.conflicts.size > 2) parts += "And ${plan.conflicts.size - 2} more."
        }
        if (plan.unplaced.isNotEmpty()) parts += "There's no room today for ${naturalJoin(plan.unplaced.take(3))}."
        return parts.joinToString(" ")
    }

    /** After the plan is read out: offer to fix the first overlap, or else to schedule the first suggestion. */
    private fun planQuestion(plan: Plan): String {
        val clash = plan.conflicts.firstOrNull()
        if (clash != null) {
            currentOverlaps().firstOrNull {
                it.range.start == clash.start && it.involves(clash.firstId) && it.involves(clash.secondId)
            }?.let { return fixQuestion(it) }
        }
        val proposal = plan.blocks.filter { it.proposed }
            .sortedWith(compareByDescending<ScheduledBlock> { it.priority.rank }.thenByDescending { it.minutes }.thenBy { it.taskId })
            .firstOrNull() ?: return ""
        offer = Offer(OfferKind.SCHEDULE, proposal.taskId, proposal.title, proposal.start)
        return " Want me to schedule ${proposal.title} for ${format.deadline(proposal.start)}?"
    }

    // ---- conflicts -----------------------------------------------------------------------

    private enum class OfferKind { FIX_OVERLAP, SCHEDULE, SUGGEST }

    /** [slot] is the time the assistant suggested, or null when there was no free time to suggest. */
    private data class Offer(
        val kind: OfferKind,
        val taskId: Long,
        val title: String,
        val slot: Long?,
        val suggestion: OpenSuggestion? = null
    )

    /** A suggestion waiting for a yes: the one on offer, the others still to try, and what the user asked for. */
    private data class OpenSuggestion(
        val date: LocalDate,
        val wantedMin: Int?,
        val current: Suggestion,
        /** Lower-cased titles already offered, so "something else" never repeats one. */
        val shown: Set<String>
    )

    /** A problem with the task being set up that has to be settled before it is saved. */
    private sealed interface DraftIssue {
        val kind: QuestionKind
        val suggestion: Long?

        data class Overlaps(val start: Long, val overlaps: List<Overlap>, override val suggestion: Long?) : DraftIssue {
            override val kind: QuestionKind get() = QuestionKind.OVERLAP
        }

        data class OddHour(val start: Long, override val suggestion: Long?) : DraftIssue {
            override val kind: QuestionKind get() = QuestionKind.ODD_HOUR
        }
    }

    private fun currentOverlaps(): List<Overlap> = detector.overlaps(store.all(), clock.millis(), store.acks())

    private fun overlapsWith(candidate: Task): List<Overlap> =
        detector.overlapsWith(store.all(), candidate, clock.millis(), store.acks())

    private fun draftCandidate(d: TaskDraft, start: Long) = Task(
        id = DRAFT_ID,
        title = d.title ?: "",
        deadline = start,
        durationMin = d.durationMin ?: Task.DEFAULT_DURATION_MIN,
        priority = d.priority ?: Priority.MEDIUM,
        createdAt = clock.millis(),
        recurrence = d.recurrence
    )

    /**
     * What is wrong with the task being set up, or null. The hour is checked as soon as it is known (3 AM is
     * nearly always a misheard 3 PM); an overlap once the length is known too.
     */
    private fun draftIssue(d: TaskDraft): DraftIssue? {
        val spec = d.deadline ?: return null
        val now = clock.millis()
        val start = resolver.resolve(spec, existing = null)

        if (!d.hourConfirmed && resolver.toLocal(start).hour < config.oddHourEndHour) {
            val flipped = start + 12 * 60 * MILLIS_PER_MINUTE
            return DraftIssue.OddHour(start, flipped.takeIf { it > now })
        }
        if (d.durationMin == null || d.keepOverlaps) return null
        val candidate = draftCandidate(d, start)
        val tasks = store.all()
        val overlaps = detector.overlapsWith(tasks, candidate, now, store.acks())
        if (overlaps.isEmpty()) return null
        return DraftIssue.Overlaps(start, overlaps, slotFinder.suggestFor(tasks, candidate, now, detector))
    }

    /** "3 PM to 3:30 PM", or with the day when it is not today. */
    private fun whenRange(start: Long, end: Long): String {
        val core = "from ${format.clockTime(start)} to ${format.clockTime(end)}"
        val day = format.day(start)
        return if (day == "today") core else "$day $core"
    }

    private fun pairSentence(o: Overlap): String =
        "${o.first.task.title} and ${o.second.task.title} overlap by ${spanWord(o.minutes)}, " +
            "${whenRange(o.range.start, o.range.end)}."

    private fun describeOverlaps(list: List<Overlap>): String {
        val more = if (list.size > 2) " And ${list.size - 2} more." else ""
        return list.take(2).joinToString(" ") { pairSentence(it) } + more
    }

    /** What [task] runs into, for "it overlaps Standup, from 9 AM to 9:30 AM." */
    private fun headsUpBody(task: Task, overlaps: List<Overlap>): String {
        val others = overlaps.map { it.other(task.id) }.distinctBy { it.task.id }
        if (others.size == 1) {
            val o = others[0]
            return "it overlaps ${o.task.title}, ${whenRange(o.start, o.end)}."
        }
        val names = others.take(2).map { it.task.title } + listOfNotNull(
            if (others.size > 2) "${others.size - 2} more" else null
        )
        return "it overlaps ${naturalJoin(names)}."
    }

    private fun issueQuestion(d: TaskDraft, issue: DraftIssue): String {
        val title = d.title ?: "The task"
        return when (issue) {
            is DraftIssue.OddHour -> {
                val at = format.deadline(issue.start)
                if (issue.suggestion != null) {
                    "$title would start $at, in the middle of the night. Did you mean ${format.deadline(issue.suggestion)}?"
                } else "$title would start $at, in the middle of the night. Is that right?"
            }
            is DraftIssue.Overlaps -> {
                val at = format.deadline(issue.start)
                val others = issue.overlaps.map { it.other(DRAFT_ID) }.distinctBy { it.task.id }
                val what = if (others.size == 1) {
                    "${others[0].task.title}, ${whenRange(others[0].start, others[0].end)}"
                } else {
                    naturalJoin(
                        others.take(2).map { it.task.title } +
                            listOfNotNull(if (others.size > 2) "${others.size - 2} more" else null)
                    )
                }
                val ask = issue.suggestion?.let {
                    "I can fit it ${format.deadline(it)} instead. Should I use that, pick another time, or keep both?"
                } ?: "I couldn't find a free slot this week. What other time works, or should I keep both?"
                "$title $at would overlap $what. $ask"
            }
        }
    }

    /** The answer to the open question, whether it is about a task being set up or one just changed. */
    private fun resolve(choice: ConflictChoice, standing: Offer?): EngineResult {
        val d = draft
        if (d != null) {
            val issue = draftIssue(d) ?: return ask(d, prefix = "Sorry, I didn't catch that. ")
            return resolveDraft(d, issue, choice)
        }
        return if (standing != null) resolveOffer(standing, choice) else reject("There is nothing to decide right now.")
    }

    private fun resolveDraft(d: TaskDraft, issue: DraftIssue, choice: ConflictChoice): EngineResult {
        val title = d.title ?: "The task"
        return when (issue) {
            is DraftIssue.OddHour -> when {
                choice == ConflictChoice.ACCEPT && issue.suggestion != null -> useDraftTime(d, issue.suggestion)
                // "No" and "that's right" both mean the early hour is what they want.
                else -> progress(
                    d.copy(hourConfirmed = true), TaskDraft(),
                    prefix = "Okay, ${format.deadline(issue.start)}. ", apologizeIfNothingNew = false
                )
            }
            is DraftIssue.Overlaps -> when (choice) {
                ConflictChoice.ACCEPT ->
                    issue.suggestion?.let { useDraftTime(d, it) }
                        ?: ask(d, prefix = "I don't have a free time to offer. ")
                ConflictChoice.KEEP -> progress(
                    d.copy(keepOverlaps = true), TaskDraft(), prefix = "Okay, I'll keep both. ",
                    apologizeIfNothingNew = false
                )
                ConflictChoice.DECLINE -> EngineResult(
                    Outcome.NEEDS_INFO, "Okay. What time would you like for $title instead? Or say keep both."
                )
            }
        }
    }

    private fun useDraftTime(d: TaskDraft, slot: Long): EngineResult = progress(
        d.copy(deadline = DeadlineSpec.Exact(slot), keepOverlaps = false, hourConfirmed = false), TaskDraft(),
        prefix = "Okay, ${d.title} starts ${format.deadline(slot)}. ", apologizeIfNothingNew = false
    )

    private fun resolveOffer(o: Offer, choice: ConflictChoice): EngineResult {
        o.suggestion?.let { open ->
            return when (choice) {
                ConflictChoice.ACCEPT -> acceptSuggestion(open)
                ConflictChoice.DECLINE -> nextSuggestion(open, prefix = "Okay. ")
                ConflictChoice.KEEP -> EngineResult(Outcome.OK, "Okay, I won't add anything.")
            }
        }
        if (choice == ConflictChoice.ACCEPT) {
            val slot = o.slot
            if (slot == null) {
                offer = o
                return EngineResult(
                    Outcome.NEEDS_INFO,
                    "I don't have a free time to offer. Tell me a time for ${o.title}, or say keep both."
                )
            }
            lastTaskId = o.taskId
            val moved = update(TaskCommand.UpdateTask(TaskRef.ById(o.taskId), TaskPatch(deadline = DeadlineSpec.Exact(slot))))
            if (!moved.isSuccess || o.kind != OfferKind.SCHEDULE || offer != null) return moved
            return moved.copy(spoken = moved.spoken + nextScheduleQuestion())
        }
        // Keep both, no, or never mind: leave things as they are, and remember it so it is not raised again.
        return if (o.kind == OfferKind.FIX_OVERLAP) {
            store.get(o.taskId)?.let { ackOverlaps(it) }
            EngineResult(Outcome.OK, "Okay, I'll keep both.", taskIds = listOf(o.taskId))
        } else {
            EngineResult(Outcome.OK, "Okay, I'll leave ${o.title} without a start time.", taskIds = listOf(o.taskId))
        }
    }

    /** "Keep both": stop reporting the overlaps [task] has right now. */
    private fun ackOverlaps(task: Task) {
        detector.overlaps(store.all(), clock.millis(), emptySet())
            .filter { it.involves(task.id) }
            .forEach { store.addAck(it.ack) }
    }

    private fun overlapNoteForNew(saved: Task, keep: Boolean): String {
        if (saved.deadline == null) return ""
        if (keep) {
            ackOverlaps(saved)
            return ""
        }
        val overlaps = overlapsWith(saved)
        return if (overlaps.isEmpty()) "" else " Heads up: ${headsUpBody(saved, overlaps)}"
    }

    /**
     * After an edit: says which overlaps it *created* (not ones that were already there) and, in
     * conversation, offers to move the edited task to the nearest free time.
     */
    private fun overlapTailForEdit(before: Task, after: Task, keep: Boolean): String {
        if (after.deadline == null) return ""
        if (keep) {
            ackOverlaps(after)
            return ""
        }
        val had = overlapsWith(before).map { it.ack }.toSet()
        val created = overlapsWith(after).filter { it.ack !in had }
        if (created.isEmpty()) return ""
        lastTaskId = after.id
        val body = " Heads up: ${headsUpBody(after, created)}"
        if (!spokenTurn || draft != null) return body

        val slot = slotFinder.suggestFor(store.all(), after, clock.millis(), detector)
        offer = Offer(OfferKind.FIX_OVERLAP, after.id, after.title, slot)
        return body + if (slot != null) " Want me to move ${after.title} to ${format.deadline(slot)}, or keep both?"
        else " I couldn't find a free slot this week. Tell me another time, or say keep both."
    }

    /** Snooze and reopen can push tasks into each other: say so, since the user did not ask about conflicts. */
    private suspend fun withNewOverlaps(command: TaskCommand): EngineResult {
        val before = currentOverlaps().map { it.ack }.toSet()
        val result = withDraftReminder(command)
        if (!result.isSuccess) return result
        val added = currentOverlaps().filter { it.ack !in before }
        if (added.isEmpty()) return result
        return result.copy(spoken = "${result.spoken} Heads up: ${describeOverlaps(added)} Ask me about conflicts and I'll help sort it out.")
    }

    /** Offers to move whichever task of [o] matters less and can be moved, to the nearest free time. */
    private fun fixQuestion(o: Overlap): String {
        val mover = listOf(o.first, o.second).filter { it.isRowStart }
            .minWithOrNull(compareBy<Occurrence>({ it.task.priority.rank }, { -it.start }, { -it.task.id }))
            ?: return ""
        val task = mover.task
        val slot = slotFinder.suggestFor(store.all(), task, clock.millis(), detector)
        offer = Offer(OfferKind.FIX_OVERLAP, task.id, task.title, slot)
        return if (slot != null) " Want me to move ${task.title} to ${format.deadline(slot)}, or keep both?"
        else " I couldn't find a free slot this week for ${task.title}. Tell me another time, or say keep both."
    }

    private fun checkConflicts(): EngineResult {
        val overlaps = currentOverlaps()
        if (overlaps.isEmpty()) return EngineResult(Outcome.OK, "You have no overlapping tasks.")
        val head = if (overlaps.size == 1) "You have 1 overlap." else "You have ${overlaps.size} overlaps."
        val ask = if (spokenTurn) fixQuestion(overlaps.first()) else ""
        return EngineResult(
            Outcome.OK, "$head ${describeOverlaps(overlaps)}$ask",
            taskIds = overlaps.flatMap { listOf(it.first.task.id, it.second.task.id) }.distinct()
        )
    }

    private fun findTime(durationMin: Int?): EngineResult {
        val minutes = durationMin ?: Task.DEFAULT_DURATION_MIN
        durationError(minutes)?.let { return reject(it) }
        val now = clock.millis()
        val busy = detector.occurrences(store.all(), now).map { it.range }
        val slot = slotFinder.find(minutes, now, now, busy)
            ?: return EngineResult(Outcome.OK, "I couldn't find ${format.duration(minutes)} free in the next week.")
        return EngineResult(Outcome.OK, "Your next free ${format.duration(minutes)} is ${format.deadline(slot)}.")
    }

    /** The opening of the daily call when tasks overlap today: say so and ask what to do. */
    private fun todaysClashQuestion(): String? {
        val endOfToday = resolver.endOfDay(resolver.today())
        val overlaps = currentOverlaps().filter { it.range.start <= endOfToday }
        val first = overlaps.firstOrNull() ?: return null
        val head = if (overlaps.size == 1) " You have an overlap today: ${pairSentence(first)}"
        else " You have ${overlaps.size} overlaps today. The first: ${pairSentence(first)}"
        return head + fixQuestion(first)
    }

    /** The opening of a reminder call for a task that overlaps another: say so and offer to move it. */
    private fun clashQuestionFor(task: Task): String? {
        val o = currentOverlaps().firstOrNull { it.involves(task.id) } ?: return null
        val other = o.other(task.id)
        val body = " It overlaps ${other.task.title}, ${whenRange(other.start, other.end)}."
        if (!o.mine(task.id).isRowStart) return body
        val slot = slotFinder.suggestFor(store.all(), task, clock.millis(), detector)
        offer = Offer(OfferKind.FIX_OVERLAP, task.id, task.title, slot)
        return body + if (slot != null) " Want me to move ${task.title} to ${format.deadline(slot)}, or keep both?"
        else " I couldn't find a free slot this week for it. Tell me another time, or say keep both."
    }

    /** After scheduling one task with no start time: offer the next one, if there is room for it. */
    private fun nextScheduleQuestion(): String {
        val now = clock.millis()
        val tasks = store.all()
        val busy = detector.occurrences(tasks, now).map { it.range }
        val candidates = tasks.filter { !it.isDone && it.deadline == null && it.durationMin > 0 }
            .sortedWith(compareByDescending<Task> { it.priority.rank }.thenByDescending { it.durationMin }.thenBy { it.id })
        for (t in candidates) {
            val slot = slotFinder.find(t.durationMin, now, now, busy) ?: continue
            offer = Offer(OfferKind.SCHEDULE, t.id, t.title, slot)
            return " Want me to schedule ${t.title} for ${format.deadline(slot)}?"
        }
        return ""
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
            "I can tell you what's on today, add a task, suggest one from your goals and habits, plan your day, move one to a different time, change how long " +
                "it takes or how important it is, mark one done, delete one, or replan your afternoon. " +
                "What would you like to do?"
        const val MAX_SPOKEN_ITEMS = 4
        const val DRAFT_ID = -1L
        const val MAX_SUGGESTED_REMINDERS = 3
        const val MAX_SNOOZE_MIN = 24 * 60
        const val MAX_PLAN_ITEMS = 12
        const val MAX_REASONS = 3

        /** A day with less than this left is not planned; the plan moves to tomorrow. */
        const val MIN_PLAN_ROOM_MIN = 30
        const val MAX_CATCH_UP = 400
    }
}
