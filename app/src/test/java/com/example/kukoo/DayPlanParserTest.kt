package com.example.kukoo

import com.example.kukoo.ai.LlamaEngine
import com.example.kukoo.ai.LlamaGrammar
import com.example.kukoo.ai.LlamaIntentParser
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.domain.ChatKind
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.PlanItemSpec
import com.example.kukoo.domain.PlanStage
import com.example.kukoo.domain.PlanningState
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.TaskCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalTime
import java.time.MonthDay

/**
 * The planning conversation as the model would drive it. The model is scripted, so these check what the
 * parser does with an *answer*: what it believes, and above all what it refuses to believe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayPlanParserTest {
    private class ScriptedEngine(var reply: String) : LlamaEngine {
        var lastMaxTokens = 0
        var lastGrammar: String? = null
        override fun ensureLoaded() = true
        override fun complete(prompt: String, maxTokens: Int, grammar: String?): String {
            lastMaxTokens = maxTokens
            lastGrammar = grammar
            return reply
        }
    }

    private fun parse(utterance: String, modelSays: String, context: ParseContext = ParseContext()): TaskCommand =
        runBlocking { LlamaIntentParser(ScriptedEngine(modelSays)).parse(utterance, context) }

    private val asking = ParseContext(planning = PlanningState(PlanStage.ASK_TASKS, "tomorrow", emptyList()))
    private val reviewing = ParseContext(
        planning = PlanningState(PlanStage.REVIEW, "tomorrow", listOf("Gym", "Take doctor appointment", "Prepare GATE exam"))
    )

    // ---- starting -----------------------------------------------------------------------------

    @Test
    fun planMyDay_opensThePlanningConversation() {
        assertEquals(TaskCommand.PlanDay(), parse("plan my day", """{"action":"plan_day"}"""))
    }

    @Test
    fun planMyDayForTomorrow_carriesTheDay() {
        assertEquals(
            TaskCommand.PlanDay(day = DayRef.Tomorrow),
            parse("plan my day for tomorrow", """{"action":"plan_day","day":"tomorrow"}""")
        )
        assertEquals(
            TaskCommand.PlanDay(day = DayRef.Tomorrow),
            parse("plan my day for tmr", """{"action":"plan_day","day":"tomorrow"}""")
        )
    }

    @Test
    fun planMyDayForADate_carriesMonthAndDay_notTheModelsYear() {
        // The model guessed 2024; only the month and day are used, and the engine picks the next such date.
        assertEquals(
            TaskCommand.PlanDay(date = MonthDay.of(10, 5)),
            parse("plan my day for october 5th", """{"action":"plan_day","date":"2024-10-05"}""")
        )
        assertEquals(
            TaskCommand.PlanDay(date = MonthDay.of(10, 5)),
            parse("plan my day for 5th oct", """{"action":"plan_day","date":"2026-10-05"}""")
        )
    }

    @Test
    fun aDateTheUserNeverSaid_isDropped() {
        assertEquals(
            TaskCommand.PlanDay(),
            parse("plan my day", """{"action":"plan_day","date":"2026-10-05"}""")
        )
        // A month with the wrong day number is not what was said either.
        assertEquals(
            TaskCommand.PlanDay(),
            parse("plan my day for october 12", """{"action":"plan_day","date":"2026-10-05"}""")
        )
    }

    @Test
    fun aDayTheUserNeverSaid_isDropped() {
        assertEquals(
            TaskCommand.PlanDay(),
            parse("plan my day", """{"action":"plan_day","day":"tomorrow"}""")
        )
    }

    @Test
    fun planDay_needsTheUserToHaveSaidSomethingAboutPlanning() {
        assertEquals(TaskCommand.Unsupported("hello there"), parse("hello there", """{"action":"plan_day"}"""))
    }

    @Test
    fun tasksListedWithTheRequest_areKept() {
        val model = """{"action":"plan_day","day":"tomorrow","tasks":[{"title":"gym"},{"title":"take doctor appointment"},{"title":"prepare gate exam"}]}"""
        assertEquals(
            TaskCommand.PlanDay(
                DayRef.Tomorrow,
                items = listOf(PlanItemSpec("gym"), PlanItemSpec("take doctor appointment"), PlanItemSpec("prepare gate exam"))
            ),
            parse("plan my day for tomorrow gym take doctor appointment and prepare gate exam", model)
        )
    }

    @Test
    fun replanIsStillReplan() {
        assertEquals(
            TaskCommand.Replan(com.example.kukoo.domain.PlanScope.AFTERNOON),
            parse("replan my afternoon", """{"action":"replan","scope":"afternoon"}""")
        )
    }

    // ---- the task list ------------------------------------------------------------------------

    @Test
    fun theTasksTheUserNames_becomeItems() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"Gym"},{"title":"Take doctor appointment"},{"title":"Prepare GATE exam"}]}"""
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("Gym"), PlanItemSpec("Take doctor appointment"), PlanItemSpec("Prepare GATE exam"))),
            parse("i have gym take doctor appointment prepare gate exam for tomorrow plan optimally", model, asking)
        )
    }

    @Test
    fun aTaskTheUserNeverNamed_isDropped() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"Gym"},{"title":"Meditation"},{"title":"Groceries"}]}"""
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("Gym"))),
            parse("i have gym tomorrow", model, asking)
        )
    }

    @Test
    fun aTaskTitledLikeTheRequestItself_isNotATask() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"my day"},{"title":"plan"}]}"""
        assertEquals(TaskCommand.PlanAdd(emptyList()), parse("plan my day", model, asking))
    }

    @Test
    fun aListJoinedByCommas_isSplitIntoTasks() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym, doctor appointment, gate exam","duration_min":60}]}"""
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("gym"), PlanItemSpec("doctor appointment"), PlanItemSpec("gate exam"))),
            parse("gym, doctor appointment, gate exam for 1 hour", model, asking)
        )
    }

    @Test
    fun aListOfPlainStrings_isAccepted() {
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("gym"), PlanItemSpec("laundry"))),
            parse("gym and laundry", """{"action":"plan_tasks","tasks":["gym","laundry"]}""", asking)
        )
    }

    @Test
    fun aSingleTitleWithoutAList_isOneTask() {
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("cooking"))),
            parse("also add cooking", """{"action":"plan_tasks","title":"cooking"}""", reviewing)
        )
    }

    @Test
    fun detailsTheUserGaveForATask_areKept() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym","duration_min":45,"priority":"high","time":"07:00"},{"title":"laundry"}]}"""
        assertEquals(
            TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("gym", 45, Priority.HIGH, LocalTime.of(7, 0)),
                    PlanItemSpec("laundry")
                )
            ),
            parse("gym for 45 minutes at 7 am high priority and laundry", model, asking)
        )
    }

    @Test
    fun oneDurationTheModelCopiedOntoEveryTask_isDropped() {
        // The user gave no length at all, so none of these 60s can be believed.
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym","duration_min":60},{"title":"laundry","duration_min":60},{"title":"reading","duration_min":60}]}"""
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("gym"), PlanItemSpec("laundry"), PlanItemSpec("reading"))),
            parse("gym laundry and reading", model, asking)
        )
        // One length was said, but the model attached it to three tasks: nothing can be trusted.
        assertEquals(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("gym"), PlanItemSpec("laundry"), PlanItemSpec("reading"))),
            parse("gym for an hour laundry and reading", model, asking)
        )
    }

    @Test
    fun aPriorityOrTimeTheModelInvented_isDropped() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym","priority":"high","time":"09:00"}]}"""
        assertEquals(TaskCommand.PlanAdd(listOf(PlanItemSpec("gym"))), parse("i have gym", model, asking))
    }

    @Test
    fun aListWithNoPlanOpenAndNothingSaidAboutPlanning_isNotAPlan() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym"}]}"""
        assertEquals(TaskCommand.Unsupported("i went to the gym"), parse("i went to the gym", model))
    }

    @Test
    fun aListWithNoPlanOpen_butAskedToPlan_startsOne() {
        val model = """{"action":"plan_tasks","tasks":[{"title":"gym"}]}"""
        assertEquals(
            TaskCommand.PlanDay(items = listOf(PlanItemSpec("gym"))),
            parse("plan gym for me", model)
        )
    }

    // ---- approving ----------------------------------------------------------------------------

    @Test
    fun yes_approvesTheProposal() {
        listOf("yes", "yeah go ahead", "sounds good", "add them", "that's all", "okay").forEach {
            assertEquals(it, TaskCommand.PlanApprove, parse(it, """{"action":"approve_plan"}""", reviewing))
        }
    }

    @Test
    fun aYesThatAlsoAsksForAChange_isNotAnApproval() {
        // "yes but ..." must never write the plan: the change has to be made first.
        assertEquals(
            TaskCommand.Unsupported("yes but make the gym shorter"),
            parse("yes but make the gym shorter", """{"action":"approve_plan"}""", reviewing)
        )
    }

    @Test
    fun anApprovalThatWasNeverSaid_isNotBelieved() {
        assertEquals(
            TaskCommand.Unsupported("make the gym thirty minutes"),
            parse("make the gym thirty minutes", """{"action":"approve_plan"}""", reviewing)
        )
    }

    @Test
    fun aYesTheModelCallsAnAnswer_isStillAnApproval_butOnlyWhileAPlanIsOpen() {
        assertEquals(TaskCommand.PlanApprove, parse("yes", """{"action":"answer"}""", reviewing))
        assertEquals(TaskCommand.Unsupported("yes"), parse("yes", """{"action":"answer"}"""))
    }

    @Test
    fun approvalMeansNothingWithNoPlanOpen() {
        assertEquals(TaskCommand.Unsupported("yes"), parse("yes", """{"action":"approve_plan"}"""))
    }

    @Test
    fun anAcknowledgementModelAnswer_reachesTheEngineAsChat_whichReadsItAsAYes() {
        assertEquals(
            TaskCommand.Chat(ChatKind.ACKNOWLEDGE),
            parse("yeah", """{"action":"chat","kind":"acknowledge"}""", reviewing)
        )
    }

    // ---- changing -----------------------------------------------------------------------------

    @Test
    fun changingATasksLength() {
        assertEquals(
            TaskCommand.PlanChange(target = "Gym", durationMin = 30),
            parse("make the gym 30 minutes", """{"action":"plan_change","target":"Gym","duration_min":30}""", reviewing)
        )
    }

    @Test
    fun movingATask() {
        assertEquals(
            TaskCommand.PlanChange(target = "Gym", at = LocalTime.of(18, 0)),
            parse("move gym to 6 pm", """{"action":"plan_change","target":"Gym","time":"18:00"}""", reviewing)
        )
    }

    @Test
    fun aBareHourInAPlanChange_meansAfternoon_likeEverywhereElse() {
        assertEquals(
            TaskCommand.PlanChange(target = "Gym", at = LocalTime.of(18, 0)),
            parse("gym at 6", """{"action":"plan_change","target":"Gym","time":"06:00"}""", reviewing)
        )
    }

    @Test
    fun changingAPriority() {
        assertEquals(
            TaskCommand.PlanChange(target = "Prepare GATE exam", priority = Priority.HIGH),
            parse("make the exam high priority", """{"action":"plan_change","target":"Prepare GATE exam","priority":"high"}""", reviewing)
        )
    }

    @Test
    fun removingATask_needsRemovingToHaveBeenSaid() {
        assertEquals(
            TaskCommand.PlanChange(target = "Gym", remove = true),
            parse("drop the gym", """{"action":"plan_change","target":"Gym","remove":true}""", reviewing)
        )
        assertEquals(
            TaskCommand.PlanChange(target = "Gym"),
            parse("what about the gym", """{"action":"plan_change","target":"Gym","remove":true}""", reviewing)
        )
    }

    @Test
    fun aLaterStart_hasNoTarget() {
        assertEquals(
            TaskCommand.PlanChange(at = LocalTime.of(10, 0)),
            parse("start at 10 am", """{"action":"plan_change","time":"10:00"}""", reviewing)
        )
    }

    @Test
    fun anotherDay() {
        assertEquals(
            TaskCommand.PlanChange(day = DayRef.Tomorrow),
            parse("do it tomorrow instead", """{"action":"plan_change","day":"tomorrow"}""", reviewing)
        )
    }

    @Test
    fun aBareNo_isAnEmptyChange() {
        assertEquals(TaskCommand.PlanChange(), parse("no", """{"action":"plan_change"}""", reviewing))
    }

    @Test
    fun aPlanChangeWithNoPlanOpen_isNotUnderstood() {
        assertEquals(
            TaskCommand.Unsupported("make the gym 30 minutes"),
            parse("make the gym 30 minutes", """{"action":"plan_change","target":"Gym","duration_min":30}""")
        )
    }

    @Test
    fun cancelling_dropsThePlan() {
        assertEquals(TaskCommand.DiscardDraft, parse("never mind", """{"action":"discard_task"}""", reviewing))
    }

    // ---- what the model is told ---------------------------------------------------------------

    private fun prompt(context: ParseContext) =
        LlamaIntentParser(ScriptedEngine("")).buildPrompt("yes", context)

    @Test
    fun thePlanningSection_appearsOnlyWhilePlanning() {
        assertFalse(prompt(ParseContext()).contains("is planning"))
        val p = prompt(reviewing)
        assertTrue(p.contains("The user is planning tomorrow. Tasks in the plan so far: Gym | Take doctor appointment | Prepare GATE exam."))
        assertTrue(p.contains("whether to add the plan it just proposed"))
        assertTrue(p.contains("approve_plan"))
        assertTrue(prompt(asking).contains("Tasks in the plan so far: none yet."))
        assertTrue(prompt(asking).contains("which tasks they want to fit into the day"))
    }

    @Test
    fun thePromptTeachesPlanDay_andKeepsReplanApart() {
        val p = prompt(ParseContext())
        assertTrue(p.contains("plan_day"))
        assertTrue(p.contains("Never use replan for this"))
    }

    @Test
    fun aPlanningTurn_getsMoreTimeAndRoomThanAOneWordAnswer() {
        val short = ScriptedEngine("""{"action":"plan_day"}""")
        runBlocking { LlamaIntentParser(short).parse("plan my day", ParseContext()) }
        val planning = ScriptedEngine("""{"action":"plan_tasks","tasks":[{"title":"gym"}]}""")
        runBlocking { LlamaIntentParser(planning).parse("gym", asking) }
        assertTrue("${planning.lastMaxTokens} vs ${short.lastMaxTokens}", planning.lastMaxTokens > short.lastMaxTokens)
    }

    @Test
    fun theGrammar_knowsThePlanningActions() {
        val g = LlamaGrammar.COMMAND_JSON
        listOf("plan_day", "plan_tasks", "plan_change", "approve_plan").forEach {
            assertTrue("grammar lacks $it", g.contains(it))
        }
        listOf("tasks", "taskobj", "remove", "date").forEach {
            assertTrue("grammar lacks a rule for $it", Regex("^$it\\s*::=", RegexOption.MULTILINE).containsMatchIn(g))
        }
    }
}
