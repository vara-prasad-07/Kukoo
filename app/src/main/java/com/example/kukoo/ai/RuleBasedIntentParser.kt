package com.example.kukoo.ai

import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale

/**
 * Deterministic pattern parser for the supported voice commands. It is the typed-input /
 * click-to-send fallback (so the demo survives a dead microphone) and a reference the
 * LLM parser is tested against.
 */
class RuleBasedIntentParser : IntentParser {

    override suspend fun parse(utterance: String, context: ParseContext): TaskCommand = parseNow(utterance)

    fun parseNow(utterance: String): TaskCommand {
        val t = normalize(utterance)
        if (t.isEmpty()) return TaskCommand.Unsupported(utterance)

        if (endCall.matches(t)) return TaskCommand.EndCall
        // Before "cancel <task>" (delete) so that "cancel that" means undo.
        if (undo.matches(t)) return TaskCommand.Undo

        snooze.matchEntire(t)?.let { m ->
            val tail = m.groupValues[1].trim()
            return if (tail.isEmpty()) TaskCommand.Snooze() else snoozeMinutes(tail)?.let { TaskCommand.Snooze(it) }
                ?: TaskCommand.Unsupported(utterance)
        }
        remindIn.matchEntire(t)?.let { m ->
            snoozeMinutes(m.groupValues[1])?.let { return TaskCommand.Snooze(it) }
        }

        replan.matchEntire(t)?.let { m ->
            val scope = if (m.groupValues[1] == "afternoon") PlanScope.AFTERNOON else PlanScope.DAY
            return TaskCommand.Replan(scope)
        }
        if (replanBare.matches(t)) return TaskCommand.Replan(PlanScope.DAY)

        delete.matchEntire(t)?.let { return TaskCommand.DeleteTask(byTitle(it.groupValues[1])) }

        markDone.matchEntire(t)?.let { return TaskCommand.CompleteTask(byTitle(it.groupValues[1])) }
        finishTask.matchEntire(t)?.let { return TaskCommand.CompleteTask(byTitle(it.groupValues[1])) }
        reopen.matchEntire(t)?.let { return TaskCommand.ReopenTask(byTitle(it.groupValues[1])) }

        setPriority.matchEntire(t)?.let {
            val priority = Priority.fromName(it.groupValues[2])
            return TaskCommand.UpdateTask(byTitle(it.groupValues[1]), TaskPatch(priority = priority))
        }

        setDuration.matchEntire(t)?.let { m ->
            val minutes = parseBareDuration(m.groupValues[2]) ?: return TaskCommand.Unsupported(utterance)
            val ref = m.groupValues[1].takeIf { it.isNotBlank() }?.let(::byTitle) ?: TaskRef.Last
            return TaskCommand.UpdateTask(ref, TaskPatch(durationMin = minutes))
        }
        changeDeadline.matchEntire(t)?.let { m ->
            val spec = extractWhen(m.groupValues[2]).spec ?: return TaskCommand.Unsupported(utterance)
            val ref = m.groupValues[1].takeIf { it.isNotBlank() }?.let(::byTitle) ?: TaskRef.Last
            return TaskCommand.UpdateTask(ref, TaskPatch(deadline = spec))
        }
        deadlineOfTask.matchEntire(t)?.let { m ->
            val spec = extractWhen(m.groupValues[2]).spec ?: return TaskCommand.Unsupported(utterance)
            return TaskCommand.UpdateTask(byTitle(m.groupValues[1]), TaskPatch(deadline = spec))
        }

        move.matchEntire(t)?.let { m ->
            val spec = extractWhen(m.groupValues[2]).spec ?: return TaskCommand.Unsupported(utterance)
            return TaskCommand.UpdateTask(byTitle(m.groupValues[1]), TaskPatch(deadline = spec))
        }

        (add.matchEntire(t) ?: newTask.matchEntire(t) ?: remind.matchEntire(t))?.let { m ->
            return buildAdd(m.groupValues[1], utterance)
        }

        // "client deck takes 90 minutes" — only when the tail really is a duration.
        takesDuration.matchEntire(t)?.let { m ->
            parseBareDuration(m.groupValues[3])?.let { minutes ->
                return TaskCommand.UpdateTask(byTitle(m.groupValues[1]), TaskPatch(durationMin = minutes))
            }
        }

        if (isQuery(t)) {
            val scope = if (allScope.containsMatchIn(t)) QueryScope.ALL_OPEN else QueryScope.TODAY
            return TaskCommand.QueryTasks(scope)
        }
        implicitAdd(t, utterance)?.let { return it }
        return TaskCommand.Unsupported(utterance)
    }

