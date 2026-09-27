package com.example.kukoo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.ui.KukooViewModel
import com.example.kukoo.ui.Phase
import com.example.kukoo.ui.Screen
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
import java.time.Instant
import java.time.LocalDate

/**
 * End-to-end on the JVM with the real ViewModel, real SQLite (via Robolectric) and the real
 * engine. Understanding is the NPU model's job in the app and there is no NPU here, so a
 * deterministic parser is injected: what these tests cover is the engine, the dialog and the
 * ViewModel, not how an utterance is understood (that is LlamaIntentParserTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KukooApp::class)
class AppFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<KukooApp>()

    /** Every test runs at 3 PM on a fixed day, so "due today" / "tomorrow" never depend on the wall clock. */
    private val clock = clockAt(15)

    private fun newVm(): KukooViewModel = KukooViewModel(app.also { it.container = AppContainer(it, clock, RuleBasedIntentParser()) }).also { vm ->
        await("seed data loaded") { vm.state.value.loaded && vm.state.value.tasks.size == 5 }
    }

    /** The ViewModel hops to Dispatchers.IO and back to the main looper; pump until [condition] holds. */
    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    private fun KukooViewModel.task(title: String): Task = state.value.tasks.first { it.title == title }

    private fun KukooViewModel.say(text: String, expectedReply: String? = null) {
        val before = state.value.session.turns.size
        submitText(text)
        await("reply to '$text'") {
            val s = state.value.session
            s.turns.size >= before + 2 && s.phase == Phase.IDLE || state.value.screen == Screen.PLAN
        }
        await("idle after '$text'") { state.value.session.phase == Phase.IDLE || state.value.screen == Screen.PLAN }
        if (expectedReply != null) assertEquals(expectedReply, state.value.session.turns.last().text)
    }

    private fun KukooViewModel.startAndAwaitBriefing() {
        startSession()
        await("briefing") { state.value.session.turns.isNotEmpty() && state.value.session.phase == Phase.IDLE }
    }

    @Test
    fun firstLaunch_seedsTheDemoTasks() {
        val vm = newVm()
        assertEquals(
            setOf("Follow-up", "Client Deck", "Design Review", "Expense report", "Book flights"),
            vm.state.value.tasks.map { it.title }.toSet()
        )
    }

    @Test
    fun answeringTheCall_opensSessionWithBriefing() {
        val vm = newVm()
        vm.ringNow()
        assertEquals(Screen.INCOMING_CALL, vm.state.value.screen)
        vm.answerCall()
        assertEquals(Screen.SESSION, vm.state.value.screen)
        await("briefing") { vm.state.value.session.turns.isNotEmpty() && vm.state.value.session.phase == Phase.IDLE }
        val briefing = vm.state.value.session.turns.first()
        assertFalse(briefing.fromUser)
        assertTrue(briefing.text, briefing.text.endsWith("What would you like to do?"))
        assertTrue(briefing.text, briefing.text.contains("Follow-up"))
    }

    @Test
    fun decliningTheCall_returnsHome() {
        val vm = newVm()
        vm.ringNow()
        vm.declineCall()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun backPress_followsTheNavigationRules() {
        val vm = newVm()
        assertFalse("Home lets the system leave the app", vm.onBack())
        vm.ringNow()
        assertTrue(vm.onBack())
        assertEquals(Screen.HOME, vm.state.value.screen)
        vm.startSession()
        assertTrue(vm.onBack())
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun demoScript_endToEnd_persistsToSqlite() {
        val vm = newVm()
        vm.startAndAwaitBriefing()

        vm.say("Move the client deck to tomorrow")
        val deck = vm.task("Client Deck")
        assertEquals(
            LocalDate.now(clock).plusDays(1),
            Instant.ofEpochMilli(deck.deadline!!).atZone(clock.zone).toLocalDate()
        )
        assertTrue(vm.state.value.session.turns.last().text.startsWith("Done. Client Deck is now due tomorrow at "))

        vm.say("Mark the follow-up as done", "Done. I marked Follow-up as done.")
        assertEquals(TaskStatus.DONE, vm.task("Follow-up").status)

        vm.say("Delete the expense task", "Done. I deleted Expense report.")
        assertTrue(vm.state.value.tasks.none { it.title == "Expense report" })

        // A new task is only added once it has a name, a deadline, a duration, a priority and a reminder answer.
        vm.say("Add a task: finish the report tomorrow at 5")
        assertTrue(vm.state.value.tasks.none { it.title == "Finish the report" })
        vm.say("45 minutes")
        vm.say("high")
        vm.say("no reminder")
        assertNotNull(vm.task("Finish the report").deadline)

        // Everything above is really in the database, not just in memory.
        val fromDisk = SqliteTaskStore(app).all()
        assertEquals(TaskStatus.DONE, fromDisk.first { it.title == "Follow-up" }.status)
        assertTrue(fromDisk.none { it.title == "Expense report" })
        assertEquals(deck.deadline, fromDisk.first { it.title == "Client Deck" }.deadline)
        assertTrue(fromDisk.any { it.title == "Finish the report" })
    }

    @Test
    fun replanMyAfternoon_opensPlanScreen_andBackReturnsToTheCall() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.submitText("Replan my afternoon")
        await("plan screen") { vm.state.value.screen == Screen.PLAN }
        val plan = vm.state.value.plan!!
        assertTrue("plan has blocks", plan.blocks.isNotEmpty())
        assertEquals(Screen.SESSION, vm.state.value.planReturnsTo)

        assertTrue(vm.onBack())
        assertEquals(Screen.SESSION, vm.state.value.screen)
        await("assistant reply recorded") { vm.state.value.session.turns.last().text.startsWith("Here is your") }
    }

    @Test
    fun unknownTask_isRejected_andNothingChanges() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        val before = vm.state.value.tasks
        vm.say("Delete the payroll task", "I couldn't find a task called payroll.")
        assertEquals(before, vm.state.value.tasks)
        assertEquals(Outcome.REJECTED, vm.state.value.session.turns.last().outcome)
    }

    @Test
    fun unsupportedSpeech_getsHelpNotACrash() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.say("sing me a song")
        assertEquals(Outcome.UNSUPPORTED, vm.state.value.session.turns.last().outcome)
    }

    @Test
    fun saying_goodbye_endsTheCall() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.submitText("goodbye")
        await("back home") { vm.state.value.screen == Screen.HOME }
        assertTrue(vm.state.value.session.turns.isEmpty())
    }

    @Test
    fun holdToTalk_withoutSttModel_explainsTheFallback() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.onMicPress()
        vm.onMicRelease()
        assertNotNull(vm.state.value.session.hint)
        assertEquals(Phase.IDLE, vm.state.value.session.phase)
    }

    @Test
    fun addingATask_asksForEveryMissingDetail_beforeSavingAnything() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        val before = vm.state.value.tasks.size

        vm.say("add a task", "What should I call the task?")
        assertEquals(before, vm.state.value.tasks.size)

        vm.say("call mom", "Got it: Call mom. When is Call mom due?")
        vm.say("tomorrow at 6", "Got it: due tomorrow at 6 PM. How long will Call mom take?")
        assertEquals(before, vm.state.value.tasks.size)

        // Something that is not an answer gets the same question again, not a guess.
        vm.say("purple", "Sorry, I didn't catch that. How long will Call mom take?")
        assertEquals(before, vm.state.value.tasks.size)

        vm.say("half an hour", "Got it: 30 minutes. Is Call mom high, medium or low priority?")
        assertEquals(before, vm.state.value.tasks.size)

        vm.say(
            "medium",
            "Got it: medium priority. Do you want a reminder call before Call mom? " +
                "I can call you 30, 15 or 10 minutes before, or say none."
        )
        assertEquals(before, vm.state.value.tasks.size)

        vm.say("10 minutes before")
        assertEquals(before + 1, vm.state.value.tasks.size)
        val saved = vm.task("Call mom")
        assertEquals(millis(TOMORROW, 18), saved.deadline)
        assertEquals(30, saved.durationMin)
        assertEquals(Priority.MEDIUM, saved.priority)
        assertEquals(10, saved.reminderMin)
        assertTrue(vm.state.value.session.turns.last().text.endsWith("Medium priority."))
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun aQuestionMidDialog_isAnswered_thenTheDialogContinues() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.say("add call mom tomorrow at 6 pm")   // asks how long
        vm.say("what's due today")
        assertTrue(vm.state.value.session.turns.last().text.endsWith("Back to Call mom. How long will Call mom take?"))
        vm.say("20 minutes")
        vm.say("low")
        vm.say("none")
        assertEquals(20, vm.task("Call mom").durationMin)
        assertNull(vm.task("Call mom").reminderMin)
    }

    @Test
    fun cancellingMidDialog_addsNothing() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        val before = vm.state.value.tasks.size
        vm.say("add call mom tomorrow at 6 pm")
        vm.say("never mind", "Okay, I won't add Call mom.")
        assertEquals(before, vm.state.value.tasks.size)
        // Nothing is pending any more, so a bare answer is not read as one.
        vm.say("high")
        assertEquals(before, vm.state.value.tasks.size)
    }

    @Test
    fun editor_addsAndEditsTasks_andShowsEngineErrors() {
        val vm = newVm()
        vm.openEditor(null)
        vm.saveEditor("Buy milk", null, 15, Priority.LOW)
        await("task added and editor closed") {
            vm.state.value.tasks.any { it.title == "Buy milk" } && vm.state.value.editor == null
        }
        assertEquals(15, vm.task("Buy milk").durationMin)

        val milk = vm.task("Buy milk")
        vm.openEditor(milk)
        vm.saveEditor("Buy oat milk", null, 20, Priority.HIGH)
        await("task edited and editor closed") {
            vm.state.value.tasks.any { it.title == "Buy oat milk" } && vm.state.value.editor == null
        }
        assertEquals(Priority.HIGH, vm.task("Buy oat milk").priority)
        assertEquals(milk.id, vm.task("Buy oat milk").id)

        // A deadline in the past is refused and the editor stays open with the reason.
        vm.openEditor(null)
        vm.saveEditor("Yesterday thing", clock.millis() - 3_600_000, 30, Priority.MEDIUM)
        await("error shown") { vm.state.value.editor?.error != null }
        assertTrue(vm.state.value.editor!!.error!!.contains("already passed"))
        assertTrue(vm.state.value.tasks.none { it.title == "Yesterday thing" })
    }

    @Test
    fun toggleAndDeleteFromHome() {
        val vm = newVm()
        val task = vm.task("Book flights")
        vm.toggleDone(task)
        await("done") { vm.task("Book flights").isDone }
        vm.toggleDone(vm.task("Book flights"))
        await("reopened") { !vm.task("Book flights").isDone }
        vm.deleteTask(vm.task("Book flights"))
        await("deleted and announced") {
            vm.state.value.tasks.none { it.title == "Book flights" } && vm.state.value.notice != null
        }
        assertEquals("Done. I deleted Book flights.", vm.state.value.notice)
    }

    @Test
    fun planFromHome_showsPlanAndReturnsHome() {
        val vm = newVm()
        vm.planFromHome(com.example.kukoo.domain.PlanScope.DAY)
        await("plan") { vm.state.value.screen == Screen.PLAN }
        assertEquals(Screen.HOME, vm.state.value.planReturnsTo)
        vm.onBack()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun resetDemoData_restoresTheSeed() {
        val vm = newVm()
        vm.deleteTask(vm.task("Book flights"))
        await("deleted") { vm.state.value.tasks.size == 4 }
        vm.resetDemoData()
        await("reset") { vm.state.value.tasks.size == 5 }
    }

    @Test
    fun incomingCallWhileInSession_isIgnored() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        vm.onIncomingCall(fromAlarm = true)
        assertEquals(Screen.SESSION, vm.state.value.screen)
    }

    @Test
    fun missedCall_whenAppLeavesScreenWhileRinging() {
        val vm = newVm()
        vm.ringNow()
        vm.onAppBackgrounded()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun quickActionChips_undoAndQuickTimePicker() {
        val vm = newVm()
        vm.startAndAwaitBriefing()
        assertFalse(vm.state.value.canUndo)

        vm.say("Add a task: water plants tomorrow at 5")
        assertFalse("still asking, so nothing was added to undo", vm.state.value.canUndo)
        vm.say("20 minutes")
        vm.say("low")
        vm.say("none")
        assertTrue(vm.state.value.canUndo)
        val actions = vm.state.value.session.quickActions
        assertEquals(listOf("+1 Hour", "Tomorrow 9 AM", "Snooze 15m"), actions.map { it.label })

        // A chip is just a typed command: "Tomorrow 9 AM" moves the task that was just added.
        vm.say(actions.first { it.label == "Tomorrow 9 AM" }.text)
        assertEquals(millis(TOMORROW, 9), vm.task("Water plants").deadline)

        vm.say(vm.state.value.session.quickActions.first { it.label == "+1 Hour" }.text)
        assertEquals(millis(TOMORROW, 10), vm.task("Water plants").deadline)

        // The snackbar's Undo runs the Undo command and reports it in the call.
        vm.executeCommand(com.example.kukoo.domain.TaskCommand.Undo)
        await("undo applied") { vm.state.value.session.turns.last().text == "Undid last action." }
        assertEquals(millis(TOMORROW, 9), vm.task("Water plants").deadline)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun quickTimePicker_changesOnlyTheTime() {
        val vm = newVm()
        val followUp = vm.task("Follow-up") // seeded 40 minutes after 3 PM
        vm.setTaskTime(followUp, 18, 0)
        await("time changed") { vm.task("Follow-up").deadline == millis(TODAY, 18) && vm.state.value.canUndo }
    }
}

/** Store contract on a real SQLite database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KukooApp::class)
class SqliteTaskStoreTest {
    private val store get() = SqliteTaskStore(ApplicationProvider.getApplicationContext())

    @Test
    fun roundTripsEveryField_includingNulls() {
        val s = store
        val saved = s.insert(
            Task(title = "A", deadline = null, durationMin = 45, priority = Priority.HIGH, createdAt = 123)
        )
        assertTrue(saved.id > 0)
        val loaded = s.get(saved.id)!!
        assertEquals(saved, loaded)
        assertNull(loaded.deadline)
        assertNull(loaded.completedAt)
    }

    @Test
    fun updateDeleteAndDeleteAll() {
        val s = store
        val a = s.insert(Task(title = "A", deadline = 1_000, createdAt = 1))
        val b = s.insert(Task(title = "B", createdAt = 2))
        s.update(a.copy(title = "A2", status = TaskStatus.DONE, completedAt = 5, deadline = null))
        assertEquals("A2", s.get(a.id)!!.title)
        assertEquals(TaskStatus.DONE, s.get(a.id)!!.status)
        assertNull(s.get(a.id)!!.deadline)
        assertTrue(s.delete(b.id))
        assertFalse(s.delete(b.id))
        assertEquals(1, s.all().size)
        s.deleteAll()
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun updatingAMissingTask_fails_insteadOfSilentlyDoingNothing() {
        try {
            store.update(Task(id = 999, title = "ghost", createdAt = 0))
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun allReturnsInsertionOrder() {
        val s = store
        listOf("one", "two", "three").forEach { s.insert(Task(title = it, createdAt = 0)) }
        assertEquals(listOf("one", "two", "three"), s.all().map { it.title })
    }
}
