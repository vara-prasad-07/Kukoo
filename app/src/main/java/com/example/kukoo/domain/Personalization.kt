package com.example.kukoo.domain

import java.time.LocalDate
import java.time.LocalTime

/** A long-term aim the user has ("crack GATE") or something they simply enjoy ("guitar"). */
enum class GoalKind { GOAL, INTEREST }

/**
 * What the user is working towards. [keywords] link it to the tasks that serve it (a task titled "DSA practice"
 * serves a goal with the keyword "dsa"). [weight] is how much it matters, 1..3. [dailyTargetMin] is about how much
 * time per day is enough, so the assistant stops pushing a goal that already got its share today. [starterTitle] is
 * what to suggest when the history holds nothing for the goal (a goal you keep neglecting).
 */
data class Goal(
    val id: Long = 0,
    val name: String,
    val kind: GoalKind = GoalKind.GOAL,
    val keywords: List<String>,
    val weight: Int = 2,
    val dailyTargetMin: Int = 60,
    val starterTitle: String? = null,
    val starterMin: Int = 30
)

/** One thing the user did (or skipped) in the past. Completed tasks feed this too, see [HabitAnalyzer]. */
data class HistoryEntry(
    val id: Long = 0,
    val title: String,
    val startedAt: Long,
    val durationMin: Int,
    val priority: Priority = Priority.MEDIUM,
    val completed: Boolean
)

/** Persistence seam for what the assistant has learnt about the user: goals, and what they did before. */
interface ProfileStore {
    fun goals(): List<Goal>
    fun replaceGoals(goals: List<Goal>)
    fun history(): List<HistoryEntry>
    fun addHistory(entries: List<HistoryEntry>)
    fun clearHistory()
}

/** Used when nothing is known about the user; suggestions then say so instead of guessing. */
object NoProfile : ProfileStore {
    override fun goals(): List<Goal> = emptyList()
    override fun replaceGoals(goals: List<Goal>) = Unit
    override fun history(): List<HistoryEntry> = emptyList()
    override fun addHistory(entries: List<HistoryEntry>) = Unit
    override fun clearHistory() = Unit
}

/** What the past says about one repeated activity. */
data class Habit(
    val key: String,
    /** The title as the user last wrote it. */
    val title: String,
    /** On how many of the last 7 days (not counting today) it was done. */
    val doneDays7: Int,
    val doneCount: Int,
    val missedCount: Int,
    val doneToday: Boolean,
    /** Days in a row it was done, up to yesterday (or today, if it is already done). */
    val streak: Int,
    val lastDone: LocalDate?,
    /** Median start of the times it was done, or null when it was done too rarely to tell. */
    val usualStart: LocalTime?,
    /** True when the times it was done cluster (within about two hours), so "usually around" is honest. */
    val startIsSteady: Boolean,
    val typicalDurationMin: Int,
    val priority: Priority,
    val goal: Goal?
) {
    /** Share of the times it was planned that it actually got done. */
    val completionRate: Double
        get() = if (doneCount + missedCount == 0) 0.5 else doneCount.toDouble() / (doneCount + missedCount)
}

/** Groups titles that mean the same activity ("DSA Practice", "dsa practice", "the DSA practice"). */
object ActivityKey {
    private val ignored = setOf("the", "a", "an", "my", "for", "to", "of", "some", "one", "session", "time", "daily", "today", "tomorrow")

    fun tokens(text: String): List<String> = Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.toList()

    fun of(title: String): String =
        tokens(title).filter { it !in ignored }.joinToString(" ").ifEmpty { title.trim().lowercase() }

    /** A keyword matches a whole word (or its plural), or (5+ letters) the start of one, or a phrase inside the key. */
    fun matches(goal: Goal, key: String): Boolean {
        val words = key.split(' ')
        return goal.keywords.any { raw ->
            val kw = raw.trim().lowercase()
            when {
                kw.isEmpty() -> false
                ' ' in kw -> key.contains(kw)
                else -> words.any { it == kw || it == kw + "s" || (kw.length >= 5 && it.startsWith(kw)) }
            }
        }
    }

    fun goalFor(goals: List<Goal>, key: String): Goal? =
        goals.filter { matches(it, key) }.maxByOrNull { it.weight }
}

/** One dated occurrence of an activity, from the history table or from the task list. */
internal class Occurred(
    val key: String,
    val title: String,
    val date: LocalDate,
    val startMin: Int,
    val durationMin: Int,
    val priority: Priority,
    val completed: Boolean
)

