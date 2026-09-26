package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.DraftField
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft
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
 *
 * Every detail the model returns is checked against the user's own words ([Grounding]) before it
 * is used, so a small model cannot invent a title, time, length or priority nobody said. A detail
 * that fails the check is simply left out, and the engine asks the user for it.
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
                    toCommand(raw, utterance, context)
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
        val system = buildString {
            append(INSTRUCTIONS)
            context.draft?.let { append("\n\n").append(draftSection(it)) }
            append("\n\nOpen tasks:\n").append(titles)
        }
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$utterance<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Tells the model what the assistant just asked, so a short reply is read as an answer. */
    private fun draftSection(draft: TaskDraft): String {
        fun known(value: Any?) = value?.toString()?.takeIf { it.isNotBlank() } ?: "missing"
        val asked = when (draft.nextMissing()) {
            DraftField.TITLE, null -> "title (the task's name; put it under \"title\", never \"target\")"
            DraftField.DEADLINE -> "deadline"
            DraftField.DURATION -> "duration"
            DraftField.PRIORITY -> "priority"
        }
        return listOf(
            "A new task is being set up. So far: title: ${known(draft.title)} | " +
                "deadline: ${if (draft.deadline == null) "missing" else "given"} | " +
                "duration: ${if (draft.durationMin == null) "missing" else "given"} | " +
                "priority: ${if (draft.priority == null) "missing" else "given"}",
            "The assistant just asked the user for the task's $asked.",
            "- If the reply gives any of these details, output {\"action\":\"answer\"} plus only the details they gave.",
            "- If they want to drop the task (cancel, never mind, forget it), output {\"action\":\"discard_task\"}.",
            "- Use add_task only if they clearly start a different new task. If they ask for something else, use that action.",
            "Answers look like: \"an hour and a half\" -> {\"action\":\"answer\",\"duration_min\":90}; " +
                "\"make it urgent\" -> {\"action\":\"answer\",\"priority\":\"high\"}; " +
                "\"by friday evening\" -> {\"action\":\"answer\",\"day\":\"friday\",\"time\":\"18:00\"}"
        ).joinToString("\n")
    }

    /** Returns null when [raw] is not a valid, complete command (caller then falls back). */
    internal fun toCommand(raw: String, utterance: String, context: ParseContext = ParseContext()): TaskCommand? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val pending = context.draft

        fun ref(): TaskRef? = when (val t = json.text("target")) {
            null -> null
            else -> if (t.equals("last", ignoreCase = true)) TaskRef.Last else TaskRef.ByTitle(t)
        }

        return when (json.text("action")?.lowercase()) {
            "add_task" -> newTask(json, utterance, pending)
            // An answer only means something while a question is open.
            "answer" -> pending?.let { TaskCommand.FillTask(details(json, utterance, it.nextMissing())) }
            "discard_task" -> if (pending != null) TaskCommand.DiscardDraft else null
            "query_tasks" -> TaskCommand.QueryTasks(
                if (json.text("scope")?.lowercase() == "all_open") QueryScope.ALL_OPEN else QueryScope.TODAY,
            )
            "update_task" -> ref()?.let { r ->
                val clear = json.optBoolean("clear_deadline", false)
                val patch = TaskPatch(
                    title = json.text("new_title")?.takeIf { Grounding.textGrounded(it, utterance) },
                    deadline = if (clear) null else deadline(json, utterance),
                    clearDeadline = clear,
                    durationMin = duration(json, utterance),
                    priority = priority(json.text("priority"), utterance),
                    recurrence = json.text("recurrence")
                        ?.takeIf { Grounding.recurrenceGrounded(utterance) }
                        ?.let { Recurrence.fromName(it) },
                    notes = json.text("notes")?.takeIf { Grounding.textGrounded(it, utterance) },
                )
                if (patch.isEmpty) null else TaskCommand.UpdateTask(r, patch)
            }
            "complete_task" -> ref()?.let { TaskCommand.CompleteTask(it) }
            "reopen_task" -> ref()?.let { TaskCommand.ReopenTask(it) }
            "delete_task" -> ref()?.let { TaskCommand.DeleteTask(it) }
            "replan" -> TaskCommand.Replan(
                if (json.text("scope")?.lowercase() == "afternoon") PlanScope.AFTERNOON else PlanScope.DAY,
            )
            "undo" -> TaskCommand.Undo
            "snooze" -> TaskCommand.Snooze(duration(json, utterance) ?: 15)
            "end_call" -> TaskCommand.EndCall
            // Let the rules have a go before giving up on the utterance.
            "unsupported" -> null
            else -> null
        }
    }

    /**
     * "Add …": a named task is an [TaskCommand.AddTask] and an unnamed one a [TaskCommand.StartTask],
     * matching what the rule parser produces. Mid-question, details without a name are the answer.
     */
    private fun newTask(json: JSONObject, utterance: String, pending: TaskDraft?): TaskCommand {
        val d = details(json, utterance, pending?.nextMissing())
        val title = d.title
        return when {
            title != null -> TaskCommand.AddTask(title, d.deadline, d.durationMin, d.priority, d.recurrence, d.notes)
            pending != null -> TaskCommand.FillTask(d)
            else -> TaskCommand.StartTask(d)
        }
    }

    /**
     * The details of a task in [json], each kept only if the user's words support it. [expecting] is
     * the detail the assistant just asked for: a bare "5" or "45" is a fair answer to that question
     * and to no other.
     */
    private fun details(json: JSONObject, utterance: String, expecting: DraftField?): TaskDraft = TaskDraft(
        title = titleOf(json, expecting)?.takeIf { Grounding.textGrounded(it, utterance) },
        deadline = deadline(json, utterance, bare = expecting == DraftField.DEADLINE),
        durationMin = duration(json, utterance, bare = expecting == DraftField.DURATION),
        priority = priority(json.text("priority"), utterance),
        recurrence = json.text("recurrence")
            ?.takeIf { Grounding.recurrenceGrounded(utterance) }
            ?.let { Recurrence.fromName(it) }
            ?: Recurrence.NONE,
        notes = json.text("notes")?.takeIf { Grounding.textGrounded(it, utterance) },
    )

    /**
     * The task's name. Asked for a name, the model regularly files it under "target" (the key for an
     * *existing* task) or "name", so those count, but only while a name is what was asked for.
     */
    private fun titleOf(json: JSONObject, expecting: DraftField?): String? =
        json.text("title") ?: json.text("name")
            ?: if (expecting == DraftField.TITLE) json.text("target") ?: json.text("new_title") else null

    private fun JSONObject.text(key: String): String? =
        optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun deadline(json: JSONObject, utterance: String, bare: Boolean = false): DeadlineSpec? {
        val dayName = json.text("day")?.lowercase()
        val day = dayName?.let(::dayRef)?.takeIf { Grounding.dayGrounded(dayName, utterance) }
        val time = json.text("time")
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.takeIf { Grounding.timeGrounded(utterance, bare) }
            ?.let { Grounding.assumeAfternoon(it, utterance) }
        return if (day == null && time == null) null else DeadlineSpec.Relative(day, time)
    }

    private fun dayRef(name: String): DayRef? = when (name) {
        "today" -> DayRef.Today
        "tomorrow" -> DayRef.Tomorrow
        else -> DayOfWeek.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(DayRef::Weekday)
    }

    private fun duration(json: JSONObject, utterance: String, bare: Boolean = false): Int? =
        json.optInt("duration_min", 0).takeIf { it > 0 && Grounding.durationGrounded(utterance, bare) }

    private fun priority(name: String?, utterance: String): Priority? =
        Priority.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.takeIf { Grounding.priorityGrounded(utterance) }

    private companion object {
        const val TAG = "LlamaIntentParser"
        const val MAX_TOKENS = 160

        /**
         * Deliberately free of example tasks. An earlier version showed a sample task and the model
         * answered a bare "add" with exactly that; the examples that remain in [draftSection] are
         * values only (a duration, a priority, a day), never a task name.
         */
        val INSTRUCTIONS = """
            You turn one spoken sentence about a to-do list into ONE JSON object. Output the JSON only.

            Keys. Leave out every key the user did not clearly say. Never guess, invent or fill in a default.
            "action": add_task, answer, discard_task, query_tasks, update_task, complete_task, reopen_task, delete_task, replan, undo, snooze, end_call, unsupported
            "title": the task's name, in the user's own words
            "day": today, tomorrow, or monday..sunday
            "time": "HH:mm" 24-hour. A bare hour from 1 to 7 means pm.
            "duration_min": whole minutes (an hour is 60)
            "priority": high, medium or low
            "recurrence": none, daily, weekdays, weekly or monthly
            "notes": extra detail to remember with the task
            "target": exact title of one open task listed below, or "last" for the task just discussed
            "new_title": the new name (update_task)
            "clear_deadline": true or false
            "scope": today or all_open (query_tasks); afternoon or day (replan)

            Rules:
            - add_task means the user wants a NEW task. Include only what they said. "add a task" on its own is {"action":"add_task"}.
            - Never work out dates yourself, only name the day.
            - update_task, complete_task, reopen_task and delete_task need a "target" from the open tasks.
            - Use unsupported when the sentence is not about this to-do list.
        """.trimIndent()
    }
}
