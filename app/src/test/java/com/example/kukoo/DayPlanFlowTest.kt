package com.example.kukoo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.kukoo.ai.IntentParser
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.PlanItemSpec
import com.example.kukoo.domain.PlanStage
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.ui.KukooViewModel
import com.example.kukoo.ui.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Plan my day" end to end: real ViewModel, real SQLite, real engine, with the language model replaced by a
 * script of what it would answer. Proves the whole conversation hangs together, including that the
 * ViewModel tells the parser where the planning conversation stands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KukooApp::class)
class DayPlanFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<KukooApp>()
    private val clock = clockAt(15)

    /** Answers by utterance, and remembers what context each turn was parsed with. */
    private class ScriptedParser(val script: Map<String, TaskCommand>) : IntentParser {
        val contexts = mutableListOf<ParseContext>()
        override suspend fun parse(utterance: String, context: ParseContext): TaskCommand {
            contexts += context
            return script[utterance] ?: TaskCommand.Unsupported(utterance)
        }
    }

    private val plan = ScriptedParser(
        mapOf(
            "plan my day for tomorrow" to TaskCommand.PlanDay(DayRef.Tomorrow),
            "gym, take doctor appointment, prepare gate exam" to TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("Gym"),
                    PlanItemSpec("Take doctor appointment"),
                    PlanItemSpec("Prepare GATE exam"),
                )
            ),
            "make the gym 30 minutes" to TaskCommand.PlanChange(target = "Gym", durationMin = 30),
            "yes" to TaskCommand.PlanApprove,
            "undo" to TaskCommand.Undo,
        )
    )

    private fun newVm(): KukooViewModel {
        app.container = AppContainer(app, clock, plan)
        val vm = KukooViewModel(app)
        await("seed data loaded") { vm.state.value.loaded }
        // A clean list, so the plan is only the tasks named in the conversation.
        SqliteTaskStore(app).deleteAll()
        return vm
    }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    private fun KukooViewModel.say(text: String): String {
        val before = state.value.session.turns.size
        submitText(text)
        await("reply to '$text'") { state.value.session.turns.size >= before + 2 && state.value.session.phase == Phase.IDLE }
        return state.value.session.turns.last().text
    }

    private fun stored() = SqliteTaskStore(app).all()

    @Test
    fun theWholeConversation_fromPlanMyDayToTasksOnTheList() {
        val vm = newVm()
        vm.startSession()
        await("briefing") { vm.state.value.session.turns.isNotEmpty() && vm.state.value.session.phase == Phase.IDLE }

        // 1. The assistant asks what is on their mind.
        val ask = vm.say("plan my day for tomorrow")
        assertTrue(ask, ask.startsWith("Sure, let's plan tomorrow. What's on your mind?"))
        assertTrue(stored().isEmpty())

        // 2. They list tasks; a plan comes back, and nothing is written yet.
        val proposal = vm.say("gym, take doctor appointment, prepare gate exam")
        assertTrue(proposal, proposal.startsWith("Got it: Gym, Take doctor appointment and Prepare GATE exam. Here's your plan for tomorrow."))
        assertTrue(proposal, proposal.endsWith("Say yes to add them, or tell me what to change."))
        assertTrue(stored().isEmpty())

        // 3. They ask for a change; a new plan comes back, still nothing written.
        val changed = vm.say("make the gym 30 minutes")
        assertTrue(changed, changed.startsWith("Gym now takes 30 minutes. Here's your plan for tomorrow."))
        assertTrue(stored().isEmpty())

        // 4. They approve: every task lands on the list with its deadline, length, priority and reminder.
        val done = vm.say("yes")
        assertTrue(done, done.startsWith("Done. I added Take doctor appointment, Prepare GATE exam and Gym for tomorrow"))
        val saved = stored().associateBy { it.title }
        assertEquals(3, saved.size)
        assertEquals(30, saved.getValue("Gym").durationMin)
        assertEquals(Priority.HIGH, saved.getValue("Take doctor appointment").priority)
        assertEquals(tomorrow(8), saved.getValue("Take doctor appointment").deadline)
        assertEquals(tomorrow(7, 50), saved.getValue("Take doctor appointment").reminderAt)
        assertTrue(saved.values.all { it.deadline != null && it.reminderMin != null })
        assertTrue("the tasks appear in the app state too", vm.state.value.tasks.size == 3)
        assertTrue("undo is offered after the plan is added", vm.state.value.canUndo)

        // 5. And they can take it all back.
        vm.say("undo")
        assertTrue(stored().isEmpty())
    }

    @Test
    fun theParser_isToldWhereThePlanningConversationStands() {
        val vm = newVm()
        vm.startSession()
        await("briefing") { vm.state.value.session.turns.isNotEmpty() && vm.state.value.session.phase == Phase.IDLE }

        vm.say("plan my day for tomorrow")
        assertNull("no plan open before the request", plan.contexts.last().planning)

        vm.say("gym, take doctor appointment, prepare gate exam")
        val asking = plan.contexts.last().planning
        assertNotNull(asking)
        assertEquals(PlanStage.ASK_TASKS, asking!!.stage)
        assertEquals("tomorrow", asking.dayLabel)

        vm.say("make the gym 30 minutes")
        val reviewing = plan.contexts.last().planning
        assertEquals(PlanStage.REVIEW, reviewing!!.stage)
        assertEquals(listOf("Take doctor appointment", "Prepare GATE exam", "Gym"), reviewing.taskTitles)

        vm.say("yes")
        vm.say("yes") // nothing is open any more
        assertNull(plan.contexts.last().planning)
    }

    @Test
    fun hangingUp_midPlan_leavesNothingBehind() {
        val vm = newVm()
        vm.startSession()
        await("briefing") { vm.state.value.session.turns.isNotEmpty() && vm.state.value.session.phase == Phase.IDLE }
        vm.say("plan my day for tomorrow")
        vm.say("gym, take doctor appointment, prepare gate exam")
        vm.endCall()
        assertTrue(stored().isEmpty())

        // A new call starts clean: the old plan is not waiting there.
        vm.startSession()
        await("briefing") { vm.state.value.session.turns.isNotEmpty() && vm.state.value.session.phase == Phase.IDLE }
        vm.say("yes")
        assertNull(plan.contexts.last().planning)
        assertFalse(vm.state.value.session.turns.last().text.startsWith("Done. I added"))
    }
}
