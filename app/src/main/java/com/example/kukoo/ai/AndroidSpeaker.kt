package com.example.kukoo.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * [Speaker] backed by the platform [TextToSpeech] engine.
 * [speak] suspends until the utterance finishes, is stopped, or fails. Calls made while the
 * engine is still initializing are buffered (latest wins, matching QUEUE_FLUSH semantics).
 */
class AndroidSpeaker(context: Context) : Speaker {
    private val lock = Any()
    private var ready = false
    private var failed = false
    private var released = false

    /** utteranceId -> continuation waiting for that utterance to end. */
    private val waiting = HashMap<String, CancellableContinuation<Unit>>()

    /** Utterances requested before init completed: id to text. */
    private val pending = ArrayList<Pair<String, String>>()

    private val initDone = CompletableDeferred<Unit>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        initDone.complete(Unit)
        val toSpeak: List<Pair<String, String>>
        synchronized(lock) {
            if (status == TextToSpeech.SUCCESS && !released) {
                ready = true
                toSpeak = pending.toList()
            } else {
                failed = true
                toSpeak = emptyList()
            }
            pending.clear()
        }
        if (ready) {
            configure()
            // Only the last buffered utterance survives a flush; resolve the earlier ones now.
            toSpeak.dropLast(1).forEach { finish(it.first) }
            toSpeak.lastOrNull()?.let { (id, text) -> start(id, text) }
        } else {
            toSpeak.forEach { finish(it.first) }
            finishAll()
        }
    }

    private fun configure() {
        tts.language = Locale.US
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    // Timers run off the main looper so the watchdog fires even when that looper is paused.
    override suspend fun speak(text: String) = withContext(Dispatchers.Default) { speakChecked(text) }

    private suspend fun speakChecked(text: String) {
        if (text.isBlank()) return
        // Watchdog: a stuck or silent engine must never leave the caller waiting forever.
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { initDone.await() } == null) {
            // The engine never came up (missing TTS service, etc.): give up on speech for good.
            synchronized(lock) { failed = true; pending.clear() }
            finishAll()
            return
        }
        val finished = withTimeoutOrNull(utteranceTimeoutMs(text)) { speakAndWait(text) }
        if (finished == null) stop()
    }

    private fun utteranceTimeoutMs(text: String): Long =
        (INIT_GRACE_MS + text.length * MS_PER_CHAR).coerceAtMost(MAX_UTTERANCE_MS)

    private suspend fun speakAndWait(text: String) {
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            var startNow = false
            synchronized(lock) {
                if (released || failed) {
                    cont.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                waiting[id] = cont
                if (ready) startNow = true else pending.add(id to text)
            }
            cont.invokeOnCancellation {
                synchronized(lock) { waiting.remove(id); pending.removeAll { it.first == id } }
                tts.stop()
            }
            if (startNow) start(id, text)
        }
    }

    private fun start(id: String, text: String) {
        // QUEUE_FLUSH drops whatever was playing; its onStop/onDone resolves that caller.
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) finish(id)
    }

    override fun stop() {
        val dropped: List<String>
        synchronized(lock) {
            dropped = pending.map { it.first }
            pending.clear()
        }
        dropped.forEach { finish(it) }
        if (!failed) tts.stop()
    }

    /** Releases the engine. The speaker is unusable afterwards. */
    fun shutdown() {
        synchronized(lock) { released = true; pending.clear() }
        tts.stop()
        tts.shutdown()
        finishAll()
    }

    private fun finish(id: String?) {
        val cont = synchronized(lock) { waiting.remove(id) } ?: return
        if (cont.isActive) cont.resume(Unit)
    }

    private fun finishAll() {
        val all = synchronized(lock) { waiting.values.toList().also { waiting.clear() } }
        all.forEach { if (it.isActive) it.resume(Unit) }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 3_000L
        const val INIT_GRACE_MS = 3_000L
        const val MS_PER_CHAR = 60L
        const val MAX_UTTERANCE_MS = 45_000L
    }
}
