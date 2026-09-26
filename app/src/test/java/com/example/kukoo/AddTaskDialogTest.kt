package com.example.kukoo

import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.EngineResult
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft
import com.example.kukoo.domain.TaskEngine
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Adding a task by voice needs a name, a deadline, a duration and a priority. Until all four are
 * known the assistant asks for the next one and nothing is written to the store.
 */
class AddTaskDialogTest {
    private class Rig(val store: FakeStore, val engine: TaskEngine)

    /** 3 PM on Friday 2026-09-25, no tasks unless given. */
    private fun rig(tasks: List<Task> = emptyList()): Rig {
        val store = FakeStore(tasks)
        return Rig(store, TaskEngine(store, LocalReplanner(), clockAt(15)))
    }

    private fun Rig.say(command: TaskCommand): EngineResult = runBlocking { engine.executeSpoken(command) }
    private fun Rig.fill(draft: TaskDraft) = say(TaskCommand.FillTask(draft))

    private fun tomorrowAt(hour: Int) = DeadlineSpec.Relative(DayRef.Tomorrow, LocalTime.of(hour, 0))

    // ---- the happy path ------------------------------------------------------------------

    @Test
    fun aBareAdd_asksForTheName_andAddsNothing() {
        val r = rig()
        val res = r.say(TaskCommand.StartTask())
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertEquals("What should I call the task?", res.spoken)
        assertTrue(r.store.all().isEmpty())
        assertNotNull(r.engine.pendingDraft)
    }

    @Test
    fun theTaskIsOnlyAddedOnceAllFourDetailsAreKnown() {
        val r = rig()
        r.say(TaskCommand.StartTask())

        val afterName = r.fill(TaskDraft(title = "call mom"))
        assertEquals("Got it: Call mom. When is Call mom due?", afterName.spoken)
        assertTrue(r.store.all().isEmpty())

        val afterWhen = r.fill(TaskDraft(deadline = tomorrowAt(18)))
        assertEquals("Got it: due tomorrow at 6 PM. How long will Call mom take?", afterWhen.spoken)
        assertTrue(r.store.all().isEmpty())

        val afterLength = r.fill(TaskDraft(durationMin = 30))
        assertEquals("Got it: 30 minutes. Is Call mom high, medium or low priority?", afterLength.spoken)
        assertTrue(r.store.all().isEmpty())

        val done = r.fill(TaskDraft(priority = Priority.HIGH))
        assertEquals(Outcome.OK, done.outcome)
        assertEquals("Added Call mom, due tomorrow at 6 PM, 30 minutes. High priority.", done.spoken)

        val saved = r.store.all().single()
        assertEquals("Call mom", saved.title)
        assertEquals(tomorrow(18), saved.deadline)
        assertEquals(30, saved.durationMin)
        assertEquals(Priority.HIGH, saved.priority)
        assertNull(r.engine.pendingDraft)
        assertEquals(listOf(saved.id), done.taskIds)
    }

    @Test
    fun aNamedTaskWithSomeDetails_asksOnlyForWhatIsMissing() {
        val r = rig()
        val res = r.say(TaskCommand.AddTask("finish the report", tomorrowAt(17), 45))
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertEquals(
            "Got it: Finish the report, due tomorrow at 5 PM, 45 minutes. " +
                "Is Finish the report high, medium or low priority?",
            res.spoken
        )
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun oneReplyCanAnswerSeveralQuestionsAtOnce() {
        val r = rig()
        r.say(TaskCommand.AddTask("send invoice"))
        val res = r.fill(TaskDraft(deadline = tomorrowAt(10), durationMin = 20, priority = Priority.LOW))
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.endsWith("Low priority."))
        val saved = r.store.all().single()
        assertEquals(Priority.LOW, saved.priority)
        assertEquals(20, saved.durationMin)
        assertEquals(tomorrow(10), saved.deadline)
    }

    @Test
    fun aNamelessDraftKeepsItsDetails_whenTheNameArrivesAsANewTask() {
        val r = rig()
        val first = r.say(TaskCommand.StartTask(TaskDraft(deadline = tomorrowAt(17))))
        assertEquals("Got it: due tomorrow at 5 PM. What should I call the task?", first.spoken)

        // A parser that hears the name as "add call mom" must not throw the deadline away.
        val res = r.say(TaskCommand.AddTask("call mom"))
        assertEquals("Got it: Call mom. How long will Call mom take?", res.spoken)
        assertEquals(tomorrowAt(17), r.engine.pendingDraft?.deadline)
    }

