package com.example.kukoo

import com.example.kukoo.ai.Grounding
import com.example.kukoo.ai.LlamaEngine
import com.example.kukoo.ai.LlamaIntentParser
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * The model is replaced by a scripted [LlamaEngine], so these check what the parser does with a
 * given model *answer* — above all that an answer the user's words don't support is not believed.
 *
 * Robolectric, because the parser reads the model's JSON with `org.json` and logs with
 * `android.util.Log`, which are stubs that throw in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LlamaIntentParserTest {
    private class ScriptedEngine(var reply: String, val available: Boolean = true) : LlamaEngine {
        override fun ensureLoaded() = available
        override fun complete(prompt: String, maxTokens: Int, grammar: String?) = reply
    }

    private fun parse(
        utterance: String,
        modelSays: String,
        context: ParseContext = ParseContext(),
        available: Boolean = true
    ): TaskCommand = runBlocking {
        LlamaIntentParser(ScriptedEngine(modelSays, available)).parse(utterance, context)
    }

    private fun rel(day: DayRef? = null, h: Int? = null, m: Int = 0) =
        DeadlineSpec.Relative(day, h?.let { LocalTime.of(it, m) })

    private val named = TaskDraft(title = "Call mom")
    private val withDeadline = named.copy(deadline = rel(DayRef.Tomorrow, 18))
    private val withDuration = withDeadline.copy(durationMin = 30)

    // ---- the prompt ------------------------------------------------------------------------

    @Test
    fun thePromptCarriesNoExampleTasks() {
        val prompt = LlamaIntentParser(ScriptedEngine("")).buildPrompt("add", ParseContext(listOf("Follow-up")))
        // An earlier prompt showed a sample task, and the model answered a bare "add" with it.
        listOf("gym", "read book", "12pm", "12:00", "everyday", "call mom", "milk").forEach {
            assertFalse("prompt still mentions '$it'", prompt.contains(it, ignoreCase = true))
        }
        assertTrue(prompt.contains("- Follow-up"))
        assertFalse(prompt.contains("is being set up"))
    }

    @Test
    fun midDialog_thePromptSaysWhatWasJustAsked() {
        val prompt = LlamaIntentParser(ScriptedEngine("")).buildPrompt("an hour", ParseContext(draft = withDeadline))
        assertTrue(prompt.contains("A new task is being set up."))
        assertTrue(prompt.contains("title: Call mom | deadline: given | duration: missing | priority: missing"))
        assertTrue(prompt.contains("The assistant just asked the user for the task's duration."))
        assertTrue(prompt.contains("discard_task"))
    }

    // ---- an answer the user's words do not support is not believed --------------------------

    @Test
    fun aBareAdd_cannotBecomeATaskTheUserNeverNamed() {
        // Exactly what the old prompt provoked: a full task out of one word.
        val model = """{"action":"add_task","title":"gym","day":"tomorrow","time":"17:00",
            "duration_min":30,"priority":"high","recurrence":"daily"}"""
        assertEquals(TaskCommand.StartTask(TaskDraft()), parse("add", model))
    }

    @Test
    fun anUnnamedTask_isNotCalledTask() {
        assertEquals(
            TaskCommand.StartTask(TaskDraft()),
            parse("add a task", """{"action":"add_task","title":"task"}""")
        )
    }

    @Test
    fun detailsTheUserDidSay_areKept() {
        val model = """{"action":"add_task","title":"call mom","day":"tomorrow","time":"18:00",
            "duration_min":30,"priority":"high"}"""
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow, 18), 30, Priority.HIGH),
            parse("add call mom tomorrow at 6 pm for 30 minutes high priority", model)
        )
    }

    @Test
    fun aTimeWithoutAnyTimeWords_isDropped_butTheDayIsKept() {
        val model = """{"action":"add_task","title":"call mom","day":"tomorrow","time":"09:00"}"""
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow)),
            parse("add call mom tomorrow", model)
        )
    }

    // ---- answers to the assistant's question ------------------------------------------------

    @Test
    fun anAnswer_fillsTheDraft() {
        assertEquals(
            TaskCommand.FillTask(TaskDraft(durationMin = 90)),
            parse("an hour and a half", """{"action":"answer","duration_min":90}""", ParseContext(draft = withDeadline))
        )
    }

    @Test
    fun anAnswer_withNothingPending_isNotBelieved() {
        // No question is open, so "answer" cannot be right; the rule parser gets the sentence instead.
        assertTrue(
            parse("an hour and a half", """{"action":"answer","duration_min":90}""") is TaskCommand.Unsupported
        )
    }

    @Test
    fun anInventedAnswer_isDropped_soTheQuestionIsAskedAgain() {
        // "yes" says nothing about how long it takes, whatever number the model produced.
        assertEquals(
            TaskCommand.FillTask(TaskDraft()),
            parse("yes", """{"action":"answer","duration_min":30}""", ParseContext(draft = withDeadline))
        )
    }

    @Test
    fun aBareNumber_isOnlyTheAnswerToTheQuestionThatWasAsked() {
        val duration = """{"action":"answer","duration_min":45}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(durationMin = 45)),
            parse("45", duration, ParseContext(draft = withDeadline))
        )
        // The same "45" while asking for a priority means nothing.
        assertEquals(
            TaskCommand.FillTask(TaskDraft()),
            parse("45", duration, ParseContext(draft = withDuration))
        )

        val time = """{"action":"answer","time":"18:00"}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(deadline = rel(h = 18))),
            parse("6", time, ParseContext(draft = named))
        )
        assertEquals(
            TaskCommand.FillTask(TaskDraft()),
            parse("6", time, ParseContext(draft = withDeadline))
        )
    }

    @Test
    fun aNameAnswer_isKeptInTheUsersWords() {
        assertEquals(
            TaskCommand.FillTask(TaskDraft(title = "Call mom")),
            parse("call my mom", """{"action":"answer","title":"Call mom"}""", ParseContext(draft = TaskDraft()))
        )
    }

    @Test
    fun aNameFiledUnderTarget_isStillTheName_whenANameWasAsked() {
        // What the model really answered on the phone: {"action":"answer","target":"call mom"}.
        assertEquals(
            TaskCommand.FillTask(TaskDraft(title = "call mom")),
            parse("call mom", """{"action":"answer","target":"call mom"}""", ParseContext(draft = TaskDraft()))
        )
        assertEquals(
            TaskCommand.FillTask(TaskDraft(title = "call mom")),
            parse("call mom", """{"action":"answer","name":"call mom"}""", ParseContext(draft = TaskDraft()))
        )
    }

    @Test
    fun target_isNotAName_whenSomethingElseWasAsked() {
        // Asked for a duration, a "target" is not a task name, so it must not rename the draft.
        assertEquals(
            TaskCommand.FillTask(TaskDraft()),
            parse("call mom", """{"action":"answer","target":"call mom"}""", ParseContext(draft = withDeadline))
        )
    }

    @Test
    fun aBareHour_isAnAfternoonHour_evenWhenTheModelSaidMorning() {
        val model = """{"action":"add_task","title":"call mom","day":"tomorrow","time":"06:00"}"""
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow, 18)),
            parse("add call mom tomorrow at 6", model)
        )
        assertEquals(
            TaskCommand.FillTask(TaskDraft(deadline = rel(DayRef.Tomorrow, 18))),
            parse("tomorrow at 6", """{"action":"answer","day":"tomorrow","time":"06:00"}""", ParseContext(draft = named))
        )
    }

    @Test
    fun aTimeTheUserQualified_isLeftAlone() {
        val model = """{"action":"add_task","title":"call mom","day":"tomorrow","time":"06:00"}"""
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow, 6)),
            parse("add call mom tomorrow at 6 am", model)
        )
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow, 6)),
            parse("add call mom tomorrow morning at 6", model)
        )
        // Hours from 8 up are already unambiguous.
        assertEquals(
            TaskCommand.AddTask("call mom", rel(DayRef.Tomorrow, 9)),
            parse("add call mom tomorrow at 9", """{"action":"add_task","title":"call mom","day":"tomorrow","time":"09:00"}""")
        )
    }

    @Test
    fun detailsWithoutAName_midDialog_areTheAnswer_notANewTask() {
        // Mid-question the model may say add_task for "30 minutes"; that must not restart the draft.
        assertEquals(
            TaskCommand.FillTask(TaskDraft(durationMin = 30)),
            parse("30 minutes", """{"action":"add_task","duration_min":30}""", ParseContext(draft = withDeadline))
        )
    }

    @Test
    fun discard_onlyCountsWhileATaskIsBeingSetUp() {
        val model = """{"action":"discard_task"}"""
        assertEquals(TaskCommand.DiscardDraft, parse("never mind", model, ParseContext(draft = named)))
        assertTrue(parse("never mind", model) is TaskCommand.Unsupported)
    }

    // ---- everything else still works ---------------------------------------------------------

    @Test
    fun edits_areGroundedToo() {
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("Follow-up"), TaskPatch(deadline = rel(h = 18))),
            parse("move follow-up to 6 pm", """{"action":"update_task","target":"Follow-up","time":"18:00"}""")
        )
    }

    @Test
    fun queriesPassThrough() {
        assertEquals(
            TaskCommand.QueryTasks(QueryScope.TODAY),
            parse("what's due today", """{"action":"query_tasks","scope":"today"}""")
        )
    }

    @Test
    fun aBrokenAnswer_orNoModel_fallsBackToTheRuleParser() {
        assertEquals(TaskCommand.QueryTasks(QueryScope.TODAY), parse("what's due today", "not json at all"))
        assertEquals(TaskCommand.StartTask(), parse("add a task", "", available = false))
        // ...which also reads a reply in context, so the dialog still works without the model.
        assertEquals(
            TaskCommand.FillTask(TaskDraft(priority = Priority.HIGH)),
            parse("high", "", ParseContext(draft = withDuration), available = false)
        )
    }

    // ---- the checks themselves ------------------------------------------------------------

    @Test
    fun groundingOfFreeText() {
        assertTrue(Grounding.textGrounded("call mom", "please call my mom"))
        assertTrue(Grounding.textGrounded("finish report", "finishing the report"))
        assertFalse(Grounding.textGrounded("gym", "add"))
        assertFalse(Grounding.textGrounded("task", "add a task"))
        assertFalse(Grounding.textGrounded("", "anything"))
        assertTrue(Grounding.isFillerTitle("a new task"))
        assertFalse(Grounding.isFillerTitle("go to gym"))
    }

    @Test
    fun groundingOfDays() {
        assertTrue(Grounding.dayGrounded("tomorrow", "call mom tmrw"))
        assertTrue(Grounding.dayGrounded("friday", "on fri"))
        assertTrue(Grounding.dayGrounded("today", "tonight at 8"))
        assertFalse(Grounding.dayGrounded("monday", "tomorrow"))
        assertFalse(Grounding.dayGrounded("tomorrow", "add"))
    }

    @Test
    fun groundingOfTimesDurationsAndPriorities() {
        assertTrue(Grounding.timeGrounded("at 5", bare = false))
        assertTrue(Grounding.timeGrounded("tomorrow morning", bare = false))
        assertFalse(Grounding.timeGrounded("30 minutes", bare = false))
        assertTrue(Grounding.timeGrounded("5", bare = true))
        assertFalse(Grounding.timeGrounded("5", bare = false))

        assertTrue(Grounding.durationGrounded("an hour", bare = false))
        assertTrue(Grounding.durationGrounded("20 min", bare = false))
        assertFalse(Grounding.durationGrounded("45", bare = false))
        assertTrue(Grounding.durationGrounded("45", bare = true))
        assertFalse(Grounding.durationGrounded("high", bare = true))

        assertTrue(Grounding.priorityGrounded("make it urgent"))
        assertTrue(Grounding.priorityGrounded("not that important"))
        assertFalse(Grounding.priorityGrounded("yes"))

        assertTrue(Grounding.recurrenceGrounded("every day"))
        assertFalse(Grounding.recurrenceGrounded("tomorrow"))
    }
}
