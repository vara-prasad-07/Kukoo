package com.example.kukoo.ai

import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft

/**
 * Extra context a parser can use to read the request.
 *
 * [draft] is set while the assistant is in the middle of adding a task and has just asked for a
 * missing detail: the next utterance is then most likely the answer ("an hour", "high"), not a
 * fresh command.
 */
data class ParseContext(
    val openTaskTitles: List<String> = emptyList(),
    val draft: TaskDraft? = null
)

/**
 * Turns what the user said into one supported [TaskCommand].
 * Today: [RuleBasedIntentParser]. Later: the local quantized LLM emitting the same contract.
 * Implementations must never change task state themselves.
 */
interface IntentParser {
    suspend fun parse(utterance: String, context: ParseContext = ParseContext()): TaskCommand
}

/** Tap-to-talk speech input: press starts recording, release returns the transcript. */
interface SpeechToText {
    /** False until a model is installed; the UI then falls back to typed / tap-to-send input. */
    val isReady: Boolean
    suspend fun startListening()
    suspend fun stopAndTranscribe(): String
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
        if (neural.isReady) neural.speak(text) else platform.speak(text)
    }

    override fun stop() {
        neural.stop()
        platform.stop()
    }
}

/** Placeholder until the STT model is chosen and integrated. */
class UnavailableSpeechToText : SpeechToText {
    override val isReady: Boolean = false
    override suspend fun startListening() = Unit
    override suspend fun stopAndTranscribe(): String = ""
}

/** Placeholder until the TTS engine is chosen; the transcript still shows every reply. */
class SilentSpeaker : Speaker {
    override suspend fun speak(text: String) = Unit
    override fun stop() = Unit
}
