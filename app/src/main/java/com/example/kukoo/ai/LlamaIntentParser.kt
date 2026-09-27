package com.example.kukoo.ai

import android.content.Context
import android.util.Log
import com.example.kukoo.domain.ChatKind
import com.example.kukoo.domain.ConflictQuestion
import com.example.kukoo.domain.QuestionKind
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.DraftField
import com.example.kukoo.domain.PlanItemSpec
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.PlanStage
import com.example.kukoo.domain.PlanningState
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
import java.time.MonthDay

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
 * missing, [ensureLoaded] is false and the assistant says it is not ready rather than guessing.
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
 * Maps an utterance to a [TaskCommand] with the local LLM. **The model is the only thing that
 * understands the user.** There is deliberately no pattern-matching fallback: a regex parser
 * standing in for the model answered every turn with the same handful of templates and made the
 * assistant feel mechanical, and worse, it hid the fact that the model was not running at all.
 *
 * When the model cannot answer, the assistant says so ([TaskCommand.NotReady] while it is still
 * loading, [TaskCommand.Unsupported] when it genuinely did not understand) instead of guessing.
 *
 * Every detail the model returns is still checked against the user's own words ([Grounding]), so a
 * small model cannot invent a title, time, length or priority nobody said. A detail that fails the
 * check is simply left out, and the engine asks the user for it.
 */