    /**
     * "gym every day at 12pm", "read book at 9pm": a phrase with a time (or a repeat) and no command
     * word is a new task, even without "add". Anything that sounds like finishing, removing or
     * asking is left alone.
     */
    private fun implicitAdd(t: String, heard: String): TaskCommand? {
        if (implicitBlockers.containsMatchIn(t) || questionStart.containsMatchIn(t)) return null
        val hasRepeat = recurrenceRe.containsMatchIn(" $t ")
        val hasTime = extractWhen(t).spec?.time != null
        if (!hasTime && !hasRepeat) return null
        return buildAdd(t, heard).takeIf { it is TaskCommand.AddTask }
    }

    private fun buildAdd(body: String, heard: String): TaskCommand {
        var rest = " $body "
        var minutes: Int? = null
        var priority: Priority? = null
        var recurrence = Recurrence.NONE

        recurrenceRe.find(rest)?.let { m ->
            recurrence = when (m.groupValues[1]) {
                "every day", "everyday", "daily", "each day" -> Recurrence.DAILY
                "every weekday", "weekdays" -> Recurrence.WEEKDAYS
                "every week", "weekly", "each week" -> Recurrence.WEEKLY
                else -> Recurrence.MONTHLY
            }
            rest = rest.replaceRange(m.range, " ")
        }
        durationPrefixed.find(rest)?.let { m ->
            durationFrom(m)?.let {
                minutes = it
                rest = rest.replaceRange(m.range, " ")
            }
        }
        priorityRe.find(rest)?.let { m ->
            priority = when {
                m.groupValues[1].isNotEmpty() -> Priority.fromName(m.groupValues[1])
                m.groupValues[2].isNotEmpty() -> Priority.fromName(m.groupValues[2])
                else -> Priority.HIGH
            }
            rest = rest.replaceRange(m.range, " ")
        }
        val extracted = extractWhen(rest.trim())
        val title = cleanTitle(extracted.rest)
        if (title.isEmpty()) return TaskCommand.Unsupported(heard)
        return TaskCommand.AddTask(title, extracted.spec, minutes, priority, recurrence)
    }

    // ---- time phrases --------------------------------------------------------------------

    private class Extracted(val spec: DeadlineSpec.Relative?, val rest: String)

    /** Pulls "tomorrow", "friday", "at 5", "6 pm", "noon" out of [text]; returns what is left. */
    private fun extractWhen(text: String): Extracted {
        var s = " $text "
        var day: DayRef? = null
        var time: LocalTime? = null
        var tonight = false

        s = wordHour.replace(s) { "${it.groupValues[1]}${it.groupValues[2]} ${hourWords.getValue(it.groupValues[3])}" }

        timeWithPrep.find(s)?.let { m ->
            parseTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let {
                time = it
                s = s.replaceRange(m.range, " ")
            }
        }
        if (time == null) {
            timeMeridiem.find(s)?.let { m ->
                parseTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let {
                    time = it
                    s = s.replaceRange(m.range, " ")
                }
            }
        }
        if (time == null) {
            noon.find(s)?.let { m ->
                time = LocalTime.NOON
                s = s.replaceRange(m.range, " ")
            }
        }
        dayWord.find(s)?.let { m ->
            when (val w = m.groupValues[1]) {
                "today" -> day = DayRef.Today
                "tonight" -> {
                    day = DayRef.Today
                    tonight = true
                }
                "tomorrow" -> day = DayRef.Tomorrow
                else -> day = DayRef.Weekday(DayOfWeek.valueOf(w.uppercase(Locale.ROOT)))
            }
            s = s.replaceRange(m.range, " ")
        }
        if (tonight && time == null) time = LocalTime.of(20, 0)

        val spec = if (day != null || time != null) DeadlineSpec.Relative(day, time) else null
        return Extracted(spec, s.replace(Regex("\\s+"), " ").trim())
    }

