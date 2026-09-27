package com.example.kukoo.ai

import com.example.kukoo.domain.ConflictChoice
import com.example.kukoo.domain.ConflictQuestion
import com.example.kukoo.domain.QuestionKind
import com.example.kukoo.domain.TaskCommand
import java.util.Locale

/**
 * Reads the short answers to "should I use that, pick another time, or keep both?".
 *
 * The set of answers to a yes/no-style question is small and closed, and a bare "yes" or "keep both"
 * is exactly where a small language model is least reliable (it has answered a "no" with a cancelled
 * task). So these are decided by rules, before the model is asked at all. Anything that is not one of
 * them (a new time, a new length, a different command) returns null and goes on to the parser.
 */
internal object ConflictReplies {

    private val affirmative = Regex("^(?:yes|yeah|yep|yup|sure|okay|ok|alright|all right|please)\\s+")

    private val accept = Regex(
        "(?:yes|yeah|yep|yup|sure|okay|ok|alright|all right|fine|please|sounds good|sounds great|that works|" +
            "thats fine|thats good|thats perfect|that would be great|perfect|great|good|do it|go ahead|go for it|" +
            "move it|move that|move it there|use that|use it|use that one|that one|the first one|the first|first one|" +
            "first|option one|option 1|works for me|that time|that time works|yes please)" +
            "(?: please| thanks| thank you)?"
    )

    private val keep = Regex(
        "(?:keep (?:them )?both|keep (?:it|them)(?: as (?:is|they are))?|leave (?:it|them|both)(?: as (?:is|they are|it is))?|" +
            "both|(?:double book(?:ed|ing)?|book both)(?: it| them| both)?|(?:the )?overlap(?:s|ping)? (?:is |are )?(?:fine|ok|okay|alright)|" +
            "its? (?:is )?fine (?:to )?overlap(?:ping)?|they can overlap|let them overlap|i dont mind|dont mind|" +
            "doesnt matter|does not matter|no problem|not a problem|its (?:fine|ok|okay|alright|all right)|thats right|thats correct|its right|its correct|correct|" +
            "i meant (?:that|it)|thats what i meant|no its (?:right|correct|fine|ok|okay)|no thats right|" +
            "no thats correct|no keep it|no leave it|keep it that way|leave it that way)"
    )

    private val decline = Regex("(?:no|nope|nah|not really|no thanks|no thank you|not that|not that one|dont|nothing)")

    private val cancel = Regex(
        "(?:cancel|never ?mind|forget it|forget about it|drop it|skip it|skip|stop|cancel it|cancel that)"
    )

    private val neverMind = Regex("(?:never ?mind|forget it|forget about it|drop it|skip it|skip)")

    /** After a suggestion, the ways of saying "yes, add that one". */
    private val addIt = Regex("(?:yes )?(?:add|schedule|book|put|use) (?:it|that|this|that one)(?: in| please)?")

    /** After a suggestion, the ways of asking for another one. */
    private val somethingElse = Regex(
        "(?:something else|something different|another(?: one| suggestion| option)?|what else|anything else|" +
            "(?:any )?other (?:options?|suggestions?|ideas?)|next(?: one)?|different(?: one)?|show me more|give me another(?: one)?|" +
            "try another(?: one)?|not (?:that|this)(?: one)?, something else)"
    )

    private val stopSuggesting = Regex("(?:never ?mind|forget it|forget about it|drop it|cancel(?: it| that)?|stop|leave it)")
    private val skip = Regex("(?:skip|skip it|skip this|skip that|pass)")

    private fun readSuggestion(t: String): TaskCommand? = when {
        // "Never mind" ends it; "no" and "skip" move on to the next suggestion.
        stopSuggesting.matches(t) -> TaskCommand.Resolve(ConflictChoice.KEEP)
        skip.matches(t) -> TaskCommand.SuggestTask()
        addIt.matches(t) || accept.matches(t) -> TaskCommand.Resolve(ConflictChoice.ACCEPT)
        somethingElse.matches(t) -> TaskCommand.SuggestTask()
        decline.matches(t) -> TaskCommand.Resolve(ConflictChoice.DECLINE)
        else -> affirmative.find(t)?.let { t.removeRange(it.range).trim() }?.takeIf { it.isNotEmpty() }?.let { rest ->
            when {
                addIt.matches(rest) || accept.matches(rest) -> TaskCommand.Resolve(ConflictChoice.ACCEPT)
                else -> null
            }
        }
    }

    fun read(utterance: String, question: ConflictQuestion): TaskCommand? {
        val t = normalize(utterance).replace(Regex("\\s+(?:please|thanks|thank you)$"), "")
        if (t.isEmpty()) return null
        if (question.kind == QuestionKind.SUGGESTION) return readSuggestion(t)

        if (question.forDraft) {
            // While a task is being set up, cancelling drops it.
            if (cancel.matches(t)) return TaskCommand.DiscardDraft
        } else if (neverMind.matches(t)) {
            // After a change was already made: "never mind" leaves it (say "undo" to reverse it).
            return TaskCommand.Resolve(ConflictChoice.KEEP)
        }

        if (keep.matches(t)) return TaskCommand.Resolve(ConflictChoice.KEEP)
        if (accept.matches(t)) return TaskCommand.Resolve(ConflictChoice.ACCEPT)
        if (decline.matches(t)) return TaskCommand.Resolve(ConflictChoice.DECLINE)

        // "yes move it", "sure keep both": a leading yes does not change the rest.
        val rest = affirmative.find(t)?.let { t.removeRange(it.range) }?.trim()
        if (rest != null && rest.isNotEmpty()) {
            if (keep.matches(rest)) return TaskCommand.Resolve(ConflictChoice.KEEP)
            if (accept.matches(rest)) return TaskCommand.Resolve(ConflictChoice.ACCEPT)
        }
        return null
    }

    private fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