/**
 * Turns "what the user did" into [Habit]s. Two sources feed it, so it works on real use and on the demo alike:
 * the history table (older or imported activity) and the task list itself (tasks marked done are things done;
 * tasks left open long past their start were missed).
 */
class HabitAnalyzer(private val resolver: DeadlineResolver) {

    internal fun occurrences(tasks: List<Task>, history: List<HistoryEntry>, today: LocalDate): List<Occurred> {
        val out = mutableListOf<Occurred>()
        fun add(title: String, at: Long, minutes: Int, priority: Priority, completed: Boolean) {
            val local = resolver.toLocal(at)
            out += Occurred(
                ActivityKey.of(title), title, local.toLocalDate(), local.hour * 60 + local.minute,
                minutes, priority, completed
            )
        }
        history.forEach { add(it.title, it.startedAt, it.durationMin, it.priority, it.completed) }
        val startOfToday = resolver.startOfDay(today)
        for (t in tasks) {
            when {
                t.isDone -> (t.deadline ?: t.completedAt)?.let { add(t.title, it, t.durationMin, t.priority, true) }
                t.deadline != null && t.deadline < startOfToday -> add(t.title, t.deadline, t.durationMin, t.priority, false)
            }
        }
        return out
    }

    fun habits(tasks: List<Task>, history: List<HistoryEntry>, goals: List<Goal>, today: LocalDate): List<Habit> =
        habitsOf(occurrences(tasks, history, today), goals, today)

    internal fun habitsOf(events: List<Occurred>, goals: List<Goal>, today: LocalDate): List<Habit> {
        val recent = events.filter { !it.date.isBefore(today.minusDays(LOOKBACK_DAYS)) && !it.date.isAfter(today) }
        return recent.groupBy { it.key }.map { (key, group) ->
            val done = group.filter { it.completed }
            val missed = group.count { !it.completed }
            val doneDays = done.map { it.date }.toSet()
            val doneDays7 = doneDays.count { !it.isBefore(today.minusDays(7)) && it.isBefore(today) }

            var streak = 0
            var day = if (today in doneDays) today else today.minusDays(1)
            while (day in doneDays) {
                streak++
                day = day.minusDays(1)
            }

            val starts = done.map { it.startMin }.sorted()
            val median = if (starts.isEmpty()) null else starts[starts.size / 2]
            val steady = starts.size >= 3 && starts.last() - starts.first() <= 150
            val minutes = done.map { it.durationMin }.sorted().let { if (it.isEmpty()) group.first().durationMin else it[it.size / 2] }
            val priority = done.ifEmpty { group }.groupingBy { it.priority }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<Priority, Int>> { it.value }.thenByDescending { it.key.rank })
                .first().key

            Habit(
                key = key,
                title = group.maxByOrNull { it.date }!!.title,
                doneDays7 = doneDays7,
                doneCount = done.size,
                missedCount = missed,
                doneToday = today in doneDays,
                streak = streak,
                lastDone = doneDays.maxOrNull(),
                usualStart = median?.let { LocalTime.of(it / 60, it % 60) },
                startIsSteady = steady,
                typicalDurationMin = ((minutes + 2) / 5 * 5).coerceIn(Task.MIN_DURATION_MIN, Task.MAX_DURATION_MIN),
                priority = priority,
                goal = ActivityKey.goalFor(goals, key)
            )
        }.sortedWith(compareByDescending<Habit> { it.doneDays7 }.thenBy { it.title })
    }

    companion object {
        const val LOOKBACK_DAYS = 14L
    }
}

/** The facts behind a suggestion, so the assistant can say *why* in plain words (and a test can check them). */
data class Evidence(
    val doneDays7: Int,
    val streak: Int,
    val usualStart: LocalTime?,
    val goalName: String?,
    val goalKind: GoalKind?,
    val notDoneYet: Boolean,
    /** Days since it was last done, when it was done before. */
    val daysSince: Int?,
    /** True when nothing in the history serves this goal, so it comes from the goal itself. */
    val fromGoalOnly: Boolean,
    /** True when the user named the length, so it is not the usual one. */
    val lengthFromUser: Boolean,
    val goalMinutesToday: Int
)

data class Suggestion(
    val title: String,
    val durationMin: Int,
    val priority: Priority,
    val start: Long,
    val score: Double,
    val evidence: Evidence,
    /** Where the usual time is, so the slot can be found again if the day moves on while the user thinks. */
    val near: LocalTime?
) {
    val end: Long get() = start + durationMin * MILLIS_PER_MINUTE
}

