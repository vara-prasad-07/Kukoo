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
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskEngine
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TimeFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun add_deadlineInThePast_isRejected_andNothingIsSaved() {
        val r = rig(emptyList())
        val res = r.run(TaskCommand.AddTask("call mom", DeadlineSpec.Relative(DayRef.Today, LocalTime.of(9, 0))))
        assertEquals(Outcome.REJECTED, res.outcome)
        assertEquals("today at 9 AM has already passed, so I didn't add Call mom.", res.spoken)
        assertTrue(r.store.all().isEmpty())
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
    fun update_rejectsPastDeadline_keepsOldValue() {
        val r = rig()
        val res = r.run(TaskCommand.UpdateTask(title("follow up"), TaskPatch(deadline = DeadlineSpec.Relative(DayRef.Today, LocalTime.of(9, 0)))))
        assertEquals(Outcome.REJECTED, res.outcome)
        assertEquals(today(16, 30), r.byTitle("Follow-up").deadline)
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
