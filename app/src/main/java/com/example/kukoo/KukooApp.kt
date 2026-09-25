package com.example.kukoo

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.example.kukoo.ai.IntentParser
import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.ai.SilentSpeaker
import com.example.kukoo.ai.Speaker
import com.example.kukoo.ai.SpeechToText
import com.example.kukoo.ai.UnavailableSpeechToText
import com.example.kukoo.call.CallScheduler
import com.example.kukoo.data.DemoData
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.TaskEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Clock

class KukooApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Hand-rolled dependency container. The three AI seams (parser, stt, speaker) are the only
 * lines that change when the real local LLM / STT / TTS are plugged in.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kukoo", Context.MODE_PRIVATE)

    val clock: Clock = Clock.systemDefaultZone()
    val store = SqliteTaskStore(appContext)
    val engine = TaskEngine(store, LocalReplanner(), clock)

    val parser: IntentParser = RuleBasedIntentParser()
    val stt: SpeechToText = UnavailableSpeechToText()
    val speaker: Speaker = SilentSpeaker()

    val scheduler = CallScheduler(appContext, clock)

    /** Emitted when the scheduled call fires while the app is on screen. */
    val incomingCalls = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var appVisible: Boolean = false

    /** Demo tasks on the very first launch only (never re-added after the user deletes them). */
    fun seedOnFirstRun() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        DemoData.reset(store, clock)
        prefs.edit { putBoolean(KEY_SEEDED, true) }
    }

    fun resetDemoData() = DemoData.reset(store, clock)

    private companion object {
        const val KEY_SEEDED = "seeded"
    }
}
