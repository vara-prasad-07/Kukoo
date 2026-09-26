package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime

/** Minimal text-completion surface the parser needs from a local LLM runtime. */
interface LlamaEngine {
    /** Loads the model if needed. Returns false when the runtime or model is unavailable. */
    fun ensureLoaded(): Boolean

    /**
     * Blocking completion; throws on failure. When [grammar] (GBNF) is given, sampling is constrained so the
     * output can only be text the grammar accepts.
     */
    fun complete(prompt: String, maxTokens: Int, grammar: String? = null): String
}

/**
 * JNI binding to llama.cpp. Expects a native library `libkukoo_llama.so` exporting the three
 * functions below (built from llama.cpp with the NDK) and a GGUF model file. When either is
 * missing, [ensureLoaded] is false and [LlamaIntentParser] silently uses its fallback parser.
 */
class NativeLlamaEngine(private val modelFile: File, private val contextSize: Int = 2048) : LlamaEngine {
    private var handle = 0L

    @Synchronized
    override fun ensureLoaded(): Boolean {
        if (handle != 0L) return true
        if (!nativeAvailable || !modelFile.isFile) return false
        handle = runCatching { nativeLoad(modelFile.absolutePath, contextSize) }.getOrDefault(0L)
        return handle != 0L
    }

    @Synchronized
    override fun complete(prompt: String, maxTokens: Int, grammar: String?): String {
        check(handle != 0L) { "Model not loaded" }
        return nativeComplete(handle, prompt, maxTokens, grammar)
    }

    @Synchronized
    fun release() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    private external fun nativeLoad(modelPath: String, contextSize: Int): Long
    private external fun nativeComplete(handle: Long, prompt: String, maxTokens: Int, grammar: String?): String
    private external fun nativeFree(handle: Long)

    private companion object {
        val nativeAvailable: Boolean = try {
            System.loadLibrary("kukoo_llama")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}

/**
 * Maps an utterance to a [TaskCommand] with a local LLM, falling back to [fallback] whenever the
 * model is unavailable, too slow, or returns something that isn't a valid command. It never
 * changes task state itself.
 */
class LlamaIntentParser(
    private val engine: LlamaEngine,
    private val fallback: IntentParser = RuleBasedIntentParser(),
    private val timeoutMs: Long = 8_000,
) : IntentParser {

    constructor(context: Context) : this(
        NativeLlamaEngine(File(context.applicationContext.filesDir, "models/kukoo-intent.gguf")),
    )

    // One inference at a time; a call that times out may still occupy the native side.
    private val busy = Mutex()

    override suspend fun parse(utterance: String, context: ParseContext): TaskCommand {
        val command = tryLlm(utterance, context)
        return command ?: fallback.parse(utterance, context)
    }

    private suspend fun tryLlm(utterance: String, context: ParseContext): TaskCommand? {
        if (utterance.isBlank() || !busy.tryLock()) return null
        return try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Default) {
                    if (!engine.ensureLoaded()) return@withContext null
                    val raw = engine.complete(buildPrompt(utterance, context), MAX_TOKENS, LlamaGrammar.COMMAND_JSON)
                    toCommand(raw, utterance)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "LLM parse failed, using fallback", e)
            null
        } finally {
            busy.unlock()
        }
    }

    internal fun buildPrompt(utterance: String, context: ParseContext): String {
        val titles = if (context.openTaskTitles.isEmpty()) "(none)" else
            context.openTaskTitles.joinToString("\n") { "- $it" }
        val system = """
            You convert a spoken request about a to-do list into ONE JSON object. Output JSON only.
            Schema: {"action": one of add_task, query_tasks, update_task, complete_task, reopen_task, delete_task, replan, undo, snooze, end_call, unsupported,
            "title": new task title (add_task),
            "recurrence": none|daily|weekdays|weekly|monthly (add_task / update_task),
            "notes": free-text notes for the task (add_task / update_task),
            "target": exact open task title it refers to, or "last" for the task just discussed,
            "new_title": renamed title (update_task),
            "scope": query_tasks: today|all_open; replan: afternoon|day,
            "day": today|tomorrow|monday..sunday, "time": "HH:mm" 24-hour,
            "clear_deadline": true|false, "duration_min": integer minutes (for snooze: how long, default 15), "priority": high|medium|low}
            A phrase naming an activity with a time or a repeat is add_task even if the word "add" is missing:
            "gym everyday at 12pm" -> add_task title "gym", recurrence daily, time 12:00;
            "read book at 9pm" -> add_task title "read book", time 21:00.
            Use update_task only when the user clearly refers to one of the open tasks listed below.
            Omit fields that do not apply. Use "unsupported" if the request is not about these actions.
            Never compute dates; only name the day. Open tasks:
            $titles
        """.trimIndent()
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$utterance<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Returns null when [raw] is not a valid, complete command (caller then falls back). */
    internal fun toCommand(raw: String, utterance: String): TaskCommand? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null

        fun str(key: String) = json.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        fun ref(): TaskRef? = when (val t = str("target")) {
            null -> null
            else -> if (t.equals("last", ignoreCase = true)) TaskRef.Last else TaskRef.ByTitle(t)
        }

        return when (str("action")?.lowercase()) {
            "add_task" -> str("title")?.let {
                TaskCommand.AddTask(
                    it, deadline(json), duration(json), priority(str("priority")),
                    Recurrence.fromName(str("recurrence")), str("notes"),
                )
            }
            "query_tasks" -> TaskCommand.QueryTasks(
                if (str("scope")?.lowercase() == "all_open") QueryScope.ALL_OPEN else QueryScope.TODAY,
            )
            "update_task" -> ref()?.let { r ->
                val clear = json.optBoolean("clear_deadline", false)
                val patch = TaskPatch(
                    title = str("new_title"),
                    deadline = if (clear) null else deadline(json),
                    clearDeadline = clear,
                    durationMin = duration(json),
                    priority = priority(str("priority")),
                    recurrence = str("recurrence")?.let { Recurrence.fromName(it) },
                    notes = str("notes"),
                )
                if (patch.isEmpty) null else TaskCommand.UpdateTask(r, patch)
            }
            "complete_task" -> ref()?.let { TaskCommand.CompleteTask(it) }
            "reopen_task" -> ref()?.let { TaskCommand.ReopenTask(it) }
            "delete_task" -> ref()?.let { TaskCommand.DeleteTask(it) }
            "replan" -> TaskCommand.Replan(
                if (str("scope")?.lowercase() == "afternoon") PlanScope.AFTERNOON else PlanScope.DAY,
            )
            "undo" -> TaskCommand.Undo
            "snooze" -> TaskCommand.Snooze(duration(json) ?: 15)
            "end_call" -> TaskCommand.EndCall
            // Let the rules have a go before giving up on the utterance.
            "unsupported" -> null
            else -> null
        }
    }

    private fun deadline(json: JSONObject): DeadlineSpec? {
        val day = json.optString("day", "").trim().lowercase().let(::dayRef)
        val time = json.optString("time", "").trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return if (day == null && time == null) null else DeadlineSpec.Relative(day, time)
    }

    private fun dayRef(name: String): DayRef? = when (name) {
        "today" -> DayRef.Today
        "tomorrow" -> DayRef.Tomorrow
        else -> DayOfWeek.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(DayRef::Weekday)
    }

    private fun duration(json: JSONObject): Int? =
        json.optInt("duration_min", 0).takeIf { it > 0 }

    private fun priority(name: String?): Priority? =
        Priority.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private companion object {
        const val TAG = "LlamaIntentParser"
        const val MAX_TOKENS = 160
    }
}
