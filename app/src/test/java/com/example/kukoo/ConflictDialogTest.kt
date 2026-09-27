package com.example.kukoo

import com.example.kukoo.ai.ParseContext
import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

/**
 * The assistant on a call: what it asks when a time clashes, and how it reacts to every kind of answer.
 * Each line goes through the same loop the app uses (parse with the open question as context, then
 * execute), so what is asserted is what the user would hear.
 */
class ConflictDialogTest {

    private class Talk(tasks: List<Task>, clock: Clock = clockAt(15)) {
        val store = FakeStore(tasks)
        val engine = TaskEngine(store, LocalReplanner(), clock)
        private val parser = RuleBasedIntentParser()

        fun say(text: String): String = runBlocking {
            val context = ParseContext(
                openTaskTitles = store.all().filter { !it.isDone }.map { it.title },
                draft = engine.pendingDraft,
                conflict = engine.pendingConflict
            )
            engine.executeSpoken(parser.parse(text, context)).spoken
        }

        fun task(title: String) = store.all().first { it.title == title }
        fun tasks(title: String) = store.all().filter { it.title == title }
    }

    /** Standup 4:00-4:30 PM today. It is 3 PM. */
    private fun standup(priority: Priority = Priority.MEDIUM) =
        Task(title = "Standup", deadline = today(16), durationMin = 30, priority = priority, createdAt = 0)

    private val overlapQuestion =
        "Call mom today at 4 PM would overlap Standup, from 4 PM to 4:30 PM. " +
            "I can fit it today at 4:30 PM instead. Should I use that, pick another time, or keep both?"

    /** Gets to the overlap question for a new 30-minute "Call mom" at 4 PM. */
    private fun Talk.reachTheQuestion(): String {
        assertEquals(
            "Got it: Call mom, starts today at 4 PM. How long will Call mom take?",
            say("add call mom today at 4 pm")
        )
        return say("30 minutes")
    }

    // ---- a new task that overlaps: the questions ------------------------------------------------

    @Test
    fun aNewTaskThatOverlaps_isNotSaved_andTheAssistantOffersTheNearestFreeTime() {
        val t = Talk(listOf(standup()))
        val asked = t.reachTheQuestion()
        assertEquals("Got it: 30 minutes. $overlapQuestion", asked)
        assertTrue("nothing is saved while a conflict is open", t.tasks("Call mom").isEmpty())
        assertNotNull(t.engine.pendingConflict)
        assertTrue(t.engine.pendingConflict!!.forDraft)
    }

