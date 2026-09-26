package com.example.kukoo.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tap-to-talk [SpeechToText] on the platform [SpeechRecognizer], using the system default engine (online or offline).
 * The recognizer is only touched on the main thread, as the framework requires.
 */
class AndroidSpeechToText(context: Context) : SpeechToText {
    private val appContext = context.applicationContext

    override val isReady: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    private var result: CompletableDeferred<String>? = null

    /** Latest partial hypothesis, used if the final result never arrives. */
    private var lastPartial = ""

    override suspend fun startListening() {
        check(isReady) { "Speech recognition unavailable" }
        withContext(Dispatchers.Main.immediate) {
            release()
            val done = CompletableDeferred<String>()
            result = done
            lastPartial = ""
            val created = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = created
            created.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val best = results.firstHypothesis()
                    Log.d(TAG, "onResults: \"$best\"")
                    done.complete(best)
                }

                // Any error (NO_MATCH, SPEECH_TIMEOUT, NETWORK, ...) still yields whatever partial
                // speech was captured; it only ends as an empty transcript when nothing was heard.
                override fun onError(error: Int) {
                    Log.w(TAG, "onError: ${errorName(error)} ($error), partial=\"$lastPartial\"")
                    done.complete(lastPartial)
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "onReadyForSpeech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "onBeginningOfSpeech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    Log.v(TAG, "onRmsChanged: $rmsdB")
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "onEndOfSpeech")
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults.firstHypothesis()
                    if (text.isNotEmpty()) lastPartial = text
                    Log.d(TAG, "onPartialResults: \"$text\"")
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            created.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                },
            )
        }
    }

    override suspend fun stopAndTranscribe(): String {
        val done = result ?: return ""
        withContext(Dispatchers.Main.immediate) { recognizer?.stopListening() }
        val text = withTimeoutOrNull(RESULT_TIMEOUT_MS) { done.await() } ?: lastPartial
        withContext(Dispatchers.Main.immediate) { release() }
        return text.trim()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
        result = null
    }

    private fun Bundle?.firstHypothesis(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        10 -> "ERROR_TOO_MANY_REQUESTS"
        11 -> "ERROR_SERVER_DISCONNECTED"
        12 -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        13 -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN"
    }

    private companion object {
        const val TAG = "AndroidSpeechToText"
        const val RESULT_TIMEOUT_MS = 5000L
    }
}
