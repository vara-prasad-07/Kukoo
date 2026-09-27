package com.example.kukoo.ai

import java.time.LocalTime
import java.util.Locale

/**
 * Checks that a detail the language model returned was actually said by the user.
 *
 * A small model asked for JSON will happily fill a field from the instructions instead of from the
 * sentence: a bare "add" came back as a task called "Gym" at noon because that was the example it
 * had just read. None of these checks judge whether a value is *right*; they only require that the
 * user's words contain something the value could have come from. A hallucinated field fails that
 * and is dropped, so the assistant asks for it instead of inventing it.
 */
internal object Grounding {
    private val word = Regex("[a-z0-9]+")

    private fun tokens(text: String): List<String> = word.findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    private val numberWords = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private val bigNumberWords = "$numberWords|thirteen|fourteen|fifteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred"

    /** Words that describe *making* a task rather than naming one. */
    private val notATitle = setOf(
        "a", "an", "the", "new", "task", "tasks", "todo", "to", "do", "reminder", "item", "thing", "something",
        "one", "it", "this", "that", "add", "create", "make", "please", "yes", "no", "yeah", "yep", "nope", "okay",
        "ok", "sure", "um", "uh", "hmm", "what"
    )

    /** True for text that names no task at all, e.g. "task", "a new task", "yes". */
    fun isFillerTitle(text: String): Boolean = tokens(text).all { it in notATitle }

    /**
     * Every word of [value] must appear in [utterance] (allowing "finish" for "finishing"), and the
     * value must not be filler. Used for titles and notes, which are free text.
     */
    fun textGrounded(value: String, utterance: String): Boolean {
        val wanted = tokens(value)
        if (wanted.isEmpty() || isFillerTitle(value)) return false
        val said = tokens(utterance)
        return wanted.all { w ->
            said.any { it == w || (w.length >= 3 && it.length >= 3 && (it.startsWith(w) || w.startsWith(it))) }
        }
    }

    private val dayToday = Regex("\\b(?:today|tonight|now|later|this (?:morning|afternoon|evening))\\b")
    private val dayTomorrow = Regex("\\b(?:tomorrow|tmrw|tmr)\\b")

    /** [day] is what the model said ("today", "tomorrow", "friday"...). */
    fun dayGrounded(day: String, utterance: String): Boolean {
        val u = utterance.lowercase(Locale.ROOT)
        return when (val d = day.lowercase(Locale.ROOT)) {
            "today" -> dayToday.containsMatchIn(u)
            "tomorrow" -> dayTomorrow.containsMatchIn(u)
            else -> d.length >= 3 && Regex("\\b${Regex.escape(d.take(3))}").containsMatchIn(u)
        }
    }

    private val timeMarker = Regex(
        "\\d\\s*(?:am|pm|a\\.m|p\\.m)|\\d{1,2}:\\d{2}|o'?clock|\\b(?:noon|midnight|midday|morning|afternoon|evening|night|tonight)\\b|" +
            "\\b(?:at|by|before|around|after)\\s+(?:\\d|$numberWords)"
    )
    private val anyNumber = Regex("\\d|\\b(?:$numberWords)\\b")

    /**
     * A clock time needs a marker like "6 pm", "at 5" or "noon". With [bare] (the user was just asked
     * *when*) a lone "5" is also an answer.
     */
    fun timeGrounded(utterance: String, bare: Boolean): Boolean {
        val u = utterance.lowercase(Locale.ROOT)
        return timeMarker.containsMatchIn(u) || (bare && anyNumber.containsMatchIn(u))
    }

    private val periodWord = Regex(
        "\\d\\s*(?:am|pm|a\\.m|p\\.m)|\\b(?:am|pm|morning|afternoon|evening|night|tonight|noon|midnight|midday)\\b"
    )

    /**
     * In a to-do list "at 6" means 6 PM, as it does for the rule parser. The model keeps answering
     * 06:00 whatever the prompt says, so a time from 1 to 7 o'clock is moved to the afternoon unless
     * the user said am, morning or the like.
     */
    fun assumeAfternoon(time: LocalTime, utterance: String): LocalTime =
        if (time.hour in 1..7 && !periodWord.containsMatchIn(utterance.lowercase(Locale.ROOT))) time.plusHours(12) else time

    private val durationMarker = Regex(
        "\\d\\s*(?:h|hr|hrs|hours?|m|mins?|minutes?)\\b|\\b(?:hours?|hrs?|minutes?|mins?|half|quarter|couple)\\b"
    )
    private val anyAmount = Regex("\\d|\\b(?:$bigNumberWords|couple|few)\\b")

    /** A duration needs a unit ("an hour", "20 minutes"); with [bare] a lone "45" also counts. */
    fun durationGrounded(utterance: String, bare: Boolean): Boolean {
        val u = utterance.lowercase(Locale.ROOT)
        return durationMarker.containsMatchIn(u) || (bare && anyAmount.containsMatchIn(u))
    }

    private val durationPhrase = Regex(
        "(?:\\d+(?:\\.\\d+)?\\s*|(?:$bigNumberWords|an?|half|quarter)\\s+)(?:h|hr|hrs|hours?|m|mins?|minutes?)\\b"
    )

    /** How many separate lengths of time the user named ("30 minutes, 10 minutes before" is two). */
    fun durationPhraseCount(utterance: String): Int = durationPhrase.findAll(utterance.lowercase(Locale.ROOT)).count()

    private val reminderContext = Regex(
        "\\b(?:remind\\w*|call me|calling me|ring me|notify|notification|alert|heads[- ]?up|ahead|in advance)\\b|\\bbefore\\b"
    )
    private val noWord = Regex("\\b(?:no|none|nope|nah|nothing|skip|without|neither)\\b|\\bdon'?t\\b|\\bnot (?:needed|necessary)\\b")
    private val removeWord = Regex("\\b(?:remove|delete|cancel|clear|stop|turn off|disable)\\b")

    /**
     * A number of minutes is a *reminder* only when the user spoke of one ("remind me 10 minutes before"),
     * or when the assistant had just asked about the reminder, where a bare "10" is the answer ([bare]).
     */
    fun reminderMinutesGrounded(utterance: String, bare: Boolean): Boolean =
        durationGrounded(utterance, bare) && (bare || reminderContext.containsMatchIn(utterance.lowercase(Locale.ROOT)))

    /**
     * "No reminder": a plain no-word when the assistant just asked ([bare]); otherwise the user has to
     * be talking about reminders as well ("no reminder", "remove the reminder from ...").
     */
    fun noReminderGrounded(utterance: String, bare: Boolean): Boolean {
        val u = utterance.lowercase(Locale.ROOT)
        return if (bare) noWord.containsMatchIn(u)
        else reminderContext.containsMatchIn(u) && (noWord.containsMatchIn(u) || removeWord.containsMatchIn(u))
    }

    private val priorityWord = Regex(
        "\\b(?:high|medium|low|urgent|urgently|important|critical|asap|normal|moderate|minor|top|mid|regular|" +
            "average|immediately|rush)\\b|\\bnot\\s+(?:that\\s+|very\\s+|so\\s+)?(?:urgent|important)\\b"
    )

    fun priorityGrounded(utterance: String): Boolean = priorityWord.containsMatchIn(utterance.lowercase(Locale.ROOT))

    private val repeatWord = Regex(
        "\\b(?:daily|every|each|everyday|weekdays?|weekly|monthly|repeat\\w*|recurr\\w*|annually)\\b"
    )

    fun recurrenceGrounded(utterance: String): Boolean = repeatWord.containsMatchIn(utterance.lowercase(Locale.ROOT))
}
