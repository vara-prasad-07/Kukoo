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
        "ok", "sure", "um", "uh", "hmm", "what", "them", "these", "those", "all", "everything"
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

    private val planWord = Regex("\\b(?:plan\\w*|schedul\\w*|organi[sz]\\w*|arrang\\w*|agenda|timetable|itinerary|routine)\\b")

    /** "Plan my day" needs the user to have said something about planning. */
    fun planGrounded(utterance: String): Boolean = planWord.containsMatchIn(utterance.lowercase(Locale.ROOT))

    private val suggestWord = Regex(
        "\\b(?:suggest\\w*|recommend\\w*|advise|surprise me)\\b|" +
            "\\bbased on (?:my|the|what)\\b|\\baccording to my\\b|\\bwhat (?:should|can|could) i (?:do|work on|add|try)\\b|" +
            "\\b(?:my|for my|towards my) (?:goals?|interests?|habits?|routine|history|hobbies|hobby)\\b|" +
            "\\b(?:extra|one more|additional) (?:task|thing|activity)\\b|\\bwhat do i usually\\b|\\bfill (?:my|the|this) (?:free|empty|gap)"
    )

    /**
     * The user asked the assistant to pick a task ("add one extra task for an hour based on my goals"). Without
     * one of these phrases a nameless "add a task" stays what it is, a question about the task's name.
     */
    fun suggestGrounded(utterance: String): Boolean = suggestWord.containsMatchIn(utterance.lowercase(Locale.ROOT))

    private val suggestFiller = setOf(
        "extra", "additional", "another", "more", "activity", "for", "hr", "hrs", "hour", "hours", "minute", "minutes",
        "min", "mins", "today", "tomorrow", "based", "on", "my", "goal", "goals", "interest", "interests", "habit",
        "habits", "routine", "history", "of"
    )

    /**
     * A "title" that only restates the request ("extra task for 1 hr based on my goals"): a model that is told to
     * add a task will happily copy those words into the title, and the words are all in the user's sentence.
     */
    fun isSuggestRequestTitle(text: String): Boolean {
        val numbers = numberWords.split('|').toSet()
        return tokens(text).all { it in notATitle || it in suggestFiller || it in numbers || it.all(Char::isDigit) }
    }

    private val planFiller = setOf(
        "plan", "planning", "my", "day", "days", "today", "tomorrow", "tmr", "tmrw", "schedule", "agenda", "for", "and", "i", "have"
    )

    /** True for a "task" that is really the request itself ("my day", "plan"), not something to schedule. */
    fun isPlanFiller(text: String): Boolean = tokens(text).all { it in notATitle || it in planFiller }

    private val approveWord = Regex(
        "\\b(?:yes|yeah|yep|yup|yea|sure|okay|ok|alright|fine|good|great|perfect|correct|right|confirm|approve|proceed|" +
            "continue|sounds|looks|works)\\b|\\bgo (?:ahead|on)\\b|\\bdo it\\b|\\badd (?:them|it|all|these|those)\\b|" +
            "\\bthat(?:'?s| is) (?:all|it)\\b|\\bnothing else\\b|\\bno more\\b|\\bjust (?:those|these|that|plan)\\b|" +
            "\\ball good\\b|\\bplease do\\b"
    )
    private val changeWord = Regex(
        "\\b(?:but|except|instead|change|move|make|remove|drop|shorter|longer|earlier|later|swap|replace|not|rather|also|too)\\b"
    )

    /** A yes to a proposal: an agreeing word, and nothing in the same breath that asks for a change. */
    fun approvalGrounded(utterance: String): Boolean {
        val u = utterance.lowercase(Locale.ROOT)
        return approveWord.containsMatchIn(u) && !changeWord.containsMatchIn(u)
    }

    private val dropTaskWord = Regex(
        "\\b(?:remove|drop|delete|skip|cancel|scrap|forget|without|get rid|no need|not needed)\\b|" +
            "\\b(?:leave|take|cut) (?:it |that |them )?out\\b|\\bdon'?t need\\b"
    )

    fun removeGrounded(utterance: String): Boolean = dropTaskWord.containsMatchIn(utterance.lowercase(Locale.ROOT))

    /** How many separate priority words, clock times the user gave, so one answer cannot be copied onto every task. */
    fun priorityWordCount(utterance: String): Int = priorityWord.findAll(utterance.lowercase(Locale.ROOT)).count()

    fun timeMarkerCount(utterance: String): Int = timeMarker.findAll(utterance.lowercase(Locale.ROOT)).count()

    private val monthNames = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"
    )

    /**
     * A calendar date needs both its month ("october" or "oct") and its day number ("5", "5th") in the user's words.
     * The model gets the year wrong as often as not, so only the month and day are ever taken from it.
     */
    fun monthDayGrounded(month: Int, day: Int, utterance: String): Boolean {
        if (month !in 1..12) return false
        val u = utterance.lowercase(Locale.ROOT)
        val full = monthNames[month - 1]
        val names = listOf(full, full.take(3)) + if (month == 9) listOf("sept") else emptyList()
        val monthSaid = names.any { Regex("\\b$it\\b").containsMatchIn(u) }
        return monthSaid && Regex("\\b$day(?:st|nd|rd|th)?\\b").containsMatchIn(u)
    }

    private val repeatWord = Regex(
        "\\b(?:daily|every|each|everyday|weekdays?|weekly|monthly|repeat\\w*|recurr\\w*|annually)\\b"
    )

    fun recurrenceGrounded(utterance: String): Boolean = repeatWord.containsMatchIn(utterance.lowercase(Locale.ROOT))
}