    private fun parseTime(hourText: String, minuteText: String, meridiem: String): LocalTime? {
        val h = hourText.toIntOrNull() ?: return null
        val m = if (minuteText.isEmpty()) 0 else minuteText.toIntOrNull() ?: return null
        if (m !in 0..59) return null
        val hour = when {
            meridiem == "am" -> if (h in 1..12) h % 12 else return null
            meridiem == "pm" -> if (h in 1..12) (h % 12) + 12 else return null
            h in 1..7 -> h + 12 // "at 5" in a to-do context means 5 PM
            h in 8..23 -> h
            h == 0 -> 0
            else -> return null
        }
        return LocalTime.of(hour, m)
    }

    // ---- durations -----------------------------------------------------------------------

    private fun durationFrom(m: MatchResult): Int? {
        val word = m.groupValues[3]
        val minutes = if (word.isNotEmpty()) {
            when (word) {
                "an hour", "one hour" -> 60
                "half an hour", "half hour" -> 30
                "an hour and a half" -> 90
                else -> return null
            }
        } else {
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val perUnit = if (m.groupValues[2].startsWith("h")) 60.0 else 1.0
            Math.round(n * perUnit).toInt()
        }
        return minutes.takeIf { it > 0 }
    }

    /** "15 minutes", "an hour", or a bare "20"; null when [text] is not a duration. */
    private fun snoozeMinutes(text: String): Int? {
        val t = text.trim().removePrefix("for ").removePrefix("by ").trim()
        return (t.toIntOrNull() ?: parseBareDuration(t))?.takeIf { it > 0 }
    }

    private fun parseBareDuration(text: String): Int? =
        durationBare.find(text.trim())?.let(::durationFrom)

    // ---- helpers -------------------------------------------------------------------------

    private fun isQuery(t: String): Boolean =
        queryStandalone.containsMatchIn(t) ||
            (queryVerb.containsMatchIn(t) && queryTopic.containsMatchIn(t))

    /** "expense task" -> "expense": the word "task" is filler and would leak into spoken replies. */
    private fun byTitle(text: String): TaskRef =
        TaskRef.ByTitle(text.trim().removeSuffix(" tasks").removeSuffix(" task").trim())

