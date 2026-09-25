package com.example.kukoo

import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class RuleBasedIntentParserTest {
    private val parser = RuleBasedIntentParser()
    private fun parse(s: String) = parser.parseNow(s)
    private fun rel(day: DayRef? = null, h: Int? = null, m: Int = 0) =
        DeadlineSpec.Relative(day, h?.let { LocalTime.of(it, m) })

    // The seven commands from the implementation document, verbatim.

    @Test fun whatsDueToday() =
        assertEquals(TaskCommand.QueryTasks(QueryScope.TODAY), parse("What's due today?"))

    @Test fun addTaskWithDayAndTime() = assertEquals(
        TaskCommand.AddTask("finish the report", rel(DayRef.Tomorrow, 17)),
        parse("Add a task: finish the report tomorrow at 5.")
    )

    @Test fun moveToTomorrow() = assertEquals(
        TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(deadline = rel(DayRef.Tomorrow))),
        parse("Move the client deck to tomorrow.")
    )

    @Test fun markDone() = assertEquals(
        TaskCommand.CompleteTask(TaskRef.ByTitle("follow-up")),
        parse("Mark the follow-up as done.")
    )

    @Test fun deleteTask() = assertEquals(
        TaskCommand.DeleteTask(TaskRef.ByTitle("expense")),
        parse("Delete the expense task.")
    )

    @Test fun changeDeadlineUsesLastTask() = assertEquals(
        TaskCommand.UpdateTask(TaskRef.Last, TaskPatch(deadline = rel(h = 18))),
        parse("Change the deadline to 6 PM.")
    )

    @Test fun replanAfternoon() =
        assertEquals(TaskCommand.Replan(PlanScope.AFTERNOON), parse("Replan my afternoon."))

    // Variations a speech recogniser or a person would produce.

    @Test fun replanDay() {
        assertEquals(TaskCommand.Replan(PlanScope.DAY), parse("replan my day"))
        assertEquals(TaskCommand.Replan(PlanScope.DAY), parse("Can you plan the day please"))
        assertEquals(TaskCommand.Replan(PlanScope.DAY), parse("replan"))
    }

    @Test fun addingATaskAboutPlanning_isNotAReplan() = assertEquals(
        TaskCommand.AddTask("plan my day"),
        parse("add a task to plan my day")
    )

    @Test fun addWithDurationAndPriority() = assertEquals(
        TaskCommand.AddTask("buy milk", null, 20, Priority.HIGH),
        parse("add buy milk for 20 minutes high priority")
    )

    @Test fun addWithSpelledOutHour() = assertEquals(
        TaskCommand.AddTask("call mom", rel(h = 17)),
        parse("remind me to call mom at five")
    )

    @Test fun addWithWeekdayAndMeridiem() = assertEquals(
        TaskCommand.AddTask("call dentist", rel(DayRef.Weekday(DayOfWeek.MONDAY), 9)),
        parse("add call dentist on monday at 9 am")
    )

    @Test fun addWithMinutesAndDotsInMeridiem() = assertEquals(
        TaskCommand.AddTask("send invoice", rel(DayRef.Today, 15, 30)),
        parse("Add task: send invoice today at 3:30 p.m.")
    )

    @Test fun addWithHalfHour() = assertEquals(
        TaskCommand.AddTask("stretch", null, 30),
        parse("add stretch for half an hour")
    )

    @Test fun addDoesNotSwallowWordsThatStartLikeTask() = assertEquals(
        TaskCommand.AddTask("taskbar redesign"),
        parse("add taskbar redesign")
    )

    @Test fun addWithNoTitle_isUnsupported() =
        assertTrue(parse("add tomorrow at 5") is TaskCommand.Unsupported)

    @Test fun morningHoursDefaultToAm_afternoonHoursToPm() {
        assertEquals(rel(DayRef.Tomorrow, 9), (parse("add standup tomorrow at 9") as TaskCommand.AddTask).deadline)
        assertEquals(rel(DayRef.Tomorrow, 12), (parse("add lunch tomorrow at 12") as TaskCommand.AddTask).deadline)
        assertEquals(rel(DayRef.Tomorrow, 14), (parse("add sync tomorrow at 2") as TaskCommand.AddTask).deadline)
        assertEquals(rel(DayRef.Tomorrow, 12), (parse("add lunch tomorrow at noon") as TaskCommand.AddTask).deadline)
    }

    @Test fun moveToTimeOnly() = assertEquals(
        TaskCommand.UpdateTask(TaskRef.ByTitle("deck"), TaskPatch(deadline = rel(h = 17, m = 30))),
        parse("move the deck to 5:30 pm")
    )

    @Test fun moveToWeekday() = assertEquals(
        TaskCommand.UpdateTask(
            TaskRef.ByTitle("client deck"),
            TaskPatch(deadline = rel(DayRef.Weekday(DayOfWeek.FRIDAY), 10))
        ),
        parse("reschedule client deck to friday at 10 am")
    )

    @Test fun moveWithoutAnyTime_isUnsupported() =
        assertTrue(parse("move the deck to the moon") is TaskCommand.Unsupported)

    @Test fun changeDeadlineNamingTheTask() {
        val expected = TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(deadline = rel(h = 18)))
        assertEquals(expected, parse("change the deadline of the client deck to 6 pm"))
        assertEquals(expected, parse("change the client deck deadline to 6 pm"))
    }

    @Test fun completeVariants() {
        val expected = TaskCommand.CompleteTask(TaskRef.ByTitle("follow up"))
        assertEquals(expected, parse("finished the follow up"))
        assertEquals(expected, parse("I'm done with the follow up"))
        assertEquals(expected, parse("complete follow up"))
        assertEquals(expected, parse("mark follow up complete"))
    }

    @Test fun reopen() = assertEquals(
        TaskCommand.ReopenTask(TaskRef.ByTitle("follow up")), parse("reopen the follow up")
    )

    @Test fun priorityAndDuration() {
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(priority = Priority.HIGH)),
            parse("make the client deck high priority")
        )
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(durationMin = 90)),
            parse("client deck takes 90 minutes")
        )
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(durationMin = 60)),
            parse("set the duration of the client deck to an hour")
        )
    }

    @Test fun queries() {
        assertEquals(TaskCommand.QueryTasks(QueryScope.TODAY), parse("what tasks do I have"))
        assertEquals(TaskCommand.QueryTasks(QueryScope.TODAY), parse("what is on my plate"))
        assertEquals(TaskCommand.QueryTasks(QueryScope.ALL_OPEN), parse("show all my tasks"))
    }

    @Test fun endCall() {
        assertEquals(TaskCommand.EndCall, parse("goodbye"))
        assertEquals(TaskCommand.EndCall, parse("That's all."))
        assertEquals(TaskCommand.EndCall, parse("end call"))
    }

    @Test fun unsupported() {
        assertTrue(parse("sing me a song") is TaskCommand.Unsupported)
        assertTrue(parse("") is TaskCommand.Unsupported)
        assertTrue(parse("   ") is TaskCommand.Unsupported)
    }

    @Test fun caseAndPunctuationDoNotMatter() = assertEquals(
        parse("mark the follow-up as done"), parse("  MARK THE FOLLOW-UP AS DONE!!  ")
    )
}