class LlamaIntentParser(
    private val engine: LlamaEngine,
    private val timeoutMs: Long = 8_000,
) : IntentParser {

    constructor(context: Context) : this(
        NativeLlamaEngine(File(context.applicationContext.filesDir, "models/kukoo-intent.gguf")),
    )

    // One inference at a time; a call that times out may still occupy the native side.
    private val busy = Mutex()

    override suspend fun parse(utterance: String, context: ParseContext): TaskCommand {
        // "yes", "keep both", "no": the answers to an overlap question are a closed set, decided by rules.
        context.conflict?.let { q -> ConflictReplies.read(utterance, q)?.let { return it } }
        if (!engine.ensureLoaded()) {
            Log.w(TAG, "model not loaded; asking the user to wait")
            return TaskCommand.NotReady
        }
        val command = tryLlm(utterance, context)
        if (command == null) {
            Log.w(TAG, "model gave nothing usable for \"$utterance\"")
            return TaskCommand.Unsupported(utterance)
        }
        Log.i(TAG, "LLM understood: $command")
        return command
    }

    private suspend fun tryLlm(utterance: String, context: ParseContext): TaskCommand? {
        if (utterance.isBlank() || !busy.tryLock()) return null
        // A list of tasks takes the model far longer to write out than a one-word answer, so a planning
        // turn (or a long sentence, which may carry the list) gets more time and room.
        val long = context.planning != null || utterance.length > LONG_UTTERANCE
        return try {
            withTimeoutOrNull(if (long) timeoutMs * PLAN_TIME_FACTOR else timeoutMs) {
                withContext(Dispatchers.Default) {
                    if (!engine.ensureLoaded()) return@withContext null
                    val budget = if (long) MAX_TOKENS_PLAN else MAX_TOKENS
                    val raw = engine.complete(buildPrompt(utterance, context), budget, LlamaGrammar.COMMAND_JSON)
                    toCommand(raw, utterance, context)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "LLM parse failed", e)
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
            context.draft?.let { append("\n\n").append(draftSection(it, context.conflict)) }
            context.conflict?.let { append("\n\n").append(conflictSection(it)) }
            context.planning?.let { append("\n\n").append(planningSection(it)) }
            append("\n\nOpen tasks:\n").append(titles)
            // The conversation is quoted inside the system text, not replayed as real assistant
            // turns. The assistant's replies are ordinary English ("Got it: half an hour. How
            // urgent is it?"), so putting them in the assistant role taught the model to answer in
            // English too, and it stopped emitting JSON entirely. Here the assistant role only
            // ever holds the JSON object it is supposed to produce.
            val past = context.history.takeLast(MAX_HISTORY_TURNS)
            if (past.isNotEmpty()) {
                append("\n\nThe conversation so far, for working out what \"it\" and \"that one\" mean:\n")
                past.forEach { append(if (it.fromUser) "User: " else "Assistant: ").append(it.text).append('\n') }
            }
        }
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$utterance<|im_end|>\n<|im_start|>assistant\n"
    }

    /** Tells the model what the assistant just asked, so a short reply is read as an answer. */
    private fun draftSection(draft: TaskDraft, conflict: ConflictQuestion? = null): String {
        fun known(value: Any?) = value?.toString()?.takeIf { it.isNotBlank() } ?: "missing"
        val asked = if (conflict?.forDraft == true) "start time (a different one, because of the problem described below)" else when (draft.nextMissing()) {
            DraftField.TITLE, null -> "title (the task's name; put it under \"title\", never \"target\")"
            DraftField.DEADLINE -> "deadline"
            DraftField.DURATION -> "duration"
            DraftField.PRIORITY -> "priority"
            DraftField.REMINDER -> "reminder call (how many minutes before the deadline to phone them, or none)"
        }
        val reminderRules = if (draft.nextMissing() == DraftField.REMINDER) listOf(
            "- The assistant asked about the reminder call. Put the number of minutes under \"reminder_min\", never \"duration_min\" (that is how long the task takes, which is already known).",
            "- Saying no, none, skip or that they do not need one means {\"action\":\"answer\",\"reminder_min\":0}. It does NOT mean cancelling the task.",
            "Answers look like: \"none\" -> {\"action\":\"answer\",\"reminder_min\":0}; " +
                "\"ten minutes before\" -> {\"action\":\"answer\",\"reminder_min\":10}; " +
                "\"an hour before\" -> {\"action\":\"answer\",\"reminder_min\":60}; \"5\" -> {\"action\":\"answer\",\"reminder_min\":5}",
        ) else emptyList()
        return listOf(
            "A new task is being set up. So far: title: ${known(draft.title)} | " +
                "deadline: ${if (draft.deadline == null) "missing" else "given"} | " +
                "duration: ${if (draft.durationMin == null) "missing" else "given"} | " +
                "priority: ${if (draft.priority == null) "missing" else "given"} | " +
                "reminder call: ${if (draft.reminderMin == null) "missing" else "given"}",
            "The assistant just asked the user for the task's $asked.",
            *reminderRules.toTypedArray(),
            "- If the reply gives any of these details, output {\"action\":\"answer\"} plus only the details they gave.",
            "- A day and a time are separate keys. If they name a day (today, tomorrow, a weekday) you must include \"day\" as well as \"time\", or the task lands on the wrong date.",
            "- If they want to drop the task (cancel, never mind, forget it), output {\"action\":\"discard_task\"}.",
            "- Use add_task only if they clearly start a different new task. If they ask for something else, use that action.",
            "Answers look like: \"an hour and a half\" -> {\"action\":\"answer\",\"duration_min\":90}; " +
                "\"make it urgent\" -> {\"action\":\"answer\",\"priority\":\"high\"}; " +
                "\"by friday evening\" -> {\"action\":\"answer\",\"day\":\"friday\",\"time\":\"18:00\"}"
        ).joinToString("\n")
    }

    /**
     * The assistant has just raised a problem with a time and offered to fix it. Yes / no / keep both are
     * decided by [ConflictReplies] before the model runs, so the model only has to read what is left:
     * a new time, or a new length.
     */
    private fun conflictSection(q: ConflictQuestion): String = when {
        q.forDraft && q.kind == QuestionKind.ODD_HOUR -> listOf(
            "The assistant just asked whether the new task's start time, which is in the middle of the night, is really what they meant.",
            "- If they give a corrected time, output {\"action\":\"answer\"} with \"time\" (and \"day\" if they said one), e.g. \"three pm\" -> {\"action\":\"answer\",\"time\":\"15:00\"}.",
            "- If they want to drop the task, output {\"action\":\"discard_task\"}.",
        ).joinToString("\n")
        q.forDraft -> listOf(
            "The assistant just told the user that the new task overlaps another task, and asked whether to use a suggested time, pick another time, or keep both.",
            "- If they name a different day or time, output {\"action\":\"answer\"} with \"time\" (and \"day\" if they said one). A bare hour is a time here.",
            "- If they give a different length, output {\"action\":\"answer\",\"duration_min\":N}.",
            "- If they want to drop the task, output {\"action\":\"discard_task\"}.",
        ).joinToString("\n")
        else -> listOf(
            "The assistant has just changed the task \"${q.taskTitle}\" and told the user about a problem with its time, and offered to move it.",
            "- If they name a time to move it to, output {\"action\":\"update_task\",\"target\":\"${q.taskTitle}\"} with \"time\" (and \"day\" if they said one). A bare hour is a time here.",
        ).joinToString("\n")
    }

    /** Tells the model where the day-planning conversation stands, so "yes" and "make it shorter" mean something. */
    private fun planningSection(state: PlanningState): String {
        val tasks = if (state.taskTitles.isEmpty()) "none yet" else state.taskTitles.joinToString(" | ")
        val asked = when (state.stage) {
            PlanStage.ASK_TASKS -> "which tasks they want to fit into the day"
            PlanStage.REVIEW -> "whether to add the plan it just proposed, or what to change"
        }
        return listOf(
            "The user is planning ${state.dayLabel}. Tasks in the plan so far: $tasks.",
            "The assistant just asked ${asked}.",
            "- They name tasks: {\"action\":\"plan_tasks\",\"tasks\":[{\"title\":\"<task>\"}]}, one object per task, only the tasks named in this reply.",
            "- They agree (yes, okay, sounds good, go ahead, add them) or say that is everything (that's all, nothing else): {\"action\":\"approve_plan\"}.",
            "- They change one task in the plan: {\"action\":\"plan_change\",\"target\":\"<exact title from the plan>\"} plus \"time\", \"duration_min\" or \"priority\" as they said it, or \"remove\":true to drop it.",
            "- They want the plan to start at a time: {\"action\":\"plan_change\",\"time\":\"HH:mm\"} with no target. Another day: plan_change with \"day\" or \"date\".",
            "- They say no, or want a change without saying which: {\"action\":\"plan_change\"}.",
            "- They want to stop planning (cancel, never mind): {\"action\":\"discard_task\"}.",
            "- Use another action only if they clearly ask for something else."
        ).joinToString("\n")
    }

    /** Returns null when [raw] is not a valid, complete command (caller then falls back). */
    internal fun toCommand(raw: String, utterance: String, context: ParseContext = ParseContext()): TaskCommand? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() ?: return null
        val pending = context.draft
        val expecting = context.expectedField()
        // After the assistant offered to move a task, a bare "10" is the new time.
        val bareTime = context.conflict?.forDraft == false
        val planning = context.planning

        fun ref(): TaskRef? = when (val t = json.text("target")) {
            null -> null
            else -> if (t.equals("last", ignoreCase = true)) TaskRef.Last else TaskRef.ByTitle(t)
        }

        // A reply to small talk often comes back as just {"kind":"thanks"} with the action left
        // out. There is nothing else "kind" could belong to, so it is read as the chat it is.
        val action = json.text("action")?.lowercase()
            ?: json.text("kind")?.let { "chat" }

        return when (action) {
            "add_task" -> newTask(json, utterance, pending, expecting)
            // An answer only means something while a question is open.
            // With no task question open, an "answer" to the plan proposal is the yes it was waiting for.
            "answer" -> pending?.let { TaskCommand.FillTask(details(json, utterance, expecting)) }
                ?: if (planning != null && Grounding.approvalGrounded(utterance)) TaskCommand.PlanApprove else null
            "discard_task" -> if (pending != null || planning != null) TaskCommand.DiscardDraft else null
            "plan_day" -> if (Grounding.planGrounded(utterance)) {
                TaskCommand.PlanDay(dayOf(json, utterance), monthDay(json, utterance), planItems(json, utterance))
            } else null
            "plan_tasks" -> {
                val items = planItems(json, utterance)
                when {
                    planning != null -> TaskCommand.PlanAdd(items)
                    // With no plan open, a bare list of tasks is only a plan if the user said so.
                    items.isNotEmpty() && Grounding.planGrounded(utterance) ->
                        TaskCommand.PlanDay(dayOf(json, utterance), monthDay(json, utterance), items)
                    else -> null
                }
            }
            "plan_change" -> if (planning != null) planChange(json, utterance) else null
            "approve_plan" -> if (planning != null && Grounding.approvalGrounded(utterance)) TaskCommand.PlanApprove else null
            "query_tasks" -> TaskCommand.QueryTasks(
                if (json.text("scope")?.lowercase() == "all_open") QueryScope.ALL_OPEN else QueryScope.TODAY,
            )
            "update_task" -> ref()?.let { r ->
                val clear = json.optBoolean("clear_deadline", false)
                val reminder = reminder(json, utterance, expecting = null)
                val patch = TaskPatch(
                    title = json.text("new_title")?.takeIf { Grounding.textGrounded(it, utterance) },
                    deadline = if (clear) null else deadline(json, utterance, bare = bareTime),
                    clearDeadline = clear,
                    durationMin = durationApartFromReminder(json, utterance, reminder),
                    reminderMin = reminder?.takeIf { it != TaskDraft.NO_REMINDER },
                    clearReminder = reminder == TaskDraft.NO_REMINDER,
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
            "check_conflicts" -> TaskCommand.CheckConflicts
            "find_time" -> TaskCommand.FindTime(duration(json, utterance))
            "snooze" -> TaskCommand.Snooze(duration(json, utterance) ?: 15)
            "end_call" -> TaskCommand.EndCall
            "ask_what_to_change" -> ref()?.let { TaskCommand.AskWhatToChange(it) }
            "chat" -> chatKind(json.text("kind"))?.let { TaskCommand.Chat(it) }
            // Let the rules have a go before giving up on the utterance.
            "unsupported" -> null
            else -> null
        }
    }

    private fun dayOf(json: JSONObject, utterance: String): DayRef? {
        val name = json.text("day")?.lowercase() ?: return null
        return dayRef(name)?.takeIf { Grounding.dayGrounded(name, utterance) }
    }

    /**
     * "October 5th" as month and day only; the model's year is ignored (the engine picks the next such date).
     * Accepts "2026-10-05" and "10-05".
     */
    private fun monthDay(json: JSONObject, utterance: String): MonthDay? {
        val match = Regex("^(?:\\d{4}-)?(\\d{1,2})-(\\d{1,2})$").find(json.text("date") ?: return null) ?: return null
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        if (!Grounding.monthDayGrounded(month, day, utterance)) return null
        return runCatching { MonthDay.of(month, day) }.getOrNull()
    }

    /**
     * The tasks the user listed for a plan. Each title must be in the user's words, a comma the model left
     * inside a title splits it into separate tasks, and a length, priority or time is kept only if the user
     * named at least as many of them as the model attached: a single "an hour" cannot land on every task.
     */
    private fun planItems(json: JSONObject, utterance: String): List<PlanItemSpec> {
        val entries = mutableListOf<JSONObject>()
        val array = json.optJSONArray("tasks")
        if (array != null) {
            for (i in 0 until array.length()) {
                when (val entry = array.opt(i)) {
                    is JSONObject -> entries += entry
                    is String -> entries += JSONObject().put("title", entry)
                }
            }
        } else if (json.text("tasks") != null) {
            json.text("tasks")!!.split(',').forEach { entries += JSONObject().put("title", it) }
        } else {
            json.text("title")?.let { entries += JSONObject().put("title", it) }
        }

        class Item(val title: String, val minutes: Int?, val priority: Priority?, val time: LocalTime?)

        val items = mutableListOf<Item>()
        for (entry in entries) {
            val parts = (entry.text("title") ?: entry.text("name") ?: continue).split(',')
            for (part in parts) {
                val title = part.trim()
                if (!Grounding.textGrounded(title, utterance) || Grounding.isPlanFiller(title)) continue
                if (items.any { it.title.equals(title, ignoreCase = true) }) continue
                // A title the model had to split was several tasks, so whatever it attached is a guess.
                val single = parts.size == 1
                items += Item(
                    title,
                    if (single) duration(entry, utterance) else null,
                    if (single) priority(entry.text("priority"), utterance) else null,
                    if (single) {
                        entry.text("time")?.let(::modelTime)
                            ?.takeIf { Grounding.timeGrounded(utterance, bare = false) }
                            ?.let { Grounding.assumeAfternoon(it, utterance) }
                    } else null
                )
            }
        }
        val keepMinutes = items.count { it.minutes != null } <= Grounding.durationPhraseCount(utterance)
        val keepPriority = items.count { it.priority != null } <= Grounding.priorityWordCount(utterance)
        val keepTime = items.count { it.time != null } <= Grounding.timeMarkerCount(utterance)
        return items.take(MAX_PARSED_TASKS).map {
            PlanItemSpec(
                it.title,
                it.minutes.takeIf { keepMinutes },
                it.priority.takeIf { keepPriority },
                it.time.takeIf { keepTime }
            )
        }
    }

    /** A change to the plan under review; each detail must be in the user's words, like everywhere else. */
    private fun planChange(json: JSONObject, utterance: String): TaskCommand.PlanChange = TaskCommand.PlanChange(
        target = json.text("target")?.takeIf { !it.equals("last", ignoreCase = true) },
        at = json.text("time")?.let(::modelTime)
            ?.takeIf { Grounding.timeGrounded(utterance, bare = false) }
            ?.let { Grounding.assumeAfternoon(it, utterance) },
        durationMin = duration(json, utterance),
        priority = priority(json.text("priority"), utterance),
        remove = json.optBoolean("remove", false) && Grounding.removeGrounded(utterance),
        day = dayOf(json, utterance),
        date = monthDay(json, utterance),
    )

    private fun chatKind(name: String?): ChatKind? =
        ChatKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * "Add …": a named task is an [TaskCommand.AddTask] and an unnamed one a [TaskCommand.StartTask],
     * matching what the rule parser produces. Mid-question, details without a name are the answer.
     */
    private fun newTask(json: JSONObject, utterance: String, pending: TaskDraft?, expecting: DraftField?): TaskCommand {
        val d = details(json, utterance, expecting)
        val title = d.title
        return when {
            // A reminder of NO_REMINDER (0) is "no reminder call"; the engine's draft keeps it distinct from "not asked yet".
            title != null -> TaskCommand.AddTask(
                title, d.deadline, d.durationMin, d.priority, d.recurrence, d.notes, d.reminderMin
            )
            pending != null -> TaskCommand.FillTask(d)
            else -> TaskCommand.StartTask(d)
        }
    }

    /**
     * The details of a task in [json], each kept only if the user's words support it. [expecting] is
     * the detail the assistant just asked for: a bare "5" or "45" is a fair answer to that question
     * and to no other.
     */
    private fun details(json: JSONObject, utterance: String, expecting: DraftField?): TaskDraft {
        val reminder = reminder(json, utterance, expecting)
        // The length and the time are already known while the reminder is being asked for, so nothing
        // in that answer ("10 minutes", "5") may be read as a new length or deadline for the task.
        val answeringReminder = expecting == DraftField.REMINDER
        return TaskDraft(
            title = titleOf(json, expecting)?.takeIf { Grounding.textGrounded(it, utterance) },
            deadline = if (answeringReminder) null else deadline(json, utterance, bare = expecting == DraftField.DEADLINE),
            durationMin = if (answeringReminder) null
            else durationApartFromReminder(json, utterance, reminder, bare = expecting == DraftField.DURATION),
            priority = priority(json.text("priority"), utterance),
            recurrence = json.text("recurrence")
                ?.takeIf { Grounding.recurrenceGrounded(utterance) }
                ?.let { Recurrence.fromName(it) }
                ?: Recurrence.NONE,
            notes = json.text("notes")?.takeIf { Grounding.textGrounded(it, utterance) },
            reminderMin = reminder,
        )
    }

    /**
     * Minutes before the deadline to phone the user, [TaskDraft.NO_REMINDER] for "no reminder call", or null
     * when the user did not say. While the reminder is what was asked ([expecting]) a lone number is an
     * answer, and a model that filed it under "duration_min" (the previous question's key) is forgiven;
     * otherwise the user has to have spoken of a reminder for the number to count.
     */
    private fun reminder(json: JSONObject, utterance: String, expecting: DraftField?): Int? {
        val asked = expecting == DraftField.REMINDER
        val raw = when {
            json.has("reminder_min") -> json.optInt("reminder_min", -1)
            asked && json.has("duration_min") -> json.optInt("duration_min", -1)
            else -> return null
        }
        return when {
            raw == 0 -> TaskDraft.NO_REMINDER.takeIf { Grounding.noReminderGrounded(utterance, bare = asked) }
            raw > 0 -> raw.takeIf { Grounding.reminderMinutesGrounded(utterance, bare = asked) }
            else -> null
        }
    }

    /**
     * "Remind me 15 minutes before" often comes back with 15 in "duration_min" too, because it is the only
     * length in the sentence. When the reminder explains the one length the user gave, the duration is dropped
     * and the assistant asks how long the task takes.
     */
    private fun durationApartFromReminder(json: JSONObject, utterance: String, reminder: Int?, bare: Boolean = false): Int? {
        val minutes = duration(json, utterance, bare)
        return if (minutes != null && minutes == reminder && Grounding.durationPhraseCount(utterance) <= 1) null else minutes
    }

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
            ?.let(::modelTime)
            ?.takeIf { Grounding.timeGrounded(utterance, bare) }
            ?.let { Grounding.assumeAfternoon(it, utterance) }
        return if (day == null && time == null) null else DeadlineSpec.Relative(day, time)
    }

    /**
     * The model writes a 24-hour time, but not always zero-padded: "6:00" as often as "06:00".
     * [LocalTime.parse] rejects the short form, which silently threw away a time the model had
     * read correctly, so the hour is padded before parsing.
     */
    private fun modelTime(raw: String): LocalTime? {
        val text = raw.trim()
        val padded = if (Regex("^\\d:\\d{2}$").matches(text)) "0$text" else text
        return runCatching { LocalTime.parse(padded) }.getOrNull()
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

        /** Room for a list of tasks, each an object of its own. */
        const val MAX_TOKENS_PLAN = 360
        const val PLAN_TIME_FACTOR = 3
        const val LONG_UTTERANCE = 70
        const val MAX_PARSED_TASKS = 20

        /** Enough for "move it to six" to resolve, short enough to keep the prompt fast. */
        const val MAX_HISTORY_TURNS = 6

        /**
         * Deliberately free of example tasks. An earlier version showed a sample task and the model
         * answered a bare "add" with exactly that; the examples that remain in [draftSection] are
         * values only (a duration, a priority, a day), never a task name.
         */
        val INSTRUCTIONS = """
            You turn one spoken sentence about a to-do list into ONE JSON object. Output the JSON only.

            Keys. Leave out every key the user did not clearly say. Never guess, invent or fill in a default.
            "action": add_task, answer, discard_task, query_tasks, update_task, complete_task, reopen_task, delete_task, replan, plan_day, plan_tasks, plan_change, approve_plan, undo, snooze, end_call, ask_what_to_change, check_conflicts, find_time, chat, unsupported
            "title": the task's name, in the user's own words
            "day": today, tomorrow, or monday..sunday
            "date": "YYYY-MM-DD", only when the user names a calendar date such as the fifth of a month
            "tasks": plan_day / plan_tasks only. A list with one object per task the user named: [{"title":"<task>"}]. An object may also carry "duration_min", "priority" and "time" if the user said them for that task.
            "remove": true to take a task out of the plan (plan_change only)
            "time": "HH:mm" 24-hour. A bare hour from 1 to 7 means pm.
            "duration_min": whole minutes (an hour is 60)
            "priority": high, medium or low
            "recurrence": none, daily, weekdays, weekly or monthly
            "notes": extra detail to remember with the task
            "reminder_min": whole minutes BEFORE the deadline to phone the user about the task. 0 means no reminder call.
            "target": exact title of one open task listed below, or "last" for the task just discussed
            "new_title": the new name (update_task)
            "clear_deadline": true or false
            "scope": today or all_open (query_tasks); afternoon or day (replan)
            "kind": greeting, thanks, acknowledge or help (chat only)

            Rules:
            - add_task means the user wants a NEW task. Include only what they said. "add a task" on its own is {"action":"add_task"}.
            - Never work out dates yourself, only name the day. "day" and "time" are separate keys: "tomorrow at 6 pm" is {"day":"tomorrow","time":"18:00"}, and leaving "day" out puts the task on the wrong date.
            - update_task, complete_task, reopen_task and delete_task need a "target" from the open tasks.
            - Ignore polite filler such as "can you", "could you", "please", "I want to" and "I'd like to": the request after it means exactly the same without it.
            - update_task, complete_task, reopen_task, delete_task and ask_what_to_change always need "target": the exact title of one open task listed below. If no listed task fits, use unsupported.
            - Moving a task to a new time is update_task with "target" and "time" (and "day" if they said one). Do not use "new_title" unless they are renaming it.
            - "reminder_min" is only for a call before the deadline ("remind me 10 minutes before", "call me half an hour ahead" -> 30). It is never the length of the task: put that under "duration_min". Only say 0 when they clearly want no reminder.
            - Adding, changing or removing the reminder call of an existing task is update_task with "target" and "reminder_min" (0 removes it).
            - ask_what_to_change: they named a task to change but not what to change about it.
            - check_conflicts: they ask whether any tasks overlap or clash ("any conflicts?", "am I double booked?").
            - find_time: they ask when they are free ("when am I free?", "when can I fit an hour?"). Include "duration_min" only if they named a length.
            - chat: a greeting, a thank-you, a bare acknowledgement ("okay", "yeah"), or asking what you can do. It looks like {"action":"chat","kind":"thanks"} — always include "action".
            - plan_day: the user wants help planning a day ("plan my day", "help me plan tomorrow", "plan my day for friday"). Name the day with "day" or "date". Add "tasks" only if they listed tasks in the same sentence. Never use replan for this: replan only reschedules tasks that are already on the list.
            - Use unsupported only when nothing else fits.
        """.trimIndent()
    }
}
