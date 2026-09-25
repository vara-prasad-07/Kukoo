package com.example.kukoo.ai

import com.example.kukoo.domain.TaskCommand

/** Extra context an LLM-backed parser can use to ground the request. */
data class ParseContext(val openTaskTitles: List<String> = emptyList())

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
