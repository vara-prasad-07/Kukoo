package com.example.kukoo.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline neural [Speaker] on sherpa-onnx.
 *
 * Prefers Kokoro-82M (clearly the more natural voice) and falls back to the Piper VITS voice when
 * Kokoro is not installed. Audio is written to [AudioTrack] from inside the synthesis callback, so
 * playback starts on the first generated chunk instead of waiting for the whole sentence.
 *
 * Honours the same contract as the platform speaker it replaces: [speak] suspends until the audio
 * has actually finished, and [stop] cuts it off from any thread.
 */
class SherpaSpeaker(context: Context, private val models: ModelRepository) : Speaker {
    private val appContext = context.applicationContext

    private val initLock = Any()
    private var tts: OfflineTts? = null
    private var initFailed = false

    /** One utterance at a time; a second [speak] waits for the first to finish or be stopped. */
    private val speaking = Mutex()

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    /** Cleared for good if the streaming callback ever fails; synthesis then runs in one shot. */
    @Volatile
    private var streamingWorks = true

    /** False until at least one voice is installed, so callers can fall back to the transcript. */
    val isReady: Boolean
        get() = models.isInstalled(SpeechModel.TTS_KOKORO) || models.isInstalled(SpeechModel.TTS_PIPER)

    override suspend fun speak(text: String) {
        trySpeak(text)
    }

    /** Loads the voice ahead of the first reply, so the opening line is not delayed by model load. */
    fun warmUp() {
        if (isReady) engine()
    }

    /**
     * Speaks [text]; returns false when the voice could not produce any audio (it failed to load,
     * or no audio output was available), so the caller can use another voice instead.
     */
    suspend fun trySpeak(text: String): Boolean {
        if (text.isBlank()) return true
        return speaking.withLock {
            stopped = false
            withContext(Dispatchers.IO) { speakBlocking(text) }
        }
    }

    private suspend fun speakBlocking(text: String): Boolean {
        val engine = engine() ?: return false
        val sampleRate = engine.sampleRate()
        val player = newTrack(sampleRate) ?: return false
        track = player
        var framesWritten = 0L
        try {
            player.play()

            // Must be an explicit Function1 object, NOT a lambda. Kotlin 2.x compiles lambdas with
            // invokedynamic and D8 desugars them into a synthetic class carrying only the erased
            // invoke(Object)Object. sherpa-onnx's JNI looks up the specialized
            // invoke([F)Ljava/lang/Integer; and aborts the process when it is missing.
            // Returning 0 asks sherpa-onnx to abandon the rest of the utterance.
            val onSamples = object : Function1<FloatArray, Int> {
                override fun invoke(samples: FloatArray): Int {
                    if (stopped) return 0
                    val n = player.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (n > 0) framesWritten += n
                    return if (stopped) 0 else 1
                }
            }

            if (streamingWorks) {
                try {
                    engine.generateWithCallback(text, voiceId, SPEED, onSamples)
                } catch (e: Throwable) {
                    // Any JNI mismatch here would otherwise abort the process on the next call.
                    Log.w(TAG, "streaming synthesis unavailable; using one-shot generate()", e)
                    streamingWorks = false
                }
            }
            if (!streamingWorks && !stopped) {
                val audio = engine.generate(text = text, sid = voiceId, speed = SPEED)
                val n = player.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) framesWritten += n
            }
            if (!stopped) awaitDrain(player, framesWritten)
            return stopped || framesWritten > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
            return stopped || framesWritten > 0
        } finally {
            track = null
            runCatching {
                player.pause()
                player.flush()
                player.release()
            }
        }
    }

    /** Waits until the device has actually played every frame written, or [stop] intervenes. */
    private suspend fun awaitDrain(player: AudioTrack, framesWritten: Long) {
        if (framesWritten <= 0) return
        while (!stopped) {
            // Unsigned because the head position is an Int that wraps after ~27 hours of audio.
            val played = player.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (played >= framesWritten) return
            delay(20)
        }
    }

    override fun stop() {
        stopped = true
        // Dropping the buffered audio makes the interruption immediate; the generation callback
        // sees `stopped` on its next chunk and returns 0.
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    /** Releases the model. The speaker is unusable afterwards. */
    fun shutdown() {
        stop()
        synchronized(initLock) {
            tts?.release()
            tts = null
            initFailed = true
        }
    }

    // ---- model wiring --------------------------------------------------------------------

    /** Speaker id within the chosen voice bundle; Kokoro ships many, Piper exactly one. */
    private var voiceId: Int = 0

    private fun engine(): OfflineTts? {
        tts?.let { return it }
        synchronized(initLock) {
            tts?.let { return it }
            if (initFailed) return null
            val config = kokoroConfig() ?: piperConfig()
            if (config == null) {
                Log.w(TAG, "no TTS voice installed")
                return null
            }
            return try {
                // assetManager = null means "read these paths from the filesystem".
                OfflineTts(assetManager = null, config = config).also {
                    tts = it
                    Log.i(TAG, "TTS ready: ${it.numSpeakers()} speakers at ${it.sampleRate()} Hz")
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS init failed", e)
                initFailed = true
                null
            }
        }
    }

    private fun kokoroConfig(): OfflineTtsConfig? {
        val dir = models.dirFor(SpeechModel.TTS_KOKORO).takeIf { models.isInstalled(SpeechModel.TTS_KOKORO) }
            ?: return null
        // Files are located by scanning so a bundle that nests them deeper still works.
        val model = ModelRepository.findFile(dir) { it.name.endsWith(".onnx") } ?: return null
        val voices = ModelRepository.findFileNamed(dir, "voices.bin") ?: return null
        val tokens = ModelRepository.findFileNamed(dir, "tokens.txt") ?: return null
        val dataDir = ModelRepository.findDirNamed(dir, "espeak-ng-data") ?: return null
        // The multi-language bundles ship one lexicon per language; sherpa-onnx takes them as a
        // comma-separated list and ignores the ones the text does not need.
        val lexicons = dir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt") }
            .joinToString(",") { it.absolutePath }
        voiceId = KOKORO_VOICE_ID
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = voices.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath,
                    lexicon = lexicons,
                    lang = "en-us",
                ),
                numThreads = THREADS,
                provider = "cpu",
            ),
            // One sentence at a time keeps the first audio arriving as early as possible.
            maxNumSentences = 1,
        )
    }

    private fun piperConfig(): OfflineTtsConfig? {
        val dir = models.dirFor(SpeechModel.TTS_PIPER).takeIf { models.isInstalled(SpeechModel.TTS_PIPER) }
            ?: return null
        val model = ModelRepository.findFile(dir) { it.name.endsWith(".onnx") } ?: return null
        val tokens = ModelRepository.findFileNamed(dir, "tokens.txt") ?: return null
        val dataDir = ModelRepository.findDirNamed(dir, "espeak-ng-data") ?: return null
        voiceId = 0
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = THREADS,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
    }

    private fun newTrack(sampleRate: Int): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * 4 / 2) // at least ~0.5 s of float mono
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack unavailable", e)
        null
    }

    private companion object {
        const val TAG = "SherpaSpeaker"
        const val THREADS = 2
        const val SPEED = 1.0f

        /** Kokoro bundles many voices; 0 is the default US English speaker. */
        const val KOKORO_VOICE_ID = 0
    }
}
