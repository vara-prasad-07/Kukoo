package com.example.kukoo.ai

import com.example.kukoo.domain.ConflictQuestion
import com.example.kukoo.domain.DraftField
import com.example.kukoo.domain.PlanningState
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft

/**
 * Extra context a parser can use to read the request.
 *
 * [draft] is set while the assistant is in the middle of adding a task and has just asked for a
 * missing detail: the next utterance is then most likely the answer ("an hour", "high"), not a
 * fresh command. [planning] plays the same part while a day is being planned: the next utterance is
 * then the task list, a yes, or a change to the proposal.
 */
data class ParseContext(
    val openTaskTitles: List<String> = emptyList(),
    val draft: TaskDraft? = null,
    /**
     * The last few turns, oldest first, so a follow-up can be understood at all: "move it to six"
     * and "the second one" mean nothing without what was just said.
     */
    val history: List<ConversationTurn> = emptyList(),
    /**
     * Set while the assistant is waiting for an answer about an overlap (or an odd start time): the
     * next utterance is then "yes", "keep both", a new time or a new length, not a fresh command.
     */
    val conflict: ConflictQuestion? = null,
    val planning: PlanningState? = null
) {
    /**
     * The detail a short reply is most likely giving. While a conflict is being settled that is a new
     * start time, even though the task's own next missing detail may be something else.
     */
    fun expectedField(): DraftField? =
        if (conflict?.forDraft == true) DraftField.DEADLINE else draft?.nextMissing()

    companion object {
        /** How many past turns the caller should collect; the parser may use fewer. */
        const val HISTORY_TURNS = 6
    }
}

/** One line of the conversation so far, as the model should see it. */
data class ConversationTurn(val fromUser: Boolean, val text: String)

/**
 * Turns what the user said into one supported [TaskCommand].
 * Today: [RuleBasedIntentParser]. Later: the local quantized LLM emitting the same contract.
 * Implementations must never change task state themselves.
 */
interface IntentParser {
    suspend fun parse(utterance: String, context: ParseContext = ParseContext()): TaskCommand
}

/** Hands-free speech input: the recognizer decides when the user has finished talking. */
interface SpeechToText {
    /** False until a model is installed and the mic is allowed; the UI then falls back to typed input. */
    val isReady: Boolean

    /**
     * Records one utterance and returns what was said, or "" if nothing was heard.
     *
     * Hands-free ([manual] false): waits for the user to speak and stops when they pause.
     * Push-to-talk ([manual] true): records until [finishUtterance] is called (button released).
     *
     * [onCaptured] runs once recording has ended, before the (slower) transcription. Cancelling the
     * caller releases the microphone.
     */
    suspend fun listen(manual: Boolean = false, onCaptured: () -> Unit = {}): String

    /**
     * Ends the recording now: the button was released, or the user tapped "done". Returns true if a
     * pending [listen] will now return what was recorded; false if there was nothing to end.
     */
    fun finishUtterance(): Boolean = false
}

/** Speech output. [speak] returns when the utterance has finished (or was stopped). */
interface Speaker {
    suspend fun speak(text: String)
    fun stop()
}

/**
 * Speaks with [neural] once its voice model is installed, and with [platform] until then, so the
 * assistant is never mute while the download is still running.
 */
class FallbackSpeaker(private val neural: SherpaSpeaker, private val platform: Speaker) : Speaker {
    override suspend fun speak(text: String) {
        // A voice that is installed but fails to load or play must not leave the assistant mute.
        if (neural.isReady && neural.trySpeak(text)) return
        platform.speak(text)
    }

    override fun stop() {
        neural.stop()
        platform.stop()
    }
}

/** Placeholder until the STT model is chosen and integrated. */
class UnavailableSpeechToText : SpeechToText {
    override val isReady: Boolean = false
    override suspend fun listen(manual: Boolean, onCaptured: () -> Unit): String = ""
}

/** Placeholder until the TTS engine is chosen; the transcript still shows every reply. */
class SilentSpeaker : Speaker {
    override suspend fun speak(text: String) = Unit
    override fun stop() = Unit
}
