package com.example.kukoo

import com.example.kukoo.domain.ChatKind
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.EngineResult
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.PlanItemSpec
import com.example.kukoo.domain.PlanStage
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskEngine
import com.example.kukoo.domain.TaskEstimator
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId

/**
 * "Plan my day": the assistant asks what is on the user's mind, proposes a plan with a name, deadline,
 * length, priority and reminder for each task, and only writes anything once the user says yes. Changes
 * produce a new proposal. Time is 3 PM on Friday 2026-09-25 unless a test moves the clock.
 */
class DayPlanTest {
    private class MovableClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = TEST_ZONE
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private class Rig(val store: FakeStore, val engine: TaskEngine, val clock: MovableClock)

    private fun rig(tasks: List<Task> = emptyList(), at: Long = today(15)): Rig {
        val store = FakeStore(tasks)
        val clock = MovableClock(at)
        return Rig(store, TaskEngine(store, LocalReplanner(), clock), clock)
    }

    private fun Rig.say(command: TaskCommand): EngineResult = runBlocking { engine.executeSpoken(command) }

    private val gym = PlanItemSpec("Gym")
    private val doctor = PlanItemSpec("Take doctor appointment")
    private val exam = PlanItemSpec("Prepare GATE exam")

    private fun Rig.tomorrowThreeTasks(): EngineResult {
        say(TaskCommand.PlanDay(DayRef.Tomorrow))
        return say(TaskCommand.PlanAdd(listOf(gym, doctor, exam)))
    }

    // ---- opening ---------------------------------------------------------------------------

    @Test
    fun planMyDay_asksWhatIsOnTheirMind_andAddsNothing() {
        val r = rig()
        val res = r.say(TaskCommand.PlanDay())
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertEquals(
            "Sure, let's plan today. What's on your mind? Tell me the tasks you want to fit in, " +
                "and I'll suggest the best order and timing.",
            res.spoken
        )
        assertTrue(r.store.all().isEmpty())
        assertEquals(PlanStage.ASK_TASKS, r.engine.pendingPlan?.stage)
        assertEquals("today", r.engine.pendingPlan?.dayLabel)
    }

    @Test
    fun planMyDayForTomorrow_namesTheDay() {
        val res = rig().say(TaskCommand.PlanDay(DayRef.Tomorrow))
        assertTrue(res.spoken, res.spoken.startsWith("Sure, let's plan tomorrow."))
    }

    @Test
    fun planMyDayForADate_usesThatDate() {
        val r = rig()
        val res = r.say(TaskCommand.PlanDay(date = MonthDay.of(10, 5)))
        assertTrue(res.spoken, res.spoken.startsWith("Sure, let's plan Oct 5."))
        assertEquals("Oct 5", r.engine.pendingPlan?.dayLabel)
    }

    @Test
    fun aDateThatAlreadyPassedThisYear_meansNextYear() {
        val r = rig()
        r.say(TaskCommand.PlanDay(date = MonthDay.of(3, 1)))
        assertEquals("Mar 1", r.engine.pendingPlan?.dayLabel)
        val res = r.say(TaskCommand.PlanAdd(listOf(gym)))
        assertTrue(res.spoken, res.spoken.contains("Here's your plan for Mar 1."))
    }

