package com.example.kukoo.ai

import java.util.Locale

/**
 * Repairs the mistakes a speech recognizer reliably makes on this app's vocabulary.
 *
 * Recognizers are tuned for general English, so short command words come back as the common word
 * that sounds the same: "high priority" as "hi priority", "an hour" as "honor", "modify" as
 * "mortify". None of those are the user's fault, and in this app they are unambiguous.
 *
 * Repair strength depends on where the words are going, because a task title can legitimately
 * contain any English word:
 *  - [repairAnswer] is for a reply to a question the assistant just asked ("how long?", "how
 *    important?"). The answer is a duration or a priority, never a title, so "honor" is certainly
 *    "an hour" and everything can be corrected.
 *  - [repairCommand] is for anything else, and only fixes words that would never appear in a
 *    name, so "add honor roll ceremony" keeps its title intact.
 *
 * A misheard *title* is never patched here; it is matched against the real titles by
 * [com.example.kukoo.domain.FuzzyText] instead.
 */
object SpeechRepair {

    /** Safe anywhere: these spellings are recognizer artefacts, not words anyone names a task. */
    private val alwaysSafe = listOf(
        "mortify|modifi|motify|mordify" to "modify",
        "to morrow|tomorow|tommorow" to "tomorrow",
        "to-day" to "today",
        "o clock|oclock" to "o'clock",
        "a\\.m\\.|a m\\b" to "am",
        "p\\.m\\.|p m\\b" to "pm",
    ).compile()

    /** Only safe in an answer to a question, where the words cannot be part of a task name. */
    private val answerOnly = listOf(
        // "an hour" has many misspellings and is the most common answer to "how long?".
        "on her|honour|honor|an our|and our|a nour|an ower" to "an hour",
        "half an our|half an ower|half our" to "half an hour",
        // "high"/"low" as a bare answer to "how important?"
        "^hi$|^hy$|^hire$" to "high",
        "^lo$" to "low",
        "^mediam$|^medim$" to "medium",
    ).compile()

    /** "hi priority" is a priority even mid-sentence; a bare "hi" is a greeting and is left alone. */
    private val nearPriority = listOf(
        "hi|hy|hire" to "high",
        "lo" to "low",
        "mediam|medim" to "medium",
    ).map { (from, to) ->
        Regex("\\b(?:$from)(?=\\s+(?:priority|importance|urgency)\\b)") to to
    } + listOf(
        Regex("(?<=\\bpriority\\s)(?:hi|hy)\\b") to "high",
    )

    private fun List<Pair<String, String>>.compile(): List<Pair<Regex, String>> =
        map { (from, to) ->
            // Patterns already anchored with ^/$ are matched as-is; the rest get word boundaries.
            val anchored = from.startsWith("^")
            val pattern = if (anchored) from else "\\b(?:$from)\\b"
            Regex(pattern) to to
        }

    /** Conservative repair for a general request. Keeps every word that could be a task name. */
    fun repairCommand(transcript: String): String = apply(transcript, alwaysSafe + nearPriority)

    /** Full repair for a reply to a question the assistant just asked. */
    fun repairAnswer(transcript: String): String =
        apply(transcript, alwaysSafe + nearPriority + answerOnly)

    private fun apply(transcript: String, rules: List<Pair<Regex, String>>): String {
        if (transcript.isBlank()) return transcript
        var t = transcript.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        for ((pattern, replacement) in rules) t = pattern.replace(t, replacement)
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