/**
 * Picks what to add to a free hour, from what the user actually does and what they said they want.
 *
 * Everything here is arithmetic on data, deterministic, and explainable: the language model never chooses a task
 * (a 4B model on a phone would invent one), it only recognises that the user asked for a suggestion. A candidate is
 * a repeated activity, scored by
 *
 *   how often it was done in the last week (28%) + how reliably it gets finished (14%) + how much the goal behind
 *   it matters (24%) + its current streak (10%) + how well a free slot matches the time of day it is usually done (24%)
 *
 * then reduced when its goal has already had its share of today. Activities already on the list for that day are
 * skipped, and a goal nothing in the history serves comes in as a "starter" candidate, ranked below real habits.
 */
class Recommender(private val clock: java.time.Clock, private val config: PlannerConfig = PlannerConfig()) {
    private val resolver = DeadlineResolver(clock, config)
    private val detector = ConflictDetector(resolver)
    private val analyzer = HabitAnalyzer(resolver)

    fun habits(tasks: List<Task>, profile: ProfileStore): List<Habit> =
        analyzer.habits(tasks, profile.history(), profile.goals(), resolver.today())

    /**
     * Every activity that could go into [date], best first. [wantedMin] is the length the user asked for, or null
     * for each activity's usual length. Activities named in [exclude] are left out.
     */
    fun rank(
        tasks: List<Task>,
        profile: ProfileStore,
        date: LocalDate,
        wantedMin: Int?,
        exclude: Set<String> = emptySet()
    ): List<Suggestion> {
        val now = clock.millis()
        val today = resolver.today()
        val goals = profile.goals()
        val habits = analyzer.habits(tasks, profile.history(), goals, today)
        val open = tasks.filter { !it.isDone }
        val busyOccurrences = detector.occurrences(open, now)
        val onDate = busyOccurrences.filter { resolver.toLocal(it.start).toLocalDate() == date }

        // What is already planned that day (and, for today, already done), by activity and by goal.
        val plannedKeys = onDate.map { ActivityKey.of(it.task.title) }.toMutableSet()
        val goalMinutes = HashMap<Long, Int>()
        fun spend(title: String, minutes: Int) {
            ActivityKey.goalFor(goals, ActivityKey.of(title))?.let { goalMinutes.merge(it.id, minutes, Int::plus) }
        }
        onDate.forEach { spend(it.task.title, it.task.durationMin) }
        if (date == today) {
            analyzer.occurrences(tasks, profile.history(), today).filter { it.completed && it.date == today }.forEach {
                plannedKeys += it.key
                spend(it.title, it.durationMin)
            }
        }

        val gaps = freeGaps(date, now, busyOccurrences.map { it.range })
        val out = mutableListOf<Suggestion>()

        for (h in habits) {
            if (h.key in plannedKeys || h.title.lowercase() in exclude || h.key in exclude) continue
            // One-off chores ("pay the electricity bill") are not routines; a routine, or a goal, is needed.
            if (h.doneCount < 2 && h.goal == null) continue
            val minutes = (wantedMin ?: h.typicalDurationMin).coerceIn(Task.MIN_DURATION_MIN, Task.MAX_DURATION_MIN)
            val start = placeIn(gaps, date, minutes, h.usualStart) ?: continue
            val timeFit = h.usualStart?.let { 1.0 - minOf(hoursApart(start, date, it), 6.0) / 6.0 } ?: 0.5
            val goalWeight = (h.goal?.weight ?: 0) / 3.0
            val raw = 0.28 * (h.doneDays7 / 7.0) + 0.14 * h.completionRate + 0.24 * goalWeight +
                0.10 * (minOf(h.streak, 7) / 7.0) + 0.24 * timeFit
            val spent = h.goal?.let { goalMinutes[it.id] } ?: 0
            val score = raw * saturation(h.goal, spent, minutes)
            out += Suggestion(
                title = h.title, durationMin = minutes, priority = h.priority, start = start, score = score, near = h.usualStart,
                evidence = Evidence(
                    h.doneDays7, h.streak, h.usualStart.takeIf { h.startIsSteady }, h.goal?.name, h.goal?.kind,
                    notDoneYet = true, daysSince = h.lastDone?.let { (today.toEpochDay() - it.toEpochDay()).toInt() },
                    fromGoalOnly = false, lengthFromUser = wantedMin != null, goalMinutesToday = spent
                )
            )
        }

        // A goal that nothing in the history serves still deserves a suggestion, just a weaker one.
        for (g in goals) {
            val title = g.starterTitle ?: continue
            val key = ActivityKey.of(title)
            if (key in plannedKeys || title.lowercase() in exclude || key in exclude) continue
            // An activity with a history of its own was already weighed above, from the facts.
            if (habits.any { it.key == key }) continue
            val served = habits.any { it.goal?.id == g.id && it.doneDays7 >= 2 }
            if (served) continue
            val minutes = (wantedMin ?: g.starterMin).coerceIn(Task.MIN_DURATION_MIN, Task.MAX_DURATION_MIN)
            val start = placeIn(gaps, date, minutes, null) ?: continue
            val last = habits.filter { it.goal?.id == g.id }.mapNotNull { it.lastDone }.maxOrNull()
            val since = last?.let { (today.toEpochDay() - it.toEpochDay()).toInt() }
            val neglect = minOf(since ?: 7, 7) / 7.0
            val spent = goalMinutes[g.id] ?: 0
            val raw = 0.24 * (g.weight / 3.0) + 0.20 * neglect + 0.08
            out += Suggestion(
                title = title, durationMin = minutes, priority = likelyPriority(g), start = start,
                score = raw * saturation(g, spent, minutes), near = null,
                evidence = Evidence(0, 0, null, g.name, g.kind, true, since, true, wantedMin != null, spent)
            )
        }
        return out.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy { it.title })
    }

    /** Where [minutes] fit on [date] near [near] (or the earliest room when there is no usual time); null if nowhere. */
    fun place(tasks: List<Task>, date: LocalDate, minutes: Int, near: LocalTime?): Long? {
        val now = clock.millis()
        val busy = detector.occurrences(tasks.filter { !it.isDone }, now).map { it.range }
        return placeIn(freeGaps(date, now, busy), date, minutes, near)
    }

    private fun likelyPriority(g: Goal) = when {
        g.weight >= 3 -> Priority.HIGH
        g.weight == 2 -> Priority.MEDIUM
        else -> Priority.LOW
    }

    /** A goal that has had its share of the day is pushed down; one this would tip over the share only a little. */
    private fun saturation(goal: Goal?, spentMin: Int, addMin: Int): Double = when {
        goal == null -> 1.0
        spentMin >= goal.dailyTargetMin -> 0.4
        spentMin + addMin > goal.dailyTargetMin -> 0.8
        else -> 1.0
    }

    private fun hoursApart(start: Long, date: LocalDate, usual: LocalTime): Double =
        kotlin.math.abs(start - resolver.at(date, usual)) / (60.0 * MILLIS_PER_MINUTE)

    /** Free time on [date]: from now (or the morning) to the evening, minus what is already booked. */
    private fun freeGaps(date: LocalDate, now: Long, busy: List<TimeRange>): List<TimeRange> {
        val slot = config.slotMin * MILLIS_PER_MINUTE
        val roundedNow = ((now + slot - 1) / slot) * slot
        val from = maxOf(resolver.at(date, config.dayStartHour), roundedNow)
        val to = resolver.at(date, SUGGEST_END_HOUR)
        if (from >= to) return emptyList()
        val out = mutableListOf<TimeRange>()
        var cursor = from
        for (b in busy.sortedBy { it.start }) {
            if (b.end <= cursor) continue
            if (b.start >= to) break
            if (b.start > cursor) out += TimeRange(cursor, b.start)
            cursor = maxOf(cursor, ((b.end + slot - 1) / slot) * slot)
            if (cursor >= to) break
        }
        if (cursor < to) out += TimeRange(cursor, to)
        return out
    }

    /** In the gap that has room, the start closest to the usual time; with no usual time, the earliest room. */
    private fun placeIn(gaps: List<TimeRange>, date: LocalDate, minutes: Int, near: LocalTime?): Long? {
        val slot = config.slotMin * MILLIS_PER_MINUTE
        val need = minutes * MILLIS_PER_MINUTE
        val fits = gaps.filter { it.length >= need }
        val wanted = near?.let { resolver.at(date, it) } ?: return fits.firstOrNull()?.start
        return fits.map { g -> (wanted / slot * slot).coerceIn(g.start, g.end - need) }
            .minByOrNull { kotlin.math.abs(it - wanted) }
    }

    companion object {
        /** Nothing is suggested to start after this hour (it ends by then, or a little later for the last slot). */
        const val SUGGEST_END_HOUR = 22
    }
}