    @Test
    fun aRepeatIsKeptButNeverAskedFor() {
        val r = rig()
        val res = r.say(TaskCommand.AddTask("water plants", tomorrowAt(9), 15, Priority.LOW, Recurrence.DAILY))
        assertEquals(Outcome.OK, res.outcome)
        assertEquals(Recurrence.DAILY, r.store.all().single().recurrence)
    }

    // ---- the rest of the app keeps its behaviour -----------------------------------------

    @Test
    fun theFormPath_stillAddsImmediatelyWithDefaults() {
        val r = rig()
        val res = runBlocking { r.engine.execute(TaskCommand.AddTask("buy milk")) }
        assertEquals(Outcome.OK, res.outcome)
        val saved = r.store.all().single()
        assertEquals(Task.DEFAULT_DURATION_MIN, saved.durationMin)
        assertEquals(Priority.MEDIUM, saved.priority)
        assertNull(saved.deadline)
    }

    // ---- bad or missing answers ----------------------------------------------------------

    @Test
    fun aDeadlineInThePast_isRefusedAndAskedAgain() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.fill(TaskDraft(deadline = DeadlineSpec.Exact(today(9))))
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("has already passed."))
        assertTrue(res.spoken, res.spoken.endsWith("When is Call mom due?"))
        assertNull(r.engine.pendingDraft?.deadline)
    }

    @Test
    fun aDurationOutOfRange_isRefusedAndAskedAgain() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom", tomorrowAt(18)))
        val res = r.fill(TaskDraft(durationMin = 2))
        assertEquals(
            "A task should take between 5 minutes and 8 hours. How long will Call mom take?",
            res.spoken
        )
        assertNull(r.engine.pendingDraft?.durationMin)
    }

    @Test
    fun aReplyThatSaysNothingUseful_repeatsTheQuestion() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom", tomorrowAt(18)))
        val empty = r.fill(TaskDraft())
        assertEquals("Sorry, I didn't catch that. How long will Call mom take?", empty.spoken)
        val unknown = r.say(TaskCommand.Unsupported("blah blah"))
        assertEquals("Sorry, I didn't catch that. How long will Call mom take?", unknown.spoken)
        assertTrue(r.store.all().isEmpty())
    }

    // ---- leaving the dialog --------------------------------------------------------------

    @Test
    fun discarding_dropsTheDraft_andSaysSo() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.say(TaskCommand.DiscardDraft)
        assertEquals("Okay, I won't add Call mom.", res.spoken)
        assertNull(r.engine.pendingDraft)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun discardingWithNothingPending_isRefused() {
        val res = rig().say(TaskCommand.DiscardDraft)
        assertEquals(Outcome.REJECTED, res.outcome)
        assertEquals("There is no new task to cancel.", res.spoken)
    }

    @Test
    fun hangingUpMidQuestion_saysTheTaskWasNotAdded() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.say(TaskCommand.EndCall)
        assertEquals(Outcome.END_CALL, res.outcome)
        assertEquals("Okay, goodbye. I didn't add Call mom because it wasn't finished.", res.spoken)
        assertNull(r.engine.pendingDraft)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun startingADifferentTask_dropsTheOldOne_andSaysSo() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.say(TaskCommand.AddTask("buy milk"))
        assertEquals(
            "Dropping the unfinished Call mom. Got it: Buy milk. When is Buy milk due?",
            res.spoken
        )
        assertEquals("Buy milk", r.engine.pendingDraft?.title)
    }

    @Test
    fun endingTheSession_forgetsTheDraft() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        r.engine.resetContext()
        assertNull(r.engine.pendingDraft)
    }

    // ---- other commands while a question is open -----------------------------------------

    @Test
    fun aQuestionMidDialog_isAnswered_andTheDraftStaysOpen() {
        val r = rig(listOf(Task(title = "Follow-up", deadline = today(16, 30), createdAt = 0)))
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.say(TaskCommand.QueryTasks())
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("Follow-up"))
        assertTrue(res.spoken, res.spoken.endsWith("Back to Call mom. When is Call mom due?"))
        assertNotNull(r.engine.pendingDraft)
    }

    @Test
    fun aMisheardEdit_ofATaskThatDoesNotExist_isTreatedAsANewTaskAndAsked() {
        val r = rig()
        val res = r.say(
            TaskCommand.UpdateTask(TaskRef.ByTitle("gym"), TaskPatch(deadline = tomorrowAt(7)))
        )
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("Got it: Gym, due tomorrow at 7 AM."))
        assertTrue(r.store.all().isEmpty())
    }
}
