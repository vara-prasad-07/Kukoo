package com.example.kukoo.domain

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Turns a [DeadlineSpec] into an absolute timestamp. All calendar maths lives here. */
class DeadlineResolver(private val clock: Clock, private val config: PlannerConfig = PlannerConfig()) {
    private val zone: ZoneId get() = clock.zone

    fun today(): LocalDate = LocalDate.now(clock)

    fun resolve(spec: DeadlineSpec, existing: Long?): Long = when (spec) {
        is DeadlineSpec.Exact -> spec.epochMillis
        is DeadlineSpec.Relative -> {
            val current = existing?.let { toLocal(it) }
            val date = when (val day = spec.day) {
                null -> current?.toLocalDate() ?: today()
                DayRef.Today -> today()
                DayRef.Tomorrow -> today().plusDays(1)
                is DayRef.Weekday -> nextWeekday(day.day)
            }
            val time = spec.time ?: current?.toLocalTime() ?: LocalTime.of(config.defaultDeadlineHour, 0)
            var target = LocalDateTime.of(date, time)
            // Invariant: a relative deadline is never in the past. If the moment has already gone by
            // (today's 10 AM at 3 PM), it rolls to the same time on the next day. Only an explicit
            // DeadlineSpec.Exact can name a past moment.
            var guard = 0
            while (target.atZone(zone).toInstant().toEpochMilli() < clock.millis() && guard++ < MAX_ROLLOVER_DAYS) {
                target = target.plusDays(1)
            }
            target.atZone(zone).toInstant().toEpochMilli()
        }
    }

    /** The next due time after [deadline] for a repeating task: same time of day, later date. */
    fun nextOccurrence(deadline: Long, recurrence: Recurrence): Long {
        val current = toLocal(deadline)
        val next = when (recurrence) {
            Recurrence.NONE -> current
            Recurrence.DAILY -> current.plusDays(1)
            Recurrence.WEEKDAYS -> {
                var d = current.plusDays(1)
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }
            Recurrence.WEEKLY -> current.plusWeeks(1)
            Recurrence.MONTHLY -> current.plusMonths(1)
        }
        return next.atZone(zone).toInstant().toEpochMilli()
    }

    private companion object {
        const val MAX_ROLLOVER_DAYS = 3660
    }

    private fun nextWeekday(target: DayOfWeek): LocalDate {
        var d = today().plusDays(1)
        while (d.dayOfWeek != target) d = d.plusDays(1)
        return d
    }

    fun toLocal(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    fun startOfDay(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun endOfDay(date: LocalDate): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    fun at(date: LocalDate, hour: Int): Long = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}

/** Spoken/short English formatting. Deterministic so the TTS says exactly what the UI shows. */
class TimeFormat(private val clock: Clock, private val locale: Locale = Locale.ENGLISH) {
    private val zone: ZoneId get() = clock.zone

    /** "5 PM", "5:30 PM". */
    fun clockTime(millis: Long): String = clockTime(Instant.ofEpochMilli(millis).atZone(zone).toLocalTime())

    fun clockTime(time: LocalTime): String {
        val h = time.hour
        val hour12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val suffix = if (h < 12) "AM" else "PM"
        return if (time.minute == 0) "$hour12 $suffix" else "$hour12:${time.minute.toString().padStart(2, '0')} $suffix"
    }

    /** "today", "tomorrow", "yesterday", "Friday" (within a week), else "Oct 3". */
    fun day(date: LocalDate): String {
        val today = LocalDate.now(clock)
        return when (date.toEpochDay() - today.toEpochDay()) {
            0L -> "today"
            1L -> "tomorrow"
            -1L -> "yesterday"
            in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> "${date.month.getDisplayName(TextStyle.SHORT, locale)} ${date.dayOfMonth}"
        }
    }

    fun day(millis: Long): String = day(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    /** "today at 5 PM". */
    fun deadline(millis: Long): String = "${day(millis)} at ${clockTime(millis)}"

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        val hours = if (h == 1) "1 hour" else "$h hours"
        return when {
            h == 0 -> "$m minutes"
            m == 0 -> hours
            else -> "$hours $m minutes"
        }
    }

    fun minutes(count: Int): String = if (count == 1) "1 minute" else "$count minutes"

    fun greeting(): String {
        val hour = LocalTime.now(clock).hour
        return when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}

/** Finds the task a user is talking about from a spoken/typed name. */
object TitleMatcher {
    sealed interface Match {
        data class Found(val task: Task) : Match
        data class Ambiguous(val candidates: List<Task>) : Match
        data object None : Match
    }

    private val stopWords = setOf(
        "the", "a", "an", "my", "task", "tasks", "to", "for", "of", "on", "please", "that", "this", "one"
    )

    private const val MIN_SCORE = 45

    fun resolve(query: String, pool: List<Task>): Match {
        val q = tokens(query)
        if (q.isEmpty() || pool.isEmpty()) return Match.None

        val scored = pool.map { it to score(q, tokens(it.title)) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return Match.None

        val best = scored.first().second
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) Match.Found(top.first()) else Match.Ambiguous(top)
    }

    private fun score(query: List<String>, title: List<String>): Int {
        if (title.isEmpty()) return 0
        if (query == title) return 100
        if (title.containsAll(query)) return 80 + (10 * query.size / title.size)

        val qc = query.joinToString("")
        val tc = title.joinToString("")
        if (qc.length >= 4 && (tc.contains(qc) || (tc.length >= 4 && qc.contains(tc)))) return 70

        val overlap = query.count { it in title }
        val ratio = overlap.toDouble() / query.size
        return if (overlap > 0 && ratio >= 0.5) (40 * ratio + 10).toInt() else 0
    }

    private fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() && it !in stopWords }
            .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
}
