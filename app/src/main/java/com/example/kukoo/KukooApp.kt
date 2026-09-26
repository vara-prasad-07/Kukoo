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
class AppContainer(context: Context, val clock: Clock = Clock.systemDefaultZone()) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kukoo", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = SqliteTaskStore(appContext)
    val engine = TaskEngine(store, OfficeKitReplanner(), clock)

    /** Download state for the speech models; the LLM bundle is managed by GenieX itself. */
    val models = ModelRepository(appContext)

    /** Qwen3-VL-4B on the NPU. Exposed so the setup screen can pull and preload it. */
    val genieX = GenieXEngine(appContext)

    // The LLM maps an utterance to one TaskCommand; RuleBasedIntentParser catches everything it
    // gets wrong or is too slow for, so a missing or failing model never breaks a call.
    val parser: IntentParser = LlamaIntentParser(genieX)
    val stt: SpeechToText = SherpaSpeechToText(appContext, models)

    private val neuralSpeaker = SherpaSpeaker(appContext, models)

    // Until the neural voice is installed the platform engine stands in, so the app always talks.
    val speaker: Speaker = FallbackSpeaker(neuralSpeaker, AndroidSpeaker(appContext))

    val scheduler = CallScheduler(appContext, clock)

    /** Emitted when the scheduled call fires while the app is on screen. */
    val incomingCalls = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var appVisible: Boolean = false

    /**
     * Loads the NPU model in the background at startup. A cold qairt load takes tens of seconds —
     * far longer than the parser's per-turn timeout — so it must not happen inside a call turn.
     */
    fun warmUp() {
        scope.launch {
            runCatching { genieX.initSdk() }
                .onFailure { Log.w(TAG, "GenieX init failed; the rule parser will handle this session", it) }
            // Prints the chipset and the AI Hub bundles actually available on this device.
            runCatching { genieX.logCatalog() }
            if (genieX.isModelDownloaded) {
                val started = System.currentTimeMillis()
                var ok = genieX.ensureLoaded()
                Log.i(TAG, "NPU model preload ok=$ok in ${System.currentTimeMillis() - started} ms")
                // A load can fail while the NPU is still being released by a previous process. Until it
                // succeeds every turn falls back to the rule parser, so keep trying for a while.
                for ((attempt, wait) in RELOAD_DELAYS_MS.withIndex()) {
                    if (ok) break
                    delay(wait)
                    val t = System.currentTimeMillis()
                    ok = genieX.retryLoad()
                    Log.i(TAG, "NPU model reload ${attempt + 1}/${RELOAD_DELAYS_MS.size} ok=$ok in ${System.currentTimeMillis() - t} ms")
                }
                if (!ok) Log.w(TAG, "NPU model never loaded; this session uses the rule parser")
            } else {
                Log.i(TAG, "NPU model not downloaded yet; open AI models in the Home menu")
            }
        }
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
    }
}
