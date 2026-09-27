package com.example.kukoo

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.example.kukoo.ai.FallbackSpeaker
import com.example.kukoo.ai.GenieXEngine
import com.example.kukoo.ai.IntentParser
import com.example.kukoo.ai.LlamaIntentParser
import com.example.kukoo.ai.ModelRepository
import com.example.kukoo.ai.AndroidSpeaker
import com.example.kukoo.ai.SherpaSpeaker
import com.example.kukoo.ai.SherpaSpeechToText
import com.example.kukoo.ai.Speaker
import com.example.kukoo.ai.SpeechToText
import com.example.kukoo.call.CallScheduler
import com.example.kukoo.data.DemoData
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.OfficeKitReplanner
import com.example.kukoo.domain.TaskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.time.Clock

class KukooApp : Application() {
    lateinit var container: AppContainer
        internal set // tests swap in a container built on a fixed clock

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }
}

/**
 * Hand-rolled dependency container. The three AI seams (parser, stt, speaker) are the only
 * lines that change when the real local LLM / STT / TTS are plugged in.
 *
 * Everything below runs on-device: Qwen3-VL-4B on the Hexagon NPU via GenieX for understanding,
 * and sherpa-onnx for speech in both directions. The network is used only to download the models.
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    /**
     * Test seam only. Understanding is the NPU model's job and there is no NPU on the JVM, so the
     * engine/ViewModel tests pass a deterministic parser instead of the real one. Production always
     * uses [LlamaIntentParser]; nothing in the app supplies this.
     */
    parserOverride: IntentParser? = null,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kukoo", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = SqliteTaskStore(appContext)
    val engine = TaskEngine(store, OfficeKitReplanner(), clock)

    /** Download state for the speech models; the LLM bundle is managed by GenieX itself. */
    val models = ModelRepository(appContext)

    /** Qwen3-VL-4B on the NPU. Exposed so the setup screen can pull and preload it. */
    val genieX = GenieXEngine(appContext)

    // The LLM maps an utterance to one TaskCommand. It is the only thing that understands the
    // user: there is no pattern-matching stand-in, so a reply is either the model's or an honest
    // "I didn't understand".
    val parser: IntentParser = parserOverride ?: LlamaIntentParser(genieX)
    private val sherpaStt = SherpaSpeechToText(appContext, models)
    val stt: SpeechToText = sherpaStt

    private val neuralSpeaker = SherpaSpeaker(appContext, models)

    // Until the neural voice is installed the platform engine stands in, so the app always talks.
    val speaker: Speaker = FallbackSpeaker(neuralSpeaker, AndroidSpeaker(appContext))

    val scheduler = CallScheduler(appContext, clock)

    /** Emitted when a scheduled call fires while the app is on screen: the task id, or NO_TASK for the daily call. */
    val incomingCalls = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    @Volatile
    var appVisible: Boolean = false

    /**
     * Loads the NPU model in the background at startup. A cold qairt load takes tens of seconds —
     * far longer than the parser's per-turn timeout — so it must not happen inside a call turn.
     */
    fun warmUp() {
        scope.launch {
            runCatching { genieX.initSdk() }
                .onFailure { Log.w(TAG, "GenieX init failed; the assistant cannot understand requests this session", it) }
            // Prints the chipset and the AI Hub bundles actually available on this device.
            runCatching { genieX.logCatalog() }
            if (genieX.isModelDownloaded) {
                val started = System.currentTimeMillis()
                var ok = genieX.ensureLoaded()
                Log.i(TAG, "NPU model preload ok=$ok in ${System.currentTimeMillis() - started} ms")
                // The load needs several GB on the NPU and fails with QNN 0x3ef while another
                // process still holds it, so it is retried for the life of the app rather than
                // only at startup: memory frees when other apps close, and the very next turn
                // then gets the model. Until it succeeds the assistant says the model is not ready.
                var attempt = 0
                while (!ok) {
                    delay(RELOAD_DELAYS_MS.getOrElse(attempt) { RELOAD_INTERVAL_MS })
                    attempt++
                    val t = System.currentTimeMillis()
                    ok = genieX.retryLoad()
                    Log.i(TAG, "NPU model reload $attempt ok=$ok in ${System.currentTimeMillis() - t} ms")
                    if (!ok && attempt == RELOAD_DELAYS_MS.size) {
                        Log.w(TAG, "NPU model still not loaded; retrying quietly")
                    }
                }
                Log.i(TAG, "NPU model loaded after $attempt retries")
            } else {
                Log.i(TAG, "NPU model not downloaded yet; open AI models in the Home menu")
            }
            // Only now: the ONNX speech models add ~300 MB of native heap, and loading them while
            // qairt is mapping the NPU bundle makes Model create() fail with "Model loading failed".
            warmUpSpeech()
        }
    }

    private fun warmUpSpeech() {
        scope.launch { runCatching { sherpaStt.warmUp() }.onFailure { Log.w(TAG, "STT warm-up failed", it) } }
        scope.launch { runCatching { neuralSpeaker.warmUp() }.onFailure { Log.w(TAG, "TTS warm-up failed", it) } }
    }

    /** Demo tasks on the very first launch only (never re-added after the user deletes them). */
    fun seedOnFirstRun() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        DemoData.reset(store, clock)
        prefs.edit { putBoolean(KEY_SEEDED, true) }
    }

    fun resetDemoData() = DemoData.reset(store, clock)

    private companion object {
        const val TAG = "AppContainer"
        const val KEY_SEEDED = "seeded"
        val RELOAD_DELAYS_MS = listOf(4_000L, 10_000L, 20_000L, 40_000L)

        /** After the quick retries, keep trying at this interval: a busy NPU frees up eventually. */
        const val RELOAD_INTERVAL_MS = 120_000L
    }
}
