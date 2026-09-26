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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline press-and-hold [SpeechToText] on sherpa-onnx with the Moonshine tiny English model.
 *
 * [startListening] opens the microphone and buffers 16 kHz mono float samples; [stopAndTranscribe]
 * closes it and decodes everything captured. Nothing leaves the device and no network is used.
 */
class SherpaSpeechToText(context: Context, private val models: ModelRepository) : SpeechToText {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initLock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var initFailed = false

    private var record: AudioRecord? = null
    private var captureJob: Job? = null

    /** Buffered microphone samples for the current press. Guarded by [samplesLock]. */
    private val samples = ArrayList<FloatArray>()
    private val samplesLock = Any()

    @Volatile
    private var capturing = false

    override val isReady: Boolean
        get() = models.isInstalled(SpeechModel.STT_MOONSHINE) && hasMicPermission()

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun startListening() {
        check(isReady) { "Speech recognition unavailable" }
        stopCapture()
        synchronized(samplesLock) { samples.clear() }

        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(SAMPLE_RATE * 4 / 2)

        val recorder = withContext(Dispatchers.IO) {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                minBytes * 2,
            )
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Microphone unavailable")
        }

        record = recorder
        capturing = true
        recorder.startRecording()
        captureJob = scope.launch {
            val buf = FloatArray(CHUNK)
            var total = 0
            while (capturing) {
                val n = recorder.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                synchronized(samplesLock) { samples.add(buf.copyOf(n)) }
                total += n
                // Bound memory (and decode time) on a press the user forgot to release.
                if (total >= SAMPLE_RATE * MAX_SECONDS) {
                    Log.w(TAG, "capture hit ${MAX_SECONDS}s limit")
                    capturing = false
                }
            }
        }
    }

    override suspend fun stopAndTranscribe(): String {
        stopCapture()
        val audio = synchronized(samplesLock) {
            val size = samples.sumOf { it.size }
            if (size == 0) return ""
            FloatArray(size).also { out ->
                var at = 0
                for (chunk in samples) {
                    chunk.copyInto(out, at)
                    at += chunk.size
                }
            }
        }
        // Too short to be speech: treat as a mis-tap rather than decoding silence.
        if (audio.size < SAMPLE_RATE / 4) return ""

        return withContext(Dispatchers.Default) {
            val engine = engine() ?: return@withContext ""
            try {
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(audio, SAMPLE_RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                        .also { Log.d(TAG, "transcript: \"$it\"") }
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode failed", e)
                ""
            }
        }
    }

    private suspend fun stopCapture() {
        capturing = false
        captureJob?.cancelAndJoin()
        captureJob = null
        record?.let { r ->
            runCatching {
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
                r.release()
            }
        }
        record = null
    }

    /** Releases the microphone and the model. */
    fun shutdown() {
        capturing = false
        captureJob?.cancel()
        runCatching { record?.release() }
        record = null
        synchronized(initLock) {
            recognizer?.release()
            recognizer = null
            initFailed = true
        }
    }

    private fun engine(): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(initLock) {
            recognizer?.let { return it }
            if (initFailed) return null
            val config = moonshineConfig()
            if (config == null) {
                Log.w(TAG, "Moonshine model not installed")
                return null
            }
            return try {
                OfflineRecognizer(assetManager = null, config = config).also {
                    recognizer = it
                    Log.i(TAG, "STT ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "STT init failed", e)
                initFailed = true
                null
            }
        }
    }

    private fun moonshineConfig(): OfflineRecognizerConfig? {
        val dir = models.dirFor(SpeechModel.STT_MOONSHINE)
            .takeIf { models.isInstalled(SpeechModel.STT_MOONSHINE) } ?: return null
        // Moonshine ships four graphs plus tokens.txt; located by name so the int8 and float
        // bundles both work.
        fun onnx(vararg parts: String): File? = ModelRepository.findFile(dir) { f ->
            f.name.endsWith(".onnx") && parts.all { f.name.contains(it) }
        }
        val preprocess = onnx("preprocess") ?: return null
        val encode = onnx("encode") ?: return null
        val uncached = onnx("uncached_decode") ?: return null
        val cached = onnx("cached_decode") ?: return null
        val tokens = ModelRepository.findFileNamed(dir, "tokens.txt") ?: return null
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
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
            ),
        )
    }

    private companion object {
        const val TAG = "SherpaSpeechToText"
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1600 // 100 ms
        const val THREADS = 2
        const val MAX_SECONDS = 30
    }
}
