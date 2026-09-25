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

    fun resetContext() {
        lastTaskId = null
    }

    fun tasks(): List<Task> = store.all()

    suspend fun execute(command: TaskCommand): EngineResult = mutex.withLock {
        when (command) {
            is TaskCommand.QueryTasks -> query(command.scope)
            is TaskCommand.AddTask -> add(command)
            is TaskCommand.UpdateTask -> update(command)
            is TaskCommand.CompleteTask -> setDone(command.ref, done = true)
            is TaskCommand.ReopenTask -> setDone(command.ref, done = false)
            is TaskCommand.DeleteTask -> delete(command)
            is TaskCommand.Replan -> replan(command.scope)
            TaskCommand.EndCall -> EngineResult(Outcome.END_CALL, "Okay, goodbye. Your tasks are up to date.")
            is TaskCommand.Unsupported -> EngineResult(
                Outcome.UNSUPPORTED,
                "Sorry, I didn't catch a task command. You can ask what's due today, add, move, " +
                    "finish or delete a task, or say replan my afternoon."
            )
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

        val saved = store.insert(
            Task(
                title = title,
                deadline = deadline,
                durationMin = duration,
                priority = cmd.priority ?: Priority.MEDIUM,
                createdAt = now
            )
        )
        lastTaskId = saved.id
        val whenPart = if (deadline != null) "due ${format.deadline(deadline)}" else "with no deadline"
        return EngineResult(
            Outcome.OK,
            "Added $title, $whenPart, ${format.duration(duration)}.",
            taskIds = listOf(saved.id)
        )
    }

    private fun update(cmd: TaskCommand.UpdateTask): EngineResult {
        if (cmd.patch.isEmpty) return reject("There was nothing to change.")
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
        if (patch.title != null && updated.title != task.title) deadlineOnly = false

        lastTaskId = task.id
        if (changes.isEmpty()) {
            return EngineResult(Outcome.OK, "${task.title} is already set that way.", taskIds = listOf(task.id))
        }
        store.update(updated)

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
        val spoken = if (done) "Done. I marked ${task.title} as done." else "Okay, ${task.title} is open again."
        return EngineResult(Outcome.OK, spoken, taskIds = listOf(task.id))
    }

    private fun delete(cmd: TaskCommand.DeleteTask): EngineResult {
        val task = when (val r = resolve(cmd.ref, prefer = { !it.isDone })) {
            is Resolved.Fail -> return r.result
            is Resolved.Ok -> r.task
        }
        store.delete(task.id)
        if (lastTaskId == task.id) lastTaskId = null
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

    private fun naturalJoin(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private companion object {
        const val MAX_SPOKEN_ITEMS = 4
    }
}
