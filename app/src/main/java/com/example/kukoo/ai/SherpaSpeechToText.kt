package com.example.kukoo.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline [SpeechToText] on sherpa-onnx: Parakeet TDT 110M when installed, else Moonshine tiny.
 *
 * Two ways to capture, both ending in the same decode:
 *  - push-to-talk ([listen] with `manual = true`): records until [finishUtterance] (button release);
 *  - hands-free: waits for speech, and stops by itself once the user pauses.
 *
 * Hands-free finds speech with an energy gate against an adapting noise floor. The end of an
 * utterance is judged relative to how loud that utterance was, so steady room noise does not keep
 * a recording open. Nothing leaves the device and no network is used.
 */
class SherpaSpeechToText(context: Context, private val models: ModelRepository) : SpeechToText {
    private val appContext = context.applicationContext

    private val initLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var initFailed = false

    @Volatile
    private var speechInProgress = false

    @Volatile
    private var manualActive = false

    @Volatile
    private var endNow = false

    override fun finishUtterance(): Boolean {
        // Push-to-talk: a release always ends the recording, even one that has only just started.
        if (!manualActive && !speechInProgress) return false
        endNow = true
        return true
    }

    /** The most accurate recognizer that is installed, or null when there is none. */
    private fun bestModel(): SpeechModel? = when {
        models.isInstalled(SpeechModel.STT_PARAKEET) -> SpeechModel.STT_PARAKEET
        models.isInstalled(SpeechModel.STT_MOONSHINE) -> SpeechModel.STT_MOONSHINE
        else -> null
    }

    override val isReady: Boolean
        get() = bestModel() != null && hasMicPermission()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun listen(manual: Boolean, onCaptured: () -> Unit): String {
        check(isReady) { "Speech recognition unavailable" }
        endNow = false
        manualActive = manual
        val audio = try {
            capture(manual)
        } finally {
            manualActive = false
            speechInProgress = false
            endNow = false
        } ?: return ""
        onCaptured()
        return transcribe(audio)
    }