    @Test
    fun aWeekday_isTheNextOne() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Weekday(DayOfWeek.MONDAY)))
        assertEquals("Monday", r.engine.pendingPlan?.dayLabel)
    }

    @Test
    fun lateInTheEvening_todayIsTooLate_soTomorrowIsPlanned() {
        val r = rig(at = today(20, 45))
        val res = r.say(TaskCommand.PlanDay())
        assertTrue(res.spoken, res.spoken.startsWith("It's too late to plan what's left of today, so I'll plan tomorrow. Sure, let's plan tomorrow."))
        assertEquals("tomorrow", r.engine.pendingPlan?.dayLabel)
    }

    @Test
    fun tasksGivenWithTheRequest_goStraightToAProposal() {
        val r = rig()
        val res = r.say(TaskCommand.PlanDay(DayRef.Tomorrow, items = listOf(gym, doctor)))
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("Got it: Gym and Take doctor appointment."))
        assertTrue(res.spoken, res.spoken.contains("Here's your plan for tomorrow."))
        assertEquals(PlanStage.REVIEW, r.engine.pendingPlan?.stage)
        assertTrue(r.store.all().isEmpty())
    }

    // ---- the proposal ----------------------------------------------------------------------

    @Test
    fun theProposal_hasNameTimeLengthPriorityAndReminderForEachTask() {
        val r = rig()
        val res = r.tomorrowThreeTasks()
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertEquals(
            "Got it: Gym, Take doctor appointment and Prepare GATE exam. Here's your plan for tomorrow. " +
                "Take doctor appointment, 8 to 9 AM, 1 hour, high priority. " +
                "Prepare GATE exam, 9:10 to 11:10 AM, 2 hours, high priority. " +
                "Gym, 11:20 AM to 12:20 PM, 1 hour, medium priority. " +
                "Each new task is due when its slot starts, and I'll call you shortly before it starts. " +
                "I estimated how long things take and how important they are, so tell me if any is off. " +
                "Say yes to add them, or tell me what to change.",
            res.spoken
        )
        assertTrue("nothing is written before a yes", r.store.all().isEmpty())
    }

    @Test
    fun theMostImportantWorkComesFirst() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(
            TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("Do laundry", priority = Priority.LOW),
                    PlanItemSpec("Finish report", priority = Priority.HIGH),
                )
            )
        )
        val plan = res.spoken.substringAfter("Here's your plan")
        assertTrue(res.spoken, plan.indexOf("Finish report") < plan.indexOf("Do laundry"))
    }

    @Test
    fun aLengthOrPriorityTheUserGave_beatsTheEstimate() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Gym", durationMin = 30, priority = Priority.HIGH))))
        assertTrue(res.spoken, res.spoken.contains("Gym, 8 to 8:30 AM, 30 minutes, high priority."))
        assertFalse(res.spoken, res.spoken.contains("I estimated"))
    }

    @Test
    fun aFixedTime_staysWhereTheUserPutIt_andTheRestFlowsAroundIt() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(
            TaskCommand.PlanAdd(listOf(PlanItemSpec("Take doctor appointment", at = LocalTime.of(16, 0)), exam, gym))
        )
        assertTrue(res.spoken, res.spoken.contains("Take doctor appointment, 4 to 5 PM, 1 hour, high priority."))
        assertTrue(res.spoken, res.spoken.contains("Prepare GATE exam, 8 to 10 AM"))
        assertTrue(res.spoken, res.spoken.contains("Gym, 10:10 to 11:10 AM"))
    }

    @Test
    fun aLongTask_isSplitAroundAFixedOne_andBothPartsAreSpoken() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(
            TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("Take doctor appointment", durationMin = 60, at = LocalTime.of(10, 0)),
                    PlanItemSpec("Prepare GATE exam", durationMin = 240),
                )
            )
        )
        // 8:00 to 9:50 is free before the appointment (with its break), then 11:10 onwards.
        assertTrue(res.spoken, res.spoken.contains("Prepare GATE exam, 8 to 9:50 AM and 11:10 AM to 1:20 PM, 4 hours"))
    }

    @Test
    fun whatIsAlreadyDueThatDay_isPlannedAround_notAddedTwice() {
        val rent = Task(title = "Pay rent", deadline = tomorrow(17), durationMin = 30, priority = Priority.HIGH, createdAt = 0)
        val r = rig(listOf(rent))
        val ask = r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        assertTrue(ask.spoken, ask.spoken.contains("You already have Pay rent on your list for then"))

        val res = r.say(TaskCommand.PlanAdd(listOf(gym)))
        assertTrue(res.spoken, res.spoken.contains("Pay rent, 8 to 8:30 AM, 30 minutes, high priority. It's already on your list."))

        val done = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.OK, done.outcome)
        assertEquals(listOf("Pay rent", "Gym"), r.store.all().map { it.title })
        assertEquals("Pay rent is untouched", tomorrow(17), r.store.all().first().deadline)
    }

    @Test
    fun sayingThatIsAll_plansJustWhatIsAlreadyDue() {
        val rent = Task(title = "Pay rent", deadline = tomorrow(17), durationMin = 30, createdAt = 0)
        val r = rig(listOf(rent))
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("Pay rent, 8 to 8:30 AM"))
        assertTrue(res.spoken, res.spoken.endsWith("Tell me any more tasks to add, or say that's all."))
        val done = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.OK, done.outcome)
        assertEquals(1, r.store.all().size)
        assertNull(r.engine.pendingPlan)
    }

    @Test
    fun sayingThatIsAll_withNothingToPlan_asksAgain() {
        val r = rig()
        r.say(TaskCommand.PlanDay())
        val res = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("There's nothing to plan yet."))
        assertNotNull(r.engine.pendingPlan)
    }

    @Test
    fun aTaskThatAlreadyExists_isFittedInWithoutBeingAddedAgain() {
        val existing = Task(title = "Gym", deadline = tomorrow(19), durationMin = 45, createdAt = 0)
        val r = rig(listOf(existing))
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanAdd(listOf(gym)))
        assertTrue(res.spoken, res.spoken.contains("Gym is already on your list, so I'll fit it in without adding it again."))
        r.say(TaskCommand.PlanApprove)
        assertEquals(1, r.store.all().size)
    }

    @Test
    fun whenNotEverythingFits_theLeftOutTaskIsSaidPlainly_andNotAdded() {
        val r = rig() // 3 PM: about 5h40 of free time left today
        r.say(TaskCommand.PlanDay())
        val res = r.say(
            TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("Finish report", durationMin = 300, priority = Priority.HIGH),
                    PlanItemSpec("Prepare GATE exam", durationMin = 120, priority = Priority.MEDIUM),
                )
            )
        )
        assertTrue(res.spoken, res.spoken.contains("I couldn't fit Prepare GATE exam into what's left of today."))
        val done = r.say(TaskCommand.PlanApprove)
        assertEquals(listOf("Finish report"), r.store.all().map { it.title })
        assertTrue(done.spoken, done.spoken.contains("I left out Prepare GATE exam because there wasn't room."))
    }

    // ---- saying yes ------------------------------------------------------------------------

    @Test
    fun yes_addsEveryTask_withDeadlineLengthPriorityAndReminder() {
        val r = rig()
        r.tomorrowThreeTasks()
        val done = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.OK, done.outcome)
        assertTrue(done.changedTasks)
        assertEquals(
            "Done. I added Take doctor appointment, Prepare GATE exam and Gym for tomorrow, each due when its slot starts, " +
                "each with a reminder call shortly before it starts. Say undo if you want them gone.",
            done.spoken
        )
        val saved = r.store.all().associateBy { it.title }
        val d = saved.getValue("Take doctor appointment")
        assertEquals(tomorrow(8), d.deadline)
        assertEquals(60, d.durationMin)
        assertEquals(Priority.HIGH, d.priority)
        assertEquals("rings at 7:50", tomorrow(7, 50), d.reminderAt)

        val e = saved.getValue("Prepare GATE exam")
        assertEquals(tomorrow(9, 10), e.deadline)
        assertEquals(120, e.durationMin)
        assertEquals("rings at 9:00, ten minutes before it starts", tomorrow(9, 0), e.reminderAt)

        val g = saved.getValue("Gym")
        assertEquals(tomorrow(11, 20), g.deadline)
        assertEquals(Priority.MEDIUM, g.priority)
        assertEquals(tomorrow(11, 10), g.reminderAt)
        assertNull(r.engine.pendingPlan)
        assertEquals(done.taskIds.size, 3)
    }

    @Test
    fun okay_afterAProposal_isAYes() {
        val r = rig()
        r.tomorrowThreeTasks()
        val done = r.say(TaskCommand.Chat(ChatKind.ACKNOWLEDGE))
        assertEquals(Outcome.OK, done.outcome)
        assertEquals(3, r.store.all().size)
    }

    @Test
    fun okay_whileStillWaitingForTheTasks_isNotAYes() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.Chat(ChatKind.ACKNOWLEDGE))
        assertTrue(res.spoken, res.spoken.startsWith("Okay. What's on your mind for tomorrow?"))
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun undo_takesTheWholePlanBack() {
        val r = rig()
        r.tomorrowThreeTasks()
        r.say(TaskCommand.PlanApprove)
        assertEquals(3, r.store.all().size)
        val undone = r.say(TaskCommand.Undo)
        assertEquals("Undid last action.", undone.spoken)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun yes_afterTimeHasMovedOn_refreshesThePlanInsteadOfAddingPastSlots() {
        val r = rig()
        r.say(TaskCommand.PlanDay())
        r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Finish report", durationMin = 60))))
        r.clock.now = today(16, 30) // the slot that was proposed has started and gone
        val res = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("Some of those times have passed while we were talking, so I've moved things."))
        assertTrue(res.spoken, res.spoken.contains("Finish report, 4:40 to 5:40 PM"))
        assertTrue(r.store.all().isEmpty())
        assertEquals(PlanStage.REVIEW, r.engine.pendingPlan?.stage)
    }

    @Test
    fun aFixedTimeThatPassedWhileTalking_becomesFlexible_andSaysSo() {
        val r = rig()
        r.say(TaskCommand.PlanDay())
        r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Take doctor appointment", at = LocalTime.of(16, 0)))))
        r.clock.now = today(16, 30)
        val res = r.say(TaskCommand.PlanApprove)
        assertTrue(res.spoken, res.spoken.contains("Today at 4 PM has already passed, so I'll fit Take doctor appointment in wherever there's room."))
        assertTrue(r.store.all().isEmpty())
    }

    // ---- changes ---------------------------------------------------------------------------

    @Test
    fun changingALength_givesANewProposal_andWritesNothing() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "gym", durationMin = 30))
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("Gym now takes 30 minutes. Here's your plan for tomorrow."))
        assertTrue(res.spoken, res.spoken.contains("Gym, 11:20 to 11:50 AM, 30 minutes, medium priority."))
        assertTrue(r.store.all().isEmpty())
        // ... and the new plan is what gets added.
        r.say(TaskCommand.PlanApprove)
        assertEquals(30, r.store.all().first { it.title == "Gym" }.durationMin)
    }

    @Test
    fun aMisheardTaskName_stillFindsTheRightTask() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "jim", priority = Priority.LOW))
        assertTrue(res.spoken, res.spoken.startsWith("Gym is now low priority."))
    }

    @Test
    fun movingATaskToATime_pinsItThere() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "gym", at = LocalTime.of(18, 0)))
        assertTrue(res.spoken, res.spoken.contains("Gym is now at 6 PM."))
        assertTrue(res.spoken, res.spoken.contains("Gym, 6 to 7 PM, 1 hour, medium priority."))
    }

    @Test
    fun aTimeThatHasPassed_isRefused_andTheTaskStaysWhereItWas() {
        val r = rig()
        r.say(TaskCommand.PlanDay())
        r.say(TaskCommand.PlanAdd(listOf(gym)))
        val res = r.say(TaskCommand.PlanChange(target = "gym", at = LocalTime.of(9, 0)))
        assertTrue(res.spoken, res.spoken.contains("Today at 9 AM has already passed. I left it where it was."))
        assertTrue(res.spoken, res.spoken.contains("Gym, 3:10 to 4:10 PM"))
    }

    @Test
    fun removingATask_dropsItFromThePlan() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "doctor", remove = true))
        assertTrue(res.spoken, res.spoken.startsWith("Removed Take doctor appointment."))
        assertFalse(res.spoken.substringAfter("Here's your plan").contains("doctor"))
        r.say(TaskCommand.PlanApprove)
        assertEquals(setOf("Prepare GATE exam", "Gym"), r.store.all().map { it.title }.toSet())
    }

    @Test
    fun removingEverything_goesBackToAskingWhatIsOnTheirMind() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        r.say(TaskCommand.PlanAdd(listOf(gym)))
        val res = r.say(TaskCommand.PlanChange(target = "gym", remove = true))
        assertEquals("Removed Gym. That leaves nothing to plan. What else is on your mind for tomorrow?", res.spoken)
        assertEquals(PlanStage.ASK_TASKS, r.engine.pendingPlan?.stage)
    }

    @Test
    fun aTaskAlreadyOnTheList_canOnlyBeLeftOut_notEditedFromHere() {
        val rent = Task(title = "Pay rent", deadline = tomorrow(17), durationMin = 30, createdAt = 0)
        val r = rig(listOf(rent))
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        r.say(TaskCommand.PlanAdd(listOf(gym)))
        val edit = r.say(TaskCommand.PlanChange(target = "pay rent", durationMin = 60))
        assertTrue(edit.spoken, edit.spoken.contains("Pay rent is already on your list, so I can only leave it out of this plan."))
        assertEquals(30, r.store.all().first { it.title == "Pay rent" }.durationMin)

        val out = r.say(TaskCommand.PlanChange(target = "pay rent", remove = true))
        assertTrue(out.spoken, out.spoken.startsWith("Left Pay rent out of the plan."))
        assertFalse(out.spoken.substringAfter("Here's your plan").contains("Pay rent"))
        assertEquals("still on the list, untouched", 1, r.store.all().count { it.title == "Pay rent" })
    }

    @Test
    fun aTaskThatIsNotInThePlan_isSaidSo_andThePlanStays() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "swimming", durationMin = 30))
        assertEquals("I couldn't find swimming in the plan. The plan has Take doctor appointment, Prepare GATE exam and Gym.", res.spoken)
        assertEquals(PlanStage.REVIEW, r.engine.pendingPlan?.stage)
    }

    @Test
    fun aTaskNamedWithoutAChange_asksWhatToChange() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(target = "gym"))
        assertTrue(res.spoken, res.spoken.startsWith("What would you like to change about Gym?"))
    }

    @Test
    fun aBareNo_asksWhatToChange() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange())
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("Sure, what should I change?"))
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun aLaterStart_movesTheWholeDay() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(at = LocalTime.of(10, 0)))
        assertTrue(res.spoken, res.spoken.startsWith("Starting from 10 AM."))
        assertTrue(res.spoken, res.spoken.contains("Take doctor appointment, 10 to 11 AM"))
    }

    @Test
    fun anotherDay_movesThePlan_andKeepsTheTasks() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanChange(day = DayRef.Weekday(DayOfWeek.MONDAY)))
        assertTrue(res.spoken, res.spoken.startsWith("Planning Monday instead. Here's your plan for Monday."))
        assertTrue(res.spoken, res.spoken.contains("Gym"))
        assertEquals("Monday", r.engine.pendingPlan?.dayLabel)
    }

    @Test
    fun saidAgainWithADay_planMyDayForFriday_changesTheDayInsteadOfLosingTheList() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.PlanDay(DayRef.Weekday(DayOfWeek.MONDAY)))
        assertTrue(res.spoken, res.spoken.startsWith("Planning Monday instead."))
        assertEquals(3, r.engine.pendingPlan?.taskTitles?.size)
    }

    @Test
    fun moreTasks_addedToAProposal_makeANewOne() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        r.say(TaskCommand.PlanAdd(listOf(gym)))
        val res = r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Cook dinner"))))
        assertTrue(res.spoken, res.spoken.startsWith("Got it: Cook dinner. Here's your plan for tomorrow."))
        assertTrue(res.spoken, res.spoken.contains("Gym") && res.spoken.contains("Cook dinner"))
    }

    @Test
    fun addATaskWhilePlanning_addsItToThePlan_notASecondDialog() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        r.say(TaskCommand.PlanAdd(listOf(gym)))
        val res = r.say(TaskCommand.AddTask("cook dinner"))
        assertTrue(res.spoken, res.spoken.contains("Got it: Cook dinner."))
        assertNull(r.engine.pendingDraft)
        assertEquals(2, r.engine.pendingPlan?.taskTitles?.size)
    }

    @Test
    fun anEditHeardAsATaskEdit_whileReviewing_changesThePlanNotARealTask() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.UpdateTask(TaskRef.ByTitle("gym"), TaskPatch(durationMin = 30)))
        assertTrue(res.spoken, res.spoken.startsWith("Gym now takes 30 minutes."))
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun aDeleteHeardWhileReviewing_dropsItFromThePlan_neverFromTheList() {
        val existing = Task(title = "Buy milk", deadline = tomorrow(20), createdAt = 0)
        val r = rig(listOf(existing))
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.DeleteTask(TaskRef.ByTitle("gym")))
        assertTrue(res.spoken, res.spoken.startsWith("Removed Gym."))
        assertEquals(1, r.store.all().size)
    }

    // ---- bad input -------------------------------------------------------------------------

    @Test
    fun aLengthOutOfRange_isRefusedButTheTaskIsKept() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Gym", durationMin = 2))))
        assertTrue(res.spoken, res.spoken.startsWith("A task should take between 5 minutes and 8 hours."))
        assertTrue(res.spoken, res.spoken.contains("Gym, 8 to 9 AM, 1 hour"))
    }

    @Test
    fun aFixedTimeInThePast_isRefused_andTheTaskFloats() {
        val r = rig()
        r.say(TaskCommand.PlanDay())
        val res = r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Gym", at = LocalTime.of(9, 0)))))
        assertTrue(res.spoken, res.spoken.contains("Today at 9 AM has already passed. I'll fit it in wherever there's room."))
        assertTrue(res.spoken, res.spoken.contains("Gym, 3:10 to 4:10 PM"))
    }

    @Test
    fun aTimeThatWouldRunPastMidnight_isRefused() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanAdd(listOf(PlanItemSpec("Prepare GATE exam", durationMin = 120, at = LocalTime.of(23, 0)))))
        assertTrue(res.spoken, res.spoken.contains("Prepare GATE exam wouldn't finish before midnight if it started at 11 PM."))
    }

    @Test
    fun twoFixedTasksThatOverlap_blockTheYes_untilOneMoves() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(
            TaskCommand.PlanAdd(
                listOf(
                    PlanItemSpec("Take doctor appointment", at = LocalTime.of(16, 0)),
                    PlanItemSpec("Gym", at = LocalTime.of(16, 30)),
                )
            )
        )
        assertTrue(res.spoken, res.spoken.contains("Take doctor appointment and Gym overlap."))
        assertTrue(res.spoken, res.spoken.endsWith("Tell me a different time for one of them."))

        val blocked = r.say(TaskCommand.PlanApprove)
        assertEquals(Outcome.NEEDS_INFO, blocked.outcome)
        assertTrue(blocked.spoken, blocked.spoken.startsWith("I haven't added anything yet."))
        assertTrue(r.store.all().isEmpty())

        r.say(TaskCommand.PlanChange(target = "gym", at = LocalTime.of(18, 0)))
        assertEquals(Outcome.OK, r.say(TaskCommand.PlanApprove).outcome)
        assertEquals(2, r.store.all().size)
    }

    @Test
    fun moreThanTwelveTasks_areCapped_andTheRestSaidPlainly() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val many = (1..14).map { PlanItemSpec("Task number $it", durationMin = 15) }
        val res = r.say(TaskCommand.PlanAdd(many))
        assertTrue(res.spoken, res.spoken.contains("I can plan up to 12 tasks at a time, so I left out Task number 13."))
        assertEquals(12, r.engine.pendingPlan?.taskTitles?.size)
    }

    @Test
    fun aReplyWithNoTasks_asksAgain() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.PlanAdd(emptyList()))
        assertEquals(
            "Sorry, I didn't catch any tasks. What's on your mind for tomorrow? Tell me the tasks you want to fit in.",
            res.spoken
        )
    }

    @Test
    fun aYesWithNoPlanOpen_isRefused() {
        val res = rig().say(TaskCommand.PlanApprove)
        assertEquals(Outcome.REJECTED, res.outcome)
    }

    // ---- leaving ---------------------------------------------------------------------------

    @Test
    fun cancelling_dropsThePlan_andSaysNothingWasAdded() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.DiscardDraft)
        assertEquals("Okay, I've dropped the plan for tomorrow. Nothing was added.", res.spoken)
        assertNull(r.engine.pendingPlan)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun hangingUpMidPlan_saysThePlanWasNotAdded() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.EndCall)
        assertEquals(Outcome.END_CALL, res.outcome)
        assertEquals("Okay, goodbye. I didn't add the plan because it wasn't approved.", res.spoken)
        assertNull(r.engine.pendingPlan)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun endingTheSession_forgetsThePlan() {
        val r = rig()
        r.tomorrowThreeTasks()
        r.engine.resetContext()
        assertNull(r.engine.pendingPlan)
    }

    @Test
    fun aQuestionMidPlan_isAnswered_thenThePlanQuestionIsRepeated() {
        val r = rig(listOf(Task(title = "Follow-up", deadline = today(16, 30), createdAt = 0)))
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = r.say(TaskCommand.QueryTasks())
        assertTrue(res.spoken, res.spoken.contains("Follow-up"))
        assertTrue(res.spoken, res.spoken.endsWith("Back to planning tomorrow. What's on your mind for tomorrow? Tell me the tasks you want to fit in."))
        assertNotNull(r.engine.pendingPlan)
    }

    @Test
    fun aQuestionWhileReviewing_bringsThePlanBack() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.QueryTasks())
        assertTrue(res.spoken, res.spoken.endsWith("Back to planning tomorrow. Say yes to add them, or tell me what to change."))
    }

    @Test
    fun anUnclearReply_repeatsWhatIsWaitedFor() {
        val r = rig()
        r.tomorrowThreeTasks()
        val res = r.say(TaskCommand.Unsupported("blah"))
        assertEquals("Sorry, I didn't catch that. Say yes to add them, or tell me what to change.", res.spoken)
    }

    @Test
    fun startingAPlan_dropsAnUnfinishedTaskDialog_andSaysSo() {
        val r = rig()
        r.say(TaskCommand.AddTask("call mom"))
        val res = r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        assertTrue(res.spoken, res.spoken.startsWith("Dropping the unfinished Call mom. Sure, let's plan tomorrow."))
        assertNull(r.engine.pendingDraft)
    }

    @Test
    fun theOrdinaryFormPath_stillAddsImmediately() {
        val r = rig()
        r.say(TaskCommand.PlanDay(DayRef.Tomorrow))
        val res = runBlocking { r.engine.execute(TaskCommand.AddTask("buy milk")) }
        assertEquals(Outcome.OK, res.outcome)
        assertEquals(1, r.store.all().size)
    }

    // ---- the estimates ---------------------------------------------------------------------

    @Test
    fun estimates_areSensibleForCommonTasks() {
        assertEquals(60, TaskEstimator.durationFor("Gym"))
        assertEquals(60, TaskEstimator.durationFor("Take doctor appointment"))
        assertEquals(120, TaskEstimator.durationFor("Prepare GATE exam"))
        assertEquals(15, TaskEstimator.durationFor("Book doctor appointment"))
        assertEquals(20, TaskEstimator.durationFor("Call mom"))
        assertEquals(45, TaskEstimator.durationFor("Buy groceries and cook"))
        assertEquals(TaskEstimator.DEFAULT_MINUTES, TaskEstimator.durationFor("Something unusual"))
        assertEquals(Priority.HIGH, TaskEstimator.priorityFor("Take doctor appointment"))
        assertEquals(Priority.HIGH, TaskEstimator.priorityFor("Prepare GATE exam"))
        assertEquals(Priority.MEDIUM, TaskEstimator.priorityFor("Gym"))
        assertEquals(Priority.LOW, TaskEstimator.priorityFor("Do laundry"))
    }

    @Test
    fun aShortKeyword_doesNotMatchAsAPrefix() {
        // "ready" must not be read as "read".
        assertEquals(TaskEstimator.DEFAULT_MINUTES, TaskEstimator.durationFor("Get ready"))
        assertEquals(Priority.MEDIUM, TaskEstimator.priorityFor("Get ready"))
    }

    @Test
    fun plansAreDeterministic() {
        val a = rig().tomorrowThreeTasks().spoken
        val b = rig().tomorrowThreeTasks().spoken
        assertEquals(a, b)
    }
}
