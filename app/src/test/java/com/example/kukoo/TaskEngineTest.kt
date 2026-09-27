package com.example.kukoo

import com.example.kukoo.domain.ConflictKind
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineResolver
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskEngine
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TimeFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime

class TaskEngineTest {

    private fun seed() = listOf(
        Task(title = "Follow-up", deadline = today(16, 30), durationMin = 30, createdAt = 0),
        Task(title = "Client Deck", deadline = today(17, 20), durationMin = 60, priority = Priority.HIGH, createdAt = 0),
        Task(title = "Design Review", deadline = tomorrow(11), durationMin = 45, createdAt = 0),
        Task(title = "Expense report", deadline = tomorrow(12), durationMin = 20, priority = Priority.LOW, createdAt = 0)
    )

    private class Rig(val store: FakeStore, val engine: TaskEngine)

    private fun rig(tasks: List<Task> = seed(), clock: Clock = clockAt(15)): Rig {
        val store = FakeStore(tasks)
        return Rig(store, TaskEngine(store, LocalReplanner(), clock))
    }

    private fun Rig.run(cmd: TaskCommand) = runBlocking { engine.execute(cmd) }
    private fun Rig.byTitle(title: String) = store.all().first { it.title == title }
    private fun title(q: String) = TaskRef.ByTitle(q)

    // ---- recurrence & snooze -------------------------------------------------------------

    @Test
    fun completingARecurringTask_spawnsTheNextOccurrence() {
        val r = rig(listOf(Task(title = "Standup", deadline = today(16), durationMin = 15, priority = Priority.HIGH,
            createdAt = 0, recurrence = Recurrence.DAILY, notes = "Room 4")))
        val res = r.run(TaskCommand.CompleteTask(title("standup")))
        assertEquals(Outcome.OK, res.outcome)
        assertEquals("Done. I marked Standup as done. The next one is due tomorrow at 4 PM.", res.spoken)
        val all = r.store.all()
        assertEquals(2, all.size)
        val next = all.first { !it.isDone }
        assertEquals(tomorrow(16), next.deadline)
        assertEquals(Recurrence.DAILY, next.recurrence)
        assertEquals("Room 4", next.notes)
        assertEquals(Priority.HIGH, next.priority)
        assertEquals(15, next.durationMin)

        // Undo removes the spawned copy and reopens the original.
        r.run(TaskCommand.Undo)
        assertEquals(1, r.store.all().size)
        assertEquals(TaskStatus.OPEN, r.store.all().single().status)
    }

    @Test
    fun weekdaysRecurrence_skipsTheWeekend() {
        // Clock is Friday: next weekday is Monday.
        val r = rig(listOf(Task(title = "Report", deadline = today(17), createdAt = 0, recurrence = Recurrence.WEEKDAYS)))
        r.run(TaskCommand.CompleteTask(title("report")))
        val next = r.store.all().first { !it.isDone }
        assertEquals(millis(TODAY.plusDays(3), 17), next.deadline)
    }

    @Test
    fun weeklyAndMonthlyRecurrence_advance() {
        val r = rig(listOf(
            Task(title = "Weekly", deadline = today(17), createdAt = 0, recurrence = Recurrence.WEEKLY),
            Task(title = "Monthly", deadline = today(17), createdAt = 0, recurrence = Recurrence.MONTHLY)
        ))
        r.run(TaskCommand.CompleteTask(title("weekly")))
        r.run(TaskCommand.CompleteTask(title("monthly")))
        val open = r.store.all().filter { !it.isDone }
        assertEquals(millis(TODAY.plusDays(7), 17), open.first { it.title == "Weekly" }.deadline)
        assertEquals(millis(TODAY.plusMonths(1), 17), open.first { it.title == "Monthly" }.deadline)
    }

    @Test
    fun completingANonRecurringTask_addsNothing() {
        val r = rig()
        val before = r.store.all().size
        r.run(TaskCommand.CompleteTask(title("follow-up")))
        assertEquals(before, r.store.all().size)
    }