    /**
     * Records one utterance. Returns null when nobody spoke within [NO_SPEECH_MS]. The microphone
     * is always released, including when the caller is cancelled.
     */
    private suspend fun capture(manual: Boolean): FloatArray? = withContext(Dispatchers.IO) {
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(SAMPLE_RATE * 4 / 2)

        // MIC rather than VOICE_RECOGNITION: on this phone the latter comes out ~10x quieter.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            minBytes * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Microphone unavailable")
        }

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone is in use by another app")
            }

            val buf = FloatArray(CHUNK)
            val preroll = ArrayDeque<FloatArray>()
            val speech = ArrayList<FloatArray>()
            var noise = INITIAL_NOISE
            var loudRun = 0
            var voicedMs = 0
            var silentMs = 0
            var elapsedMs = 0
            var utterancePeak = 0f
            // Push-to-talk is "speaking" from the first sample: the button is the endpoint.
            var speaking = manual
            if (manual) voicedMs = MIN_VOICED_MS
            speechInProgress = speaking

            while (true) {
                ensureActive()
                if (endNow && speaking && voicedMs >= MIN_VOICED_MS) break
                val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("Microphone read failed ($n)")
                if (n == 0) continue

                val frame = buf.copyOf(n)
                val ms = n * 1000 / SAMPLE_RATE
                elapsedMs += ms
                val level = rms(frame)

                if (!speaking) {
                    val startLevel = min(max(MIN_LEVEL, noise * SPEECH_RATIO), MAX_START_LEVEL)
                    if (level > startLevel) {
                        loudRun++
                    } else {
                        loudRun = 0
                        noise = (noise * 0.95f + level * 0.05f).coerceAtMost(MAX_NOISE)
                    }
                    preroll.addLast(frame)
                    while (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
                    if (loudRun >= START_CHUNKS) {
                        speaking = true
                        speechInProgress = true
                        speech.addAll(preroll)
                        preroll.clear()
                        voicedMs = loudRun * ms
                        silentMs = 0
                        utterancePeak = level
                    } else if (elapsedMs >= NO_SPEECH_MS) {
                        Log.d(TAG, "no speech heard")
                        return@withContext null
                    }
                } else {
                    speech.add(frame)
                    if (manual) {
                        if (speech.size * ms >= MAX_UTTERANCE_MS) break
                        continue
                    }
                    utterancePeak = max(utterancePeak, level)
                    // Quiet means quiet relative to how loudly this person is speaking, so a noisy
                    // room (which sits above the start gate) does not keep the recording open.
                    val quietBar = min(max(MIN_LEVEL, utterancePeak * QUIET_FRACTION), MAX_START_LEVEL)
                    if (level > quietBar) {
                        voicedMs += ms
                        silentMs = 0
                    } else {
                        silentMs += ms
                    }
                    val tooLong = speech.size * ms >= MAX_UTTERANCE_MS
                    if (silentMs >= END_SILENCE_MS || tooLong) {
                        if (voicedMs >= MIN_VOICED_MS) break
                        // A click or a cough, not speech: forget it and keep waiting.
                        speech.clear()
                        speaking = false
                        speechInProgress = false
                        loudRun = 0
                    }
                }
            }

            val size = speech.sumOf { it.size }
            FloatArray(size).also { out ->
                var at = 0
                for (chunk in speech) {
                    chunk.copyInto(out, at)
                    at += chunk.size
                }
            }
        } finally {
            speechInProgress = false
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun transcribe(raw: FloatArray): String = withContext(Dispatchers.Default) {
        val audio = normalized(raw)
        val engine = engine() ?: return@withContext ""
        try {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(audio, SAMPLE_RATE)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
                    .also { Log.i(TAG, "transcript (${audio.size / 16} ms): \"$it\"") }
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode failed", e)
            ""
        }
    }

    /** Phone mics differ a lot in level; bring quiet speech up to a level the model was trained on. */
    private fun normalized(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio
        val peak = audio.maxOf { abs(it) }
        if (peak < 1e-4f) return audio
        val gain = (TARGET_PEAK / peak).coerceIn(1f, MAX_GAIN)
        return if (gain == 1f) audio else FloatArray(audio.size) { audio[it] * gain }
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s * s
        return sqrt(sum / samples.size).toFloat()
    }

    /** Loads the model ahead of the first turn, so the first spoken request is not slow. */
    fun warmUp() {
        if (isReady) engine()
    }

    /** Releases the model. */
    fun shutdown() {
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
            loadedModel = null
            initFailed = true
        }
    }

    /** The model the loaded [recognizer] was built from, so a newly installed better one replaces it. */
    private var loadedModel: SpeechModel? = null

    private fun engine(): OfflineRecognizer? {
        val wanted = bestModel() ?: return null
        recognizer?.let { if (loadedModel == wanted) return it }
        synchronized(initLock) {
            recognizer?.let { if (loadedModel == wanted) return it }
            if (initFailed && loadedModel == wanted) return null
            recognizer?.release()
            recognizer = null
            val config = recognizerConfig(wanted)
            if (config == null) {
                Log.w(TAG, "${wanted.dirName} is incomplete")
                return null
            }
            return try {
                OfflineRecognizer(assetManager = null, config = config).also {
                    recognizer = it
                    loadedModel = wanted
                    initFailed = false
                    Log.i(TAG, "STT ready: ${wanted.dirName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT init failed for ${wanted.dirName}", e)
                loadedModel = wanted
                initFailed = true
                null
            }
        }
    }

    private fun recognizerConfig(model: SpeechModel): OfflineRecognizerConfig? {
        val dir = models.dirFor(model).takeIf { models.isInstalled(model) } ?: return null
        // Files are matched by name prefix so int8 and float bundles both work, and so
        // "cached_decode" can never pick up "uncached_decode" (which contains that text).
        fun onnx(prefix: String): File? = ModelRepository.findFile(dir) { f ->
            f.name.endsWith(".onnx") && f.name.startsWith(prefix)
        }
        val tokens = ModelRepository.findFileNamed(dir, "tokens.txt") ?: return null
        val modelConfig = when (model) {
            SpeechModel.STT_PARAKEET -> {
                val encoder = onnx("encoder") ?: return null
                val decoder = onnx("decoder") ?: return null
                val joiner = onnx("joiner") ?: return null
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = PARAKEET_THREADS,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                )
            }
            else -> {
                val preprocess = onnx("preprocess") ?: return null
                val encode = onnx("encode") ?: return null
                val uncached = onnx("uncached_decode") ?: return null
                val cached = onnx("cached_decode") ?: return null
                OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = preprocess.absolutePath,
                        encoder = encode.absolutePath,
                        uncachedDecoder = uncached.absolutePath,
                        cachedDecoder = cached.absolutePath,
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = THREADS,
                    provider = "cpu",
                    modelType = "moonshine",
                )
            }
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
    }

    private companion object {
        const val TAG = "SherpaSpeechToText"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 800 // 50 ms
        const val THREADS = 2
        const val PARAKEET_THREADS = 4 // 110M transducer; ~real-time x20 on this phone's CPU

        /** Speech must rise this far above the room's noise floor, and never below [MIN_LEVEL]. */
        const val SPEECH_RATIO = 3f
        const val MIN_LEVEL = 0.012f
        const val INITIAL_NOISE = 0.004f
        const val MAX_NOISE = 0.04f
        const val MAX_START_LEVEL = 0.06f

        /** An utterance is over once it stays below this fraction of its own peak level. */
        const val QUIET_FRACTION = 0.3f

        /** Peak scaling before decoding; the model expects normally-loud speech. */
        const val TARGET_PEAK = 0.6f
        const val MAX_GAIN = 40f

        /** 3 loud chunks (150 ms) in a row start an utterance; 400 ms before it is kept. */
        const val START_CHUNKS = 3
        const val PREROLL_CHUNKS = 8

        /** How long the user may pause mid-sentence before the utterance counts as finished. */
        const val END_SILENCE_MS = 900
        const val MIN_VOICED_MS = 250
        const val MAX_UTTERANCE_MS = 15_000
        const val NO_SPEECH_MS = 10_000
    }
}
