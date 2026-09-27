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

    /**
     * The model is the only thing that understands the user. When it cannot answer, the assistant
     * says so; it must never quietly fall back to pattern matching, which is what made every reply
     * feel templated and hid the fact that the model was not running.
     */
    @Test
    fun withoutAUsableAnswer_theAssistantSaysSo_ratherThanGuessing() {
        assertEquals(
            TaskCommand.Unsupported("what's due today"),
            parse("what's due today", "not json at all")
        )
        assertEquals(TaskCommand.NotReady, parse("add a task", "", available = false))
        assertEquals(
            TaskCommand.NotReady,
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

    // ---- the reminder call -----------------------------------------------------------------

    private val readyForReminder = withDuration.copy(priority = Priority.HIGH)

    @Test
    fun whenTheReminderIsAsked_thePromptSaysSoAndKeepsNumbersOutOfTheDuration() {
        val prompt = LlamaIntentParser(ScriptedEngine("")).buildPrompt("ten minutes", ParseContext(draft = readyForReminder))
        assertTrue(prompt.contains("reminder call: missing"))
        assertTrue(prompt.contains("The assistant just asked the user for the task's reminder call"))
        assertTrue(prompt.contains("never \"duration_min\""))
        assertTrue(prompt.contains("does NOT mean cancelling the task"))
    }

    @Test
    fun aReminderAnswer_isReadAsMinutesBefore() {
        val model = """{"action":"answer","reminder_min":10}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(reminderMin = 10)),
            parse("ten minutes before", model, ParseContext(draft = readyForReminder))
        )
    }

    @Test
    fun noReminder_isAnAnswer_notACancellation() {
        val model = """{"action":"answer","reminder_min":0}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(reminderMin = TaskDraft.NO_REMINDER)),
            parse("none", model, ParseContext(draft = readyForReminder))
        )
    }

    @Test
    fun aNumberFiledUnderDuration_whileTheReminderIsAsked_isStillTheReminder() {
        val model = """{"action":"answer","duration_min":10}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(reminderMin = 10)),
            parse("10 minutes", model, ParseContext(draft = readyForReminder))
        )
    }

    @Test
    fun aReminderNobodySaid_isNotBelieved() {
        val ctx = ParseContext(draft = readyForReminder)
        // "sure" has no number, and "sounds good" has no no-word: neither can have meant a reminder answer.
        assertEquals(TaskCommand.FillTask(TaskDraft()), parse("sure", """{"action":"answer","reminder_min":15}""", ctx))
        assertEquals(TaskCommand.FillTask(TaskDraft()), parse("sounds good", """{"action":"answer","reminder_min":0}""", ctx))
    }

    @Test
    fun anAnswerToTheReminderQuestion_neverChangesTheDurationOrDeadline() {
        val model = """{"action":"answer","reminder_min":5,"duration_min":5,"day":"tomorrow","time":"05:00"}"""
        assertEquals(
            TaskCommand.FillTask(TaskDraft(reminderMin = 5)),
            parse("5 minutes before at 5", model, ParseContext(draft = readyForReminder))
        )
    }

    @Test
    fun aReminderSaidWhileAdding_isKept_andIsNotAlsoTheTasksLength() {
        val model = """{"action":"add_task","title":"gym","day":"tomorrow","time":"18:00","duration_min":15,"reminder_min":15}"""
        assertEquals(
            TaskCommand.AddTask("gym", rel(DayRef.Tomorrow, 18), reminderMin = 15),
            parse("add gym tomorrow at 6 pm remind me 15 minutes before", model)
        )
    }

    @Test
    fun aRealDurationAndAReminderInOneSentence_areBothKept() {
        val model = """{"action":"add_task","title":"gym","duration_min":60,"reminder_min":10}"""
        assertEquals(
            TaskCommand.AddTask("gym", null, 60, reminderMin = 10),
            parse("add gym for 60 minutes and remind me 10 minutes before", model)
        )
    }

    @Test
    fun aReminderTheUserNeverMentioned_isDroppedFromAnAdd() {
        val model = """{"action":"add_task","title":"gym","duration_min":30,"reminder_min":30}"""
        assertEquals(
            TaskCommand.AddTask("gym", null, 30),
            parse("add gym for 30 minutes", model)
        )
    }

    @Test
    fun changingOrRemovingAReminderOnAnExistingTask() {
        val ctx = ParseContext(openTaskTitles = listOf("Gym"))
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("Gym"), TaskPatch(reminderMin = 10)),
            parse("remind me 10 minutes before gym", """{"action":"update_task","target":"Gym","reminder_min":10}""", ctx)
        )
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("Gym"), TaskPatch(clearReminder = true)),
            parse("remove the reminder from gym", """{"action":"update_task","target":"Gym","reminder_min":0}""", ctx)
        )
    }

    @Test
    fun groundingOfReminders() {
        assertTrue(Grounding.reminderMinutesGrounded("10 minutes before", bare = false))
        assertFalse(Grounding.reminderMinutesGrounded("for 30 minutes", bare = false))
        assertTrue(Grounding.reminderMinutesGrounded("10", bare = true))
        assertTrue(Grounding.noReminderGrounded("none", bare = true))
        assertFalse(Grounding.noReminderGrounded("none", bare = false))
        assertTrue(Grounding.noReminderGrounded("no reminder please", bare = false))
        assertTrue(Grounding.noReminderGrounded("remove the reminder", bare = false))
        assertEquals(2, Grounding.durationPhraseCount("30 minutes and 10 minutes before"))
        assertEquals(1, Grounding.durationPhraseCount("remind me an hour before at 6 am"))
    }
}