    private fun cleanTitle(text: String): String {
        var words = text.trim().split(' ').filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in leadFillers) words = words.drop(1)
        while (words.isNotEmpty() && words.last() in trailFillers) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    private fun normalize(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace("p.m.", "pm")
        .replace("a.m.", "am")
        .replace('’', '\'')
        .replace(Regex("(?<!\\d):|:(?!\\d)"), " ")
        .replace(Regex("[?!,;\"]"), " ")
        .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val leadFillers = setOf("to", "for", "called", "named", "that", "says")
        val trailFillers = setOf("to", "for", "by", "at", "on", "due", "before", "and", "in")

        val hourWords = mapOf(
            "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
            "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12"
        )

        // whole-utterance commands
        val endCall = Regex(
            "(?:please )?(?:end (?:the )?call|hang up|good ?bye|bye|that'?s all|that is all|i'?m done|we'?re done|" +
                "no thanks?|nothing else|stop)"
        )
        val undo = Regex(
            "(?:please )?(?:undo(?: (?:that|it|the last (?:thing|action|change)|last action))?|" +
                "cancel that|revert(?: (?:that|it|the last (?:thing|action|change)))?)"
        )
        val snooze = Regex("(?:please )?snooze(?: (.+))?")
        val remindIn = Regex("(?:please )?remind me in\\s+(.+)")
        val implicitBlockers = Regex(
            "\\b(?:done|complete|completed|finish|finished|delete|remove|undo|revert|cancel|snooze|" +
                "replan|reschedule|move|change|set|mark|reopen|not)\\b"
        )
        val questionStart = Regex("^(?:what|whats|what's|when|where|why|how|who|is|are|do|does|did|can|could|will|would)\\b")
        val recurrenceRe = Regex(
            "\\s(every day|everyday|each day|daily|every weekday|weekdays|every week|each week|weekly|every month|monthly)(?=\\s)"
        )
        val replan = Regex(
            "(?:please )?(?:(?:can|could) you )?(?:re-?plan|re-?schedule|plan|organi[sz]e)\\s+(?:my|the)\\s+" +
                "(afternoon|day|today)(?: again| please)?"
        )
        val replanBare = Regex("(?:please )?re-?plan(?: everything| it)?")

        val delete = Regex("(?:please )?(?:delete|remove|cancel|drop|trash)\\s+(?:the\\s+|my\\s+)?(.+)")
        val markDone = Regex(
            "(?:please )?(?:mark|set|tick|check)\\s+(?:off\\s+)?(?:the\\s+|my\\s+)?(.+?)\\s+(?:as\\s+)?" +
                "(?:done|complete|completed|finished)"
        )
        val finishTask = Regex(
            "(?:please )?(?:complete|finish|finished|completed|done with|i'?m done with|i (?:have )?(?:finished|completed))" +
                "\\s+(?:the\\s+|my\\s+)?(.+)"
        )
        val reopen = Regex("(?:please )?(?:re-?open|un-?complete)\\s+(?:the\\s+|my\\s+)?(.+)")

        val setPriority = Regex(
            "(?:please )?(?:set|make|mark|change)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+(?:as\\s+|to\\s+)?(high|medium|low)[\\s-]priority"
        )
        val setDuration = Regex(
            "(?:please )?(?:set|change|update)\\s+(?:the\\s+)?(?:duration|length)(?:\\s+of\\s+(?:the\\s+)?(.+?))?\\s+to\\s+(.+)"
        )
        val takesDuration = Regex("(?:the\\s+|my\\s+)?(.+?)\\s+(takes|will take|needs)\\s+(.+)")

        val changeDeadline = Regex(
            "(?:please )?(?:change|set|update|make)\\s+(?:the\\s+)?(?:deadline|due(?: date| time)?|time)" +
                "(?:\\s+(?:of|for|on)\\s+(?:the\\s+|my\\s+)?(.+?))?\\s+(?:to|as|for)\\s+(.+)"
        )
        val deadlineOfTask = Regex(
            "(?:please )?(?:change|set|update)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+(?:deadline|due date|due time)\\s+to\\s+(.+)"
        )
        val move = Regex(
            "(?:please )?(?:move|push|re-?schedule|postpone|shift|bump)\\s+(?:the\\s+|my\\s+)?(.+?)\\s+" +
                "(?:to|until|till|for)\\s+(.+)"
        )

        val add = Regex(
            "(?:please )?(?:add|create)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?:(?:task|to-?do|reminder)\\b)?\\s*" +
                "(?:called\\s+|named\\s+|to\\s+)?(.+)"
        )
        val newTask = Regex("(?:please )?new\\s+(?:task|to-?do)\\s+(.+)")
        val remind = Regex("(?:please )?(?:remind me to|i need to|i have to|i must)\\s+(.+)")

        val queryVerb = Regex("^(?:please )?(?:what|whats|what's|show|list|tell|read|give)\\b")
        val queryTopic = Regex("\\b(?:tasks?|due|plate|agenda|schedule|left|pending|to-?do|have)\\b")
        val queryStandalone = Regex("\\b(?:due today|my tasks|my agenda|briefing|on my plate)\\b")
        val allScope = Regex("\\b(?:all|everything|open|upcoming|every)\\b")

        // time phrases (operate on text padded with a space on both sides)
        val wordHour = Regex("(\\s)(at|by|before|around)\\s+(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)(?=\\s)")
        val timeWithPrep = Regex("\\s(?:at|by|before|around|@)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?(?:\\s*o'?clock)?(?=\\s)")
        val timeMeridiem = Regex("\\s(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)(?=\\s)")
        val noon = Regex("\\s(?:(?:at|by|before)\\s+)?(?:noon|midday)(?=\\s)")
        val dayWord = Regex(
            "\\s(?:(?:by|on|for|due|before|this|next)\\s+)?" +
                "(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?=\\s)"
        )

        val durationAtom =
            "(?:(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|minutes?|mins?)|(an hour and a half|an hour|one hour|half an hour|half hour))"
        val durationPrefixed = Regex("\\s(?:for|takes?|taking|lasting)\\s+(?:about\\s+|around\\s+)?$durationAtom(?=\\s)")
        val durationBare = Regex("^(?:about\\s+|around\\s+)?$durationAtom$")

        val priorityRe = Regex("\\s(?:(high|medium|low)[\\s-]priority|priority\\s+(high|medium|low)|(urgent|important))(?=\\s)")
    }
}