    @Test
    fun yes_usesTheSuggestedTime_andCarriesOnWithTheRestOfTheQuestions() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        assertEquals(
            "Okay, Call mom starts today at 4:30 PM. Is Call mom high, medium or low priority?",
            t.say("yes")
        )
        t.say("high")
        val added = t.say("none")
        assertEquals("Added Call mom, starting today at 4:30 PM, 30 minutes. High priority.", added)
        assertEquals(today(16, 30), t.task("Call mom").deadline)
        assertNull("no question is left open", t.engine.pendingConflict)
    }

    @Test
    fun manyWaysOfSayingYes_areAllUnderstood() {
        listOf("yeah", "sure", "okay", "sounds good", "that works", "yes please", "go ahead", "do it", "use that", "the first one")
            .forEach { yes ->
                val t = Talk(listOf(standup()))
                t.reachTheQuestion()
                val reply = t.say(yes)
                assertTrue("\"$yes\" -> $reply", reply.startsWith("Okay, Call mom starts today at 4:30 PM."))
            }
    }

    @Test
    fun keepBoth_savesItAtTheTimeAsked_andRemembersThatItWasOkay() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        assertEquals("Okay, I'll keep both. Is Call mom high, medium or low priority?", t.say("keep both"))
        t.say("low")
        t.say("none")
        assertEquals(today(16), t.task("Call mom").deadline)
        // It was a decision, not an oversight: it is not raised again.
        assertEquals("You have no overlapping tasks.", t.say("any conflicts"))
    }

    @Test
    fun manyWaysOfKeepingBoth_areAllUnderstood() {
        listOf("keep both", "leave it", "both", "it's fine", "no it's fine", "double book it", "I don't mind", "doesn't matter")
            .forEach { phrase ->
                val t = Talk(listOf(standup()))
                t.reachTheQuestion()
                val reply = t.say(phrase)
                assertTrue("\"$phrase\" -> $reply", reply.startsWith("Okay, I'll keep both.") || reply.startsWith("Okay, I'll keep both"))
            }
    }

    @Test
    fun no_asksForAnotherTime_insteadOfCancellingOrGuessing() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        assertEquals("Okay. What time would you like for Call mom instead? Or say keep both.", t.say("no"))
        assertNotNull("the task is still being set up", t.engine.pendingDraft)
        // Now a time that is free.
        assertEquals(
            "Got it: starts today at 6 PM. Is Call mom high, medium or low priority?",
            t.say("at 6 pm")
        )
    }

    @Test
    fun aNewTime_thatClashesAgain_isQuestionedAgain() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        val again = t.say("at 4:15 pm")
        assertTrue(again, again.startsWith("Got it: starts today at 4:15 PM. Call mom today at 4:15 PM would overlap Standup"))
        assertTrue(again, again.endsWith("Should I use that, pick another time, or keep both?"))
    }

    @Test
    fun aBareHour_isTheNewTime() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        val reply = t.say("six")
        assertTrue(reply, reply.startsWith("Got it: starts today at 6 PM."))
    }

    @Test
    fun aDayAlone_movesTheDay_andKeepsTheTimeOfDay() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        assertEquals(
            "Got it: starts tomorrow at 4 PM. Is Call mom high, medium or low priority?",
            t.say("tomorrow")
        )
    }

    @Test
    fun aShorterLength_thatNoLongerOverlaps_resolvesIt() {
        // Standup is 4:00-4:30; a 30-minute task at 3:45 overlaps by 15 minutes, a 15-minute one does not.
        val t = Talk(listOf(standup()))
        t.say("add call mom today at 3:45 pm")
        val asked = t.say("30 minutes")
        assertTrue(asked, asked.contains("would overlap Standup"))
        assertEquals(
            "Got it: 15 minutes. Is Call mom high, medium or low priority?",
            t.say("make it 15 minutes")
        )
    }

    @Test
    fun cancel_dropsTheNewTask() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        assertEquals("Okay, I won't add Call mom.", t.say("cancel"))
        assertNull(t.engine.pendingDraft)
        assertTrue(t.tasks("Call mom").isEmpty())
    }

    @Test
    fun anUnrelatedQuestion_isAnswered_andTheConflictQuestionComesBack() {
        val t = Talk(listOf(standup()))
        t.reachTheQuestion()
        val reply = t.say("what's on today")
        assertTrue(reply, reply.contains("Standup at 4 PM"))
        assertTrue(reply, reply.contains("Call mom today at 4 PM would overlap Standup"))
    }

    @Test
    fun whenNothingIsFreeThisWeek_theAssistantSaysSoInsteadOfInventingATime() {
        val day = (0..7).map { d ->
            Task(title = "Busy $d", deadline = millis(TODAY.plusDays(d.toLong()), 7), durationMin = 480, createdAt = 0)
        } + (0..7).map { d ->
            Task(title = "Busy2 $d", deadline = millis(TODAY.plusDays(d.toLong()), 15), durationMin = 480, createdAt = 0)
        }
        val t = Talk(day)
        t.say("add call mom tomorrow at 9 am")
        val asked = t.say("30 minutes")
        assertTrue(asked, asked.contains("I couldn't find a free slot this week. What other time works, or should I keep both?"))
        // A "yes" has nothing to accept: it says so and asks again rather than guessing.
        val yes = t.say("yes")
        assertTrue(yes, yes.startsWith("I don't have a free time to offer."))
    }

    @Test
    fun overlappingSeveralTasks_namesThem() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, createdAt = 0)
            )
        )
        t.say("add call mom today at 4 pm")
        val asked = t.say("45 minutes")
        assertTrue(asked, asked.contains("Call mom today at 4 PM would overlap Standup and Dentist."))
    }

    @Test
    fun aTaskAskedForInOneSentence_isStillCheckedBeforeItIsSaved() {
        val t = Talk(listOf(standup()))
        val reply = t.say("add call mom today at 4 pm for 30 minutes")
        assertTrue(reply, reply.contains("would overlap Standup"))
        assertTrue(t.tasks("Call mom").isEmpty())
    }

    @Test
    fun noOverlap_meansNoExtraQuestion() {
        val t = Talk(listOf(standup()))
        t.say("add call mom today at 6 pm")
        assertEquals("Got it: 30 minutes. Is Call mom high, medium or low priority?", t.say("30 minutes"))
    }

    @Test
    fun backToBack_isNotAnOverlap() {
        val t = Talk(listOf(standup()))
        t.say("add call mom today at 4:30 pm")
        assertEquals("Got it: 30 minutes. Is Call mom high, medium or low priority?", t.say("30 minutes"))
    }

    // ---- an odd hour: a probably misheard AM/PM ---------------------------------------------------

    @Test
    fun aStartInTheMiddleOfTheNight_isConfirmed_beforeAnythingElseIsAsked() {
        val t = Talk(emptyList())
        val reply = t.say("add gym tomorrow at 3 am")
        assertEquals(
            "Got it: Gym. Gym would start tomorrow at 3 AM, in the middle of the night. Did you mean tomorrow at 3 PM?",
            reply
        )
        assertEquals("Okay, Gym starts tomorrow at 3 PM. How long will Gym take?", t.say("yes"))
    }

    @Test
    fun anEarlyStart_thatIsReallyMeant_isKept() {
        val t = Talk(emptyList())
        t.say("add flight tomorrow at 3 am")
        assertEquals("Okay, tomorrow at 3 AM. How long will Flight take?", t.say("no that's right"))
        // It is not asked again.
        assertEquals("Got it: 2 hours. Is Flight high, medium or low priority?", t.say("2 hours"))
    }

    @Test
    fun aCorrectedTime_replacesTheOddOne() {
        val t = Talk(emptyList())
        t.say("add gym tomorrow at 3 am")
        assertEquals("Got it: starts tomorrow at 7 PM. How long will Gym take?", t.say("7 pm"))
    }

    @Test
    fun sixInTheMorning_isNotQuestioned() {
        val t = Talk(emptyList())
        assertEquals("Got it: Gym, starts tomorrow at 6 AM. How long will Gym take?", t.say("add gym tomorrow at 6 am"))
    }

    // ---- changing an existing task ---------------------------------------------------------------

    private fun twoTasks() = listOf(
        Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
        Task(title = "Dentist", deadline = today(18), durationMin = 30, createdAt = 0)
    )

    @Test
    fun movingATaskOntoAnother_isDone_thenTheAssistantOffersToFixIt() {
        val t = Talk(twoTasks())
        val reply = t.say("move the dentist to 4:15 pm")
        assertEquals(
            "Done. Dentist now starts today at 4:15 PM. Heads up: it overlaps Standup, from 4 PM to 4:30 PM. " +
                "Want me to move Dentist to today at 4:30 PM, or keep both?",
            reply
        )
        assertEquals(today(16, 15), t.task("Dentist").deadline)
        assertNotNull(t.engine.pendingConflict)
        assertFalse(t.engine.pendingConflict!!.forDraft)
    }

    @Test
    fun yes_movesTheTaskToTheOfferedTime() {
        val t = Talk(twoTasks())
        t.say("move the dentist to 4:15 pm")
        assertEquals("Done. Dentist now starts today at 4:30 PM.", t.say("yes"))
        assertEquals("You have no overlapping tasks.", t.say("any conflicts"))
    }

    @Test
    fun keepBoth_orNo_orNeverMind_leavesItAndDoesNotRaiseItAgain() {
        listOf("keep both", "no", "never mind", "leave it").forEach { phrase ->
            val t = Talk(twoTasks())
            t.say("move the dentist to 4:15 pm")
            assertEquals("\"$phrase\"", "Okay, I'll keep both.", t.say(phrase))
            assertEquals("You have no overlapping tasks.", t.say("any conflicts"))
            assertEquals(today(16, 15), t.task("Dentist").deadline)
        }
    }

    @Test
    fun aDifferentTime_canBeGivenInstead_evenAsABareHour() {
        val t = Talk(twoTasks())
        t.say("move the dentist to 4:15 pm")
        assertEquals("Done. Dentist now starts today at 7 PM.", t.say("7 pm"))
        val t2 = Talk(twoTasks())
        t2.say("move the dentist to 4:15 pm")
        assertEquals("Done. Dentist now starts today at 7 PM.", t2.say("seven"))
    }

    @Test
    fun theOffer_isOnlyGoodForTheNextThingSaid() {
        val t = Talk(twoTasks())
        t.say("move the dentist to 4:15 pm")
        t.say("what's on today")
        assertNull(t.engine.pendingConflict)
        // "yes" now means nothing in particular.
        assertFalse(t.say("yes").contains("Dentist now starts"))
    }

    @Test
    fun changingHowLongATaskTakes_intoAnother_isAlsoCaught() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 30), durationMin = 30, createdAt = 0)
            )
        )
        val reply = t.say("change the duration of standup to 45 minutes")
        assertTrue(reply, reply.contains("Heads up: it overlaps Dentist, from 4:30 PM to 5 PM."))
    }

    @Test
    fun anEditThatDoesNotCauseAnOverlap_sayNothingAboutConflicts() {
        val t = Talk(twoTasks())
        assertEquals("Done. Dentist now starts today at 7 PM.", t.say("move the dentist to 7 pm"))
    }

    @Test
    fun editingAnAlreadyOverlappingTask_unrelatedly_doesNotNag() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, createdAt = 0)
            )
        )
        val reply = t.say("make the dentist high priority")
        assertEquals("Updated Dentist: high priority.", reply)
    }

    // ---- asking about conflicts ------------------------------------------------------------------

    @Test
    fun anyConflicts_saysWhichTasks_forHowLong_andOffersToMoveTheLessImportantOne() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, priority = Priority.HIGH, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, priority = Priority.LOW, createdAt = 1)
            )
        )
        assertEquals(
            "You have 1 overlap. Standup and Dentist overlap by 15 minutes, from 4:15 PM to 4:30 PM. " +
                "Want me to move Dentist to today at 4:30 PM, or keep both?",
            t.say("any conflicts")
        )
        assertEquals("Done. Dentist now starts today at 4:30 PM.", t.say("yes"))
        assertEquals("You have no overlapping tasks.", t.say("do I have any overlaps"))
    }

    @Test
    fun withNoConflicts_itSaysSo() {
        assertEquals("You have no overlapping tasks.", Talk(twoTasks()).say("am I double booked"))
    }

    @Test
    fun whenAmIFree_findsTheNextGap() {
        val t = Talk(listOf(Task(title = "Standup", deadline = today(15), durationMin = 60, createdAt = 0)))
        assertEquals("Your next free 30 minutes is today at 4 PM.", t.say("when am I free"))
        assertEquals("Your next free 1 hour is today at 4 PM.", t.say("when can I fit an hour"))
    }

    // ---- planning the day ------------------------------------------------------------------------

    @Test
    fun planMyDay_readsTheOverlap_andOffersToFixIt() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, priority = Priority.HIGH, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, priority = Priority.LOW, createdAt = 1)
            )
        )
        val reply = t.say("plan my day")
        assertTrue(reply, reply.contains("Conflict: Standup and Dentist overlap by 15 minutes, from 4:15 PM to 4:30 PM."))
        assertTrue(reply, reply.endsWith("Want me to move Dentist to today at 4:30 PM, or keep both?"))
        assertEquals("Done. Dentist now starts today at 4:30 PM.", t.say("yes"))
    }

    @Test
    fun planMyDay_offersATimeForATaskWithoutOne_oneAtATime() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(15, 30), durationMin = 30, createdAt = 0),
                Task(title = "Book flights", deadline = null, durationMin = 60, priority = Priority.HIGH, createdAt = 1),
                Task(title = "Tidy desk", deadline = null, durationMin = 15, priority = Priority.LOW, createdAt = 2)
            )
        )
        val plan = t.say("plan my day")
        assertTrue(plan, plan.contains("Suggested: 4 PM, Book flights, 1 hour."))
        assertTrue(plan, plan.endsWith("Want me to schedule Book flights for today at 4 PM?"))
        val next = t.say("yes")
        // The desk tidy fits in the gap before Standup, so that is where it goes.
        assertEquals("Done. Book flights now starts today at 4 PM. Want me to schedule Tidy desk for today at 3 PM?", next)
        assertEquals(today(16), t.task("Book flights").deadline)
        assertNull("the second one waits for its own yes", t.task("Tidy desk").deadline)
        assertEquals("Done. Tidy desk now starts today at 3 PM.", t.say("yes"))
    }

    // ---- the calls themselves --------------------------------------------------------------------

    @Test
    fun theDailyCall_opensByRaisingTheOverlap_andAsksWhatToDo() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, priority = Priority.HIGH, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, priority = Priority.LOW, createdAt = 1)
            )
        )
        val opening = t.engine.briefing()
        assertTrue(opening, opening.contains("You have an overlap today: Standup and Dentist overlap by 15 minutes, from 4:15 PM to 4:30 PM."))
        assertTrue(opening, opening.endsWith("Want me to move Dentist to today at 4:30 PM, or keep both?"))
        assertFalse(opening, opening.contains("What would you like to do?"))
        assertEquals("Done. Dentist now starts today at 4:30 PM.", t.say("yes"))
    }

    @Test
    fun aReminderCall_forATaskThatOverlaps_saysSo_andOffersToMoveIt() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, createdAt = 1)
            )
        )
        val id = t.task("Dentist").id
        val opening = t.engine.taskBriefing(id)!!
        assertTrue(opening, opening.contains("It overlaps Standup, from 4 PM to 4:30 PM."))
        assertTrue(opening, opening.endsWith("Want me to move Dentist to today at 4:30 PM, or keep both?"))
    }

    @Test
    fun aCallWithNoOverlap_isUnchanged() {
        val opening = Talk(twoTasks()).engine.briefing()
        assertTrue(opening, opening.endsWith("What would you like to do?"))
    }

    // ---- snooze and reopen ---------------------------------------------------------------------

    @Test
    fun snoozeIntoAnotherTask_warnsAboutTheNewOverlap() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(15, 30), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = tomorrow(9), durationMin = 30, createdAt = 0)
            )
        )
        // Nothing overlaps yet; snoozing only moves today's tasks, so tomorrow's is untouched.
        val reply = t.say("snooze 15 minutes")
        assertFalse(reply, reply.contains("Heads up"))
    }

    // ---- the add / edit form (no conversation: it warns, and "Save anyway" is a decision) ------------

    private fun Talk.form(command: TaskCommand) = runBlocking { engine.execute(command) }

    @Test
    fun aFormAdd_thatOverlaps_isSaved_andSaysWhatItRunsInto() {
        val t = Talk(listOf(standup()))
        val res = t.form(TaskCommand.AddTask("Call mom", DeadlineSpec.Exact(today(16)), 30))
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.endsWith("Heads up: it overlaps Standup, from 4 PM to 4:30 PM."))
        assertEquals(1, t.tasks("Call mom").size)
        assertNull("a form never leaves a spoken question behind", t.engine.pendingConflict)
    }

    @Test
    fun saveAnyway_isRemembered_soItIsNotWarnedAboutAgain() {
        val t = Talk(listOf(standup()))
        val res = t.form(TaskCommand.AddTask("Call mom", DeadlineSpec.Exact(today(16)), 30, keepOverlaps = true))
        assertFalse(res.spoken, res.spoken.contains("Heads up"))
        assertEquals("You have no overlapping tasks.", t.say("any conflicts"))
        assertEquals(1, t.store.acks().size)
    }

    @Test
    fun anAcknowledgedOverlap_comesBack_ifEitherTaskIsMoved() {
        val t = Talk(listOf(standup()))
        t.form(TaskCommand.AddTask("Call mom", DeadlineSpec.Exact(today(16)), 30, keepOverlaps = true))
        val res = t.form(
            TaskCommand.UpdateTask(TaskRef.ByTitle("call mom"), TaskPatch(deadline = DeadlineSpec.Exact(today(16, 10))))
        )
        assertTrue(res.spoken, res.spoken.contains("Heads up: it overlaps Standup"))
    }

    @Test
    fun deletingATask_forgetsWhatWasAcknowledgedAboutIt() {
        val t = Talk(listOf(standup()))
        t.form(TaskCommand.AddTask("Call mom", DeadlineSpec.Exact(today(16)), 30, keepOverlaps = true))
        t.form(TaskCommand.DeleteTask(TaskRef.ByTitle("call mom")))
        assertTrue(t.store.acks().isEmpty())
    }

    @Test
    fun aFormEdit_ofAnOverlappingTask_withKeepOverlaps_acknowledgesWithoutAnyChange() {
        val t = Talk(
            listOf(
                Task(title = "Standup", deadline = today(16), durationMin = 30, createdAt = 0),
                Task(title = "Dentist", deadline = today(16, 15), durationMin = 30, createdAt = 0)
            )
        )
        t.form(TaskCommand.UpdateTask(TaskRef.ByTitle("dentist"), TaskPatch(keepOverlaps = true)))
        assertEquals("You have no overlapping tasks.", t.say("any conflicts"))
    }
}