    @Test
    fun snooze_shiftsOverdueAndTodaysTasks_notTomorrows() {
        val r = rig()
        val res = r.run(TaskCommand.Snooze(15))
        assertEquals("Snoozed tasks by 15 minutes.", res.spoken)
        assertEquals(today(16, 45), r.byTitle("Follow-up").deadline)
        assertEquals(today(17, 35), r.byTitle("Client Deck").deadline)
        assertEquals(tomorrow(11), r.byTitle("Design Review").deadline)

        r.run(TaskCommand.Undo)
        assertEquals(today(16, 30), r.byTitle("Follow-up").deadline)
    }

    @Test
    fun snooze_withNothingDue_saysSo() {
        val res = rig(listOf(Task(title = "Later", deadline = tomorrow(9), createdAt = 0))).run(TaskCommand.Snooze())
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken.contains("nothing to snooze"))
    }

    @Test
    fun updatingNotesAndRecurrence_works() {
        val r = rig()
        val res = r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(recurrence = Recurrence.WEEKLY, notes = "Bring slides")))
        assertEquals(Outcome.OK, res.outcome)
        assertEquals(Recurrence.WEEKLY, r.byTitle("Client Deck").recurrence)
        assertEquals("Bring slides", r.byTitle("Client Deck").notes)
        r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(notes = "  ")))
        assertNull(r.byTitle("Client Deck").notes)
    }

    @Test
    fun add_recurringAtAPastHour_startsTomorrow() {
        val r = rig(emptyList()) // clock is 3 PM
        val res = r.run(
            TaskCommand.AddTask(
                "finish daily standup",
                DeadlineSpec.Relative(time = LocalTime.of(10, 0)),
                recurrence = Recurrence.DAILY
            )
        )
        assertEquals(Outcome.OK, res.outcome)
        assertEquals("Added 'Finish daily standup' starting tomorrow at 10 AM (repeats daily).", res.spoken)
        assertEquals(tomorrow(10), r.store.all().single().deadline)
    }

    @Test
    fun add_bareTimeStillAheadToday_staysToday_andExplicitPastTodayRollsOver() {
        val r = rig(emptyList())
        r.run(TaskCommand.AddTask("call dad", DeadlineSpec.Relative(time = LocalTime.of(17, 0))))
        assertEquals(today(17), r.store.all().single().deadline)
        r.run(TaskCommand.AddTask("x", DeadlineSpec.Relative(DayRef.Today, LocalTime.of(10, 0))))
        assertEquals(tomorrow(10), r.byTitle("X").deadline)
    }

    @Test
    fun exactPastDeadline_fromTheEditor_isStillRejected() {
        val res = rig(emptyList()).run(TaskCommand.AddTask("old", DeadlineSpec.Exact(today(9))))
        assertEquals(Outcome.REJECTED, res.outcome)
    }

    @Test
    fun updatingATaskThatDoesNotExist_withATime_selfHealsIntoAdd() {
        val r = rig(emptyList())
        val res = r.run(
            TaskCommand.UpdateTask(
                title("gym"),
                TaskPatch(deadline = DeadlineSpec.Relative(time = LocalTime.of(12, 0)), recurrence = Recurrence.DAILY)
            )
        )
        assertEquals(Outcome.OK, res.outcome)
        val gym = r.store.all().single()
        assertEquals("Gym", gym.title)
        assertEquals(Recurrence.DAILY, gym.recurrence)
        assertEquals(tomorrow(12), gym.deadline) // 3 PM now, so noon rolls to tomorrow
    }

    @Test
    fun updatingAMissingTask_withNothingToCreateFrom_stillSaysNotFound() {
        val res = rig().run(TaskCommand.UpdateTask(title("gym"), TaskPatch(notes = "x")))
        assertEquals(Outcome.REJECTED, res.outcome)
        assertTrue(res.spoken.contains("couldn't find"))
    }

    @Test
    fun implicitCreation_fromPlainPhrases() {
        val parser = com.example.kukoo.ai.RuleBasedIntentParser()
        val r = rig(emptyList())
        val cmd = parser.parseNow("workout at 6pm")
        assertTrue(cmd.toString(), cmd is TaskCommand.AddTask)
        r.run(cmd)
        val t = r.store.all().single()
        assertEquals("Workout", t.title)
        assertEquals(today(18), t.deadline)

        val gym = parser.parseNow("gym everyday at 12pm") as TaskCommand.AddTask
        assertEquals("gym", gym.title)
        assertEquals(Recurrence.DAILY, gym.recurrence)
        assertTrue(parser.parseNow("read book at 9pm") is TaskCommand.AddTask)
        // Finishing / asking phrases are not new tasks.
        assertTrue(parser.parseNow("what is due at 5pm") !is TaskCommand.AddTask)
        assertTrue(parser.parseNow("mark gym done at 5pm") !is TaskCommand.AddTask)
    }

    @Test
    fun deadlineAt8AM_whenItIs2PM_meansTomorrow() {
        val r = rig(listOf(Task(title = "Gym", deadline = tomorrow(18), createdAt = 0)), clockAt(14))
        r.run(TaskCommand.UpdateTask(title("gym"), TaskPatch(deadline = DeadlineSpec.Relative(time = LocalTime.of(8, 0)))))
        // Task is due tomorrow, so its own date is kept: tomorrow 8 AM.
        assertEquals(tomorrow(8), r.byTitle("Gym").deadline)
        r.run(TaskCommand.AddTask("run", DeadlineSpec.Relative(DayRef.Today, LocalTime.of(8, 0))))
        assertEquals(tomorrow(8), r.byTitle("Run").deadline)
    }

    // ---- undo ----------------------------------------------------------------------------

    @Test
    fun undo_withNothingToUndo_isRejected() {
        val res = rig().run(TaskCommand.Undo)
        assertEquals(Outcome.REJECTED, res.outcome)
        assertEquals("There is nothing to undo.", res.spoken)
    }

    @Test
    fun undo_revertsAdd_complete_andDelete_oneStepAtATime() {
        val r = rig()
        r.run(TaskCommand.AddTask("Call mum"))
        assertEquals(Outcome.OK, r.run(TaskCommand.Undo).outcome)
        assertTrue(r.store.all().none { it.title == "Call mum" })

        r.run(TaskCommand.CompleteTask(title("follow-up")))
        assertEquals("Undid last action.", r.run(TaskCommand.Undo).spoken)
        assertEquals(TaskStatus.OPEN, r.byTitle("Follow-up").status)

        r.run(TaskCommand.DeleteTask(title("expense report")))
        r.run(TaskCommand.Undo)
        assertEquals(20, r.byTitle("Expense report").durationMin)

        // Single step: a second undo has nothing left.
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.Undo).outcome)
    }

    @Test
    fun undo_revertsAnUpdate() {
        val r = rig()
        val before = r.byTitle("Client Deck").deadline
        r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Tomorrow))))
        r.run(TaskCommand.Undo)
        assertEquals(before, r.byTitle("Client Deck").deadline)
    }

    // ---- add -----------------------------------------------------------------------------

    @Test
    fun add_resolvesRelativeDeadline_capitalisesSpokenTitle() {
        val r = rig()
        val res = r.run(
            TaskCommand.AddTask("finish the report", DeadlineSpec.Relative(DayRef.Tomorrow, LocalTime.of(17, 0)))
        )
        assertEquals(Outcome.OK, res.outcome)
        assertEquals("Added Finish the report, due tomorrow at 5 PM, 30 minutes.", res.spoken)
        val saved = r.byTitle("Finish the report")
        assertEquals(tomorrow(17), saved.deadline)
        assertEquals(Priority.MEDIUM, saved.priority)
        assertEquals(TaskStatus.OPEN, saved.status)
    }

    @Test
    fun add_withoutDeadline_sayssoAndKeepsMixedCaseTitles() {
        val r = rig(emptyList())
        val res = r.run(TaskCommand.AddTask("Call iPhone repair", durationMin = 15, priority = Priority.HIGH))
        assertEquals("Added Call iPhone repair, with no deadline, 15 minutes.", res.spoken)
        assertNull(r.store.all().single().deadline)
    }

    @Test
    fun add_relativeTimeInThePast_rollsToTomorrow() {
        val r = rig(emptyList())
        val res = r.run(TaskCommand.AddTask("call mom", DeadlineSpec.Relative(DayRef.Today, LocalTime.of(9, 0))))
        assertEquals(Outcome.OK, res.outcome)
        assertEquals("Added Call mom, due tomorrow at 9 AM, 30 minutes.", res.spoken)
        assertEquals(tomorrow(9), r.store.all().single().deadline)
    }

    @Test
    fun add_withReminder_savesItAndSaysWhenTheCallComes() {
        val r = rig(emptyList()) // 3 PM
        val res = r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(today(18)), reminderMin = 30))
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("I'll call you 30 minutes before."))
        val saved = r.store.all().single()
        assertEquals(30, saved.reminderMin)
        assertEquals(today(17) + 30 * 60_000L, saved.reminderAt)
    }

    @Test
    fun add_reminderThatWouldRingBeforeNow_isRefused() {
        val r = rig(emptyList()) // 3 PM: a 30 minute reminder for a 3:20 PM task would ring at 2:50 PM
        val deadline = today(15) + 20 * 60_000L
        val res = r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(deadline), reminderMin = 30))
        assertEquals(Outcome.REJECTED, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("has already passed"))
        assertTrue(r.store.all().isEmpty())
        // A shorter reminder for the same task is fine.
        assertEquals(Outcome.OK, r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(deadline), reminderMin = 10)).outcome)
    }

    @Test
    fun add_reminderNeedsADeadline_andASaneLength() {
        val r = rig(emptyList())
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("Gym", reminderMin = 10)).outcome)
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(tomorrow(18)), reminderMin = 0)).outcome)
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(tomorrow(18)), reminderMin = 2000)).outcome)
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun update_movingTheDeadlineSoonerThanTheReminder_dropsTheReminderAndSaysSo() {
        val r = rig(emptyList())
        r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(today(18)), reminderMin = 30))
        val id = r.byTitle("Gym").id
        val res = r.run(
            TaskCommand.UpdateTask(TaskRef.ById(id), TaskPatch(deadline = DeadlineSpec.Exact(today(15) + 10 * 60_000L)))
        )
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.contains("reminder removed"))
        assertNull(r.byTitle("Gym").reminderMin)
    }

    @Test
    fun update_settingAClashingReminder_isRefused_andSavingUnrelatedEditsIsNot() {
        val r = rig(emptyList())
        val soon = today(15) + 20 * 60_000L
        r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(soon), reminderMin = 10))
        val id = r.byTitle("Gym").id
        val clash = r.run(TaskCommand.UpdateTask(TaskRef.ById(id), TaskPatch(reminderMin = 30)))
        assertEquals(Outcome.REJECTED, clash.outcome)
        assertEquals(10, r.byTitle("Gym").reminderMin)
        // The form resends the untouched reminder along with the deadline when only notes changed.
        val notesOnly = r.run(
            TaskCommand.UpdateTask(
                TaskRef.ById(id),
                TaskPatch(deadline = DeadlineSpec.Exact(soon), reminderMin = 10, notes = "bring shoes")
            )
        )
        assertEquals(notesOnly.spoken, Outcome.OK, notesOnly.outcome)
        assertEquals(10, r.byTitle("Gym").reminderMin)
        // And the reminder can be turned off.
        r.run(TaskCommand.UpdateTask(TaskRef.ById(id), TaskPatch(clearReminder = true)))
        assertNull(r.byTitle("Gym").reminderMin)
    }

    @Test
    fun taskBriefing_talksAboutOnlyThatTask() {
        val r = rig(emptyList())
        r.run(TaskCommand.AddTask("Gym", DeadlineSpec.Exact(today(18)), durationMin = 60, priority = Priority.HIGH, reminderMin = 30))
        r.run(TaskCommand.AddTask("Laundry", DeadlineSpec.Exact(today(19))))
        val briefing = r.engine.taskBriefing(r.byTitle("Gym").id)!!
        assertTrue(briefing, briefing.startsWith("Reminder: Gym is due today at 6 PM, in 3 hours."))
        assertTrue(briefing, briefing.contains("1 hour, high priority"))
        assertFalse(briefing, briefing.contains("Laundry"))
        r.run(TaskCommand.CompleteTask(TaskRef.ById(r.byTitle("Gym").id)))
        assertNull(r.engine.taskBriefing(r.byTitle("Gym").id))
    }

    @Test
    fun add_validatesTitleAndDuration() {
        val r = rig(emptyList())
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("   ")).outcome)
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("x".repeat(121))).outcome)
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("ok", durationMin = 3)).outcome)
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.AddTask("ok", durationMin = 600)).outcome)
        assertTrue(r.store.all().isEmpty())
    }

    // ---- update --------------------------------------------------------------------------

    @Test
    fun move_toTomorrow_keepsTimeOfDay() {
        val r = rig()
        val res = r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Tomorrow))))
        assertEquals("Done. Client Deck is now due tomorrow at 5:20 PM.", res.spoken)
        assertEquals(tomorrow(17, 20), r.byTitle("Client Deck").deadline)
    }

    @Test
    fun move_taskWithoutDeadline_usesEndOfWorkingDay() {
        val r = rig(listOf(Task(title = "Book flights", createdAt = 0)))
        r.run(TaskCommand.UpdateTask(title("flights"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Tomorrow))))
        assertEquals(tomorrow(18), r.byTitle("Book flights").deadline)
    }

    @Test
    fun changeDeadline_toSixPm_appliesToTheTaskJustDiscussed() {
        val r = rig()
        r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Tomorrow))))
        val res = r.run(TaskCommand.UpdateTask(TaskRef.Last, TaskPatch(deadline = DeadlineSpec.Relative(time = LocalTime.of(18, 0)))))
        assertEquals("Done. Client Deck is now due tomorrow at 6 PM.", res.spoken)
        assertEquals(tomorrow(18), r.byTitle("Client Deck").deadline)
    }

    @Test
    fun last_withoutContext_asksWhichTask() {
        val r = rig()
        val res = r.run(TaskCommand.UpdateTask(TaskRef.Last, TaskPatch(deadline = DeadlineSpec.Relative(time = LocalTime.of(18, 0)))))
        assertEquals(Outcome.NEEDS_CLARIFICATION, res.outcome)
        assertEquals(seed().map { it.deadline }, r.store.all().map { it.deadline })
    }

    @Test
    fun update_relativePastTime_rollsToTomorrow() {
        val r = rig()
        val res = r.run(TaskCommand.UpdateTask(title("follow up"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Today, LocalTime.of(9, 0)))))
        assertEquals(Outcome.OK, res.outcome)
        assertEquals(tomorrow(9), r.byTitle("Follow-up").deadline)
    }

    @Test
    fun update_multipleFields_andClearDeadline() {
        val r = rig()
        val res = r.run(
            TaskCommand.UpdateTask(title("client deck"), TaskPatch(durationMin = 90, priority = Priority.LOW))
        )
        assertEquals("Updated Client Deck: takes 1 hour 30 minutes, low priority.", res.spoken)
        val cleared = r.run(TaskCommand.UpdateTask(title("client deck"), TaskPatch(clearDeadline = true)))
        assertEquals("Done. Client Deck no longer has a deadline.", cleared.spoken)
        assertNull(r.byTitle("Client Deck").deadline)
    }

    @Test
    fun update_emptyPatch_andDoneTask_areRejected() {
        val r = rig()
        assertEquals(Outcome.REJECTED, r.run(TaskCommand.UpdateTask(title("deck"), TaskPatch())).outcome)
        r.run(TaskCommand.CompleteTask(title("follow up")))
        val res = r.run(TaskCommand.UpdateTask(title("follow up"), TaskPatch(priority = Priority.HIGH)))
        assertEquals(Outcome.REJECTED, res.outcome)
    }

    @Test
    fun update_byId_unchangedOverdueDeadline_isAllowed() {
        val overdue = Task(title = "Old", deadline = today(9), createdAt = 0)
        val r = rig(listOf(overdue))
        val id = r.store.all().single().id
        val res = r.run(
            TaskCommand.UpdateTask(TaskRef.ById(id), TaskPatch(title = "Old thing", deadline = DeadlineSpec.Exact(today(9))))
        )
        assertEquals(Outcome.OK, res.outcome)
        assertEquals("Old thing", r.store.get(id)!!.title)
    }

    @Test
    fun weekday_resolvesToNextOccurrence_strictlyAfterToday() {
        val resolver = DeadlineResolver(clockAt(15))
        val nextFriday = resolver.resolve(DeadlineSpec.Relative(DayRef.Weekday(DayOfWeek.FRIDAY), LocalTime.of(10, 0)), null)
        assertEquals(millis(TODAY.plusDays(7), 10), nextFriday)
        val monday = resolver.resolve(DeadlineSpec.Relative(DayRef.Weekday(DayOfWeek.MONDAY), LocalTime.of(9, 0)), null)
        assertEquals(millis(TODAY.plusDays(3), 9), monday)
    }

    // ---- complete / reopen / delete ------------------------------------------------------

    @Test
    fun complete_marksDone_andIsIdempotent() {
        val r = rig()
        val res = r.run(TaskCommand.CompleteTask(title("the follow-up")))
        assertEquals("Done. I marked Follow-up as done.", res.spoken)
        val t = r.byTitle("Follow-up")
        assertEquals(TaskStatus.DONE, t.status)
        assertNotNull(t.completedAt)
        assertEquals("Follow-up is already done.", r.run(TaskCommand.CompleteTask(title("follow up"))).spoken)
    }

    @Test
    fun reopen_bringsBackADoneTask() {
        val r = rig()
        r.run(TaskCommand.CompleteTask(title("follow up")))
        val res = r.run(TaskCommand.ReopenTask(title("follow up")))
        assertEquals("Okay, Follow-up is open again.", res.spoken)
        assertEquals(TaskStatus.OPEN, r.byTitle("Follow-up").status)
        assertNull(r.byTitle("Follow-up").completedAt)
    }

    @Test
    fun delete_removesTask() {
        val r = rig()
        val res = r.run(TaskCommand.DeleteTask(title("expense task")))
        assertEquals("Done. I deleted Expense report.", res.spoken)
        assertEquals(3, r.store.all().size)
    }

    @Test
    fun unknownTask_isRejected_withoutTouchingAnything() {
        val r = rig()
        val res = r.run(TaskCommand.DeleteTask(title("payroll")))
        assertEquals(Outcome.REJECTED, res.outcome)
        assertEquals("I couldn't find a task called payroll.", res.spoken)
        assertEquals(4, r.store.all().size)
    }

    @Test
    fun ambiguousName_asksForClarification_andChangesNothing() {
        val r = rig(listOf(Task(title = "Client Deck", createdAt = 0), Task(title = "Client Call", createdAt = 0)))
        val res = r.run(TaskCommand.DeleteTask(title("client")))
        assertEquals(Outcome.NEEDS_CLARIFICATION, res.outcome)
        assertEquals("I found 2 matching tasks: Client Deck and Client Call. Which one do you mean?", res.spoken)
        assertEquals(2, r.store.all().size)
    }

    @Test
    fun exactTitle_winsOverLongerTitleThatContainsIt() {
        val r = rig(listOf(Task(title = "Client Deck Review", createdAt = 0), Task(title = "Client Deck", createdAt = 0)))
        r.run(TaskCommand.CompleteTask(title("client deck")))
        assertEquals(TaskStatus.DONE, r.byTitle("Client Deck").status)
        assertEquals(TaskStatus.OPEN, r.byTitle("Client Deck Review").status)
    }

    @Test
    fun speechStyleNames_match_pluralsAndRunTogetherWords() {
        val r = rig()
        r.run(TaskCommand.CompleteTask(title("followup")))
        assertEquals(TaskStatus.DONE, r.byTitle("Follow-up").status)
        r.run(TaskCommand.DeleteTask(title("expense reports")))
        assertTrue(r.store.all().none { it.title == "Expense report" })
    }

    // ---- queries & briefing --------------------------------------------------------------

    @Test
    fun queryToday_listsOpenTasksDueToday_inDeadlineOrder() {
        val r = rig()
        val res = r.run(TaskCommand.QueryTasks(QueryScope.TODAY))
        assertEquals("You have 2 tasks due today: Follow-up at 4:30 PM and Client Deck at 5:20 PM.", res.spoken)
    }

    @Test
    fun queryToday_reportsOverdueSeparately_andIgnoresDone() {
        val r = rig(seed() + Task(title = "Send invoice", deadline = today(9), createdAt = 0))
        r.run(TaskCommand.CompleteTask(title("client deck")))
        val res = r.run(TaskCommand.QueryTasks(QueryScope.TODAY))
        assertEquals(
            "1 task is overdue: Send invoice, today at 9 AM. You have 1 task due today: Follow-up at 4:30 PM.",
            res.spoken
        )
    }

    @Test
    fun queryToday_whenNothingDue() {
        val r = rig(emptyList())
        assertEquals("Nothing is due today.", r.run(TaskCommand.QueryTasks(QueryScope.TODAY)).spoken)
    }

    @Test
    fun queryAllOpen_includesNoDeadlineTasks() {
        val r = rig(listOf(Task(title = "Someday", createdAt = 0)))
        assertEquals("You have 1 open task: Someday, no deadline.", r.run(TaskCommand.QueryTasks(QueryScope.ALL_OPEN)).spoken)
    }

    @Test
    fun briefing_greetsByTimeOfDay_andSumsWorkload() {
        val r = rig()
        assertEquals(
            "Good afternoon. You have 2 tasks due today: Follow-up at 4:30 PM and Client Deck at 5:20 PM. " +
                "That is about 1 hour 30 minutes of work. What would you like to do?",
            r.engine.briefing()
        )
        assertTrue(rig(clock = clockAt(8)).engine.briefing().startsWith("Good morning."))
        assertTrue(rig(clock = clockAt(19)).engine.briefing().startsWith("Good evening."))
    }

    // ---- replanning ----------------------------------------------------------------------

    @Test
    fun replanAfternoon_reportsExactShortfall() {
        val r = rig(clock = clockAt(16))
        val res = r.run(TaskCommand.Replan(PlanScope.AFTERNOON))
        val plan = res.plan!!
        assertEquals(listOf("Follow-up", "Client Deck"), plan.blocks.map { it.title })
        assertEquals(today(16), plan.blocks[0].start)
        assertEquals(today(16, 30), plan.blocks[1].start)
        val c = plan.conflicts.single()
        assertEquals(ConflictKind.LATE, c.kind)
        assertEquals(10, c.minutes)
        assertEquals(
            "Here is your afternoon plan. 4 PM, Follow-up, 30 minutes. 4:30 PM, Client Deck, 1 hour. " +
                "Conflict: Client Deck finishes 10 minutes after its deadline.",
            res.spoken
        )
    }

    @Test
    fun replan_doesNotModifyTasks() {
        val r = rig(clock = clockAt(16))
        val before = r.store.all()
        r.run(TaskCommand.Replan(PlanScope.DAY))
        assertEquals(before, r.store.all())
    }

    @Test
    fun replan_includesOnlyTasksDueByThePlanDay() {
        val r = rig(clock = clockAt(10))
        val plan = r.run(TaskCommand.Replan(PlanScope.DAY)).plan!!
        assertEquals(setOf("Follow-up", "Client Deck"), plan.blocks.map { it.title }.toSet())
    }

    @Test
    fun replan_afterWorkingHours_plansTomorrow_andSaysSo() {
        val r = rig(clock = clockAt(18, 30))
        val res = r.run(TaskCommand.Replan(PlanScope.DAY))
        assertEquals(TOMORROW, res.plan!!.date)
        assertTrue(res.spoken.startsWith("Here is your day plan for tomorrow."))
        assertEquals(millis(TOMORROW, 9), res.plan!!.blocks.first().start)
    }

    @Test
    fun replan_withNothingToDo() {
        val r = rig(emptyList())
        assertEquals("There is nothing to schedule in your day.", r.run(TaskCommand.Replan(PlanScope.DAY)).spoken)
    }

    // ---- misc ----------------------------------------------------------------------------

    @Test
    fun endCall_andUnsupported() {
        val r = rig()
        assertEquals(Outcome.END_CALL, r.run(TaskCommand.EndCall).outcome)
        val u = r.run(TaskCommand.Unsupported("sing me a song"))
        assertEquals(Outcome.UNSUPPORTED, u.outcome)
        assertEquals(4, r.store.all().size)
    }

    @Test
    fun timeFormat_isSpeakable() {
        val f = TimeFormat(clockAt(15))
        assertEquals("12 AM", f.clockTime(LocalTime.of(0, 0)))
        assertEquals("12 PM", f.clockTime(LocalTime.NOON))
        assertEquals("1:05 PM", f.clockTime(LocalTime.of(13, 5)))
        assertEquals("9:30 AM", f.clockTime(LocalTime.of(9, 30)))
        assertEquals("today", f.day(TODAY))
        assertEquals("Sunday", f.day(TODAY.plusDays(2)))
        assertEquals("Oct 3", f.day(TODAY.plusDays(8)))
        assertEquals("yesterday", f.day(TODAY.minusDays(1)))
        assertEquals("2 hours", f.duration(120))
        assertEquals("45 minutes", f.duration(45))
    }
}
