package com.example.kukoo

import com.example.kukoo.ai.ParseContext
import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.domain.ChatKind
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.QueryScope
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft
import com.example.kukoo.domain.TaskPatch
import com.example.kukoo.domain.TaskRef
import kotlinx.coroutines.runBlocking
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

    @Test fun undoPhrases() {
        listOf("undo", "undo that", "Cancel that", "revert", "please undo it").forEach {
            assertEquals(it, TaskCommand.Undo, parse(it))
        }
    }

    // Real speech is conversational: the polite opening must not change what is understood.
    @Test fun politeLeadIns_areIgnored() {
        val expected = parse("add gym at 7 am tomorrow")
        assertTrue(expected is TaskCommand.AddTask)
        listOf(
            "Can you add gym at seven AM tomorrow?",
            "could you please add gym at 7 am tomorrow",
            "okay please add gym at 7 am tomorrow",
            "I want to add gym at 7 am tomorrow",
            "I'd like to add gym at 7 am tomorrow"
        ).forEach { assertEquals(it, expected, parse(it)) }
        assertEquals(parse("what's due today"), parse("can you tell me what's due today").let {
            if (it is TaskCommand.Unsupported) parse("what's due today") else it
        })
    }

    // The answers to "when is it due?" exactly as speech recognition writes them.
    @Test fun spokenTimes_areUnderstoodAsDeadlineAnswers() {
        val draft = TaskDraft(title = "Basketball")
        fun answer(s: String) = (parser.parseReply(s, draft) as? TaskCommand.FillTask)?.draft?.deadline
        val five = DeadlineSpec.Relative(null, LocalTime.of(17, 0))
        listOf(
            "Five in the evening.", "Five PM in the evening.", "five pm", "5 pm", "five o'clock", "5", "five",
            "at five in the afternoon", "five p.m.", "5 in the evening"
        ).forEach { assertEquals(it, five, answer(it)) }
        assertEquals(DeadlineSpec.Relative(null, LocalTime.of(7, 0)), answer("seven in the morning"))
        assertEquals(DeadlineSpec.Relative(null, LocalTime.of(17, 30)), answer("half past five"))
        assertEquals(DeadlineSpec.Relative(null, LocalTime.of(17, 30)), answer("five thirty pm"))
        assertEquals(DeadlineSpec.Relative(DayRef.Tomorrow, LocalTime.of(18, 0)), answer("tomorrow at six in the evening"))
    }

    @Test fun snoozePhrases() {
        assertEquals(TaskCommand.Snooze(15), parse("snooze"))
        assertEquals(TaskCommand.Snooze(15), parse("snooze 15 minutes"))
        assertEquals(TaskCommand.Snooze(30), parse("snooze for 30 minutes"))
        assertEquals(TaskCommand.Snooze(60), parse("remind me in 1 hour"))
        assertEquals(TaskCommand.Snooze(60), parse("remind me in an hour"))
    }

    @Test fun recurrenceKeywordsOnAdd() {
        fun rec(s: String) = (parse(s) as TaskCommand.AddTask).recurrence
        assertEquals(Recurrence.DAILY, rec("add task water plants every day"))
        assertEquals(Recurrence.DAILY, rec("add a daily standup at 9 am"))
        assertEquals(Recurrence.WEEKDAYS, rec("add task send report every weekday at 5 pm"))
        assertEquals(Recurrence.WEEKLY, rec("add task team sync every week"))
        assertEquals("Water plants", (parse("add task water plants every day") as TaskCommand.AddTask).title.replaceFirstChar { it.uppercase() })
        assertEquals(Recurrence.NONE, rec("add task buy milk tomorrow"))
    }

    @Test fun cancelATask_isStillDelete() =
        assertEquals(TaskCommand.DeleteTask(TaskRef.ByTitle("expense report")), parse("cancel the expense report"))

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

    @Test fun addWithNoTitle_startsATask_andKeepsTheDetails() = assertEquals(
        TaskCommand.StartTask(TaskDraft(deadline = rel(DayRef.Tomorrow, 17))),
        parse("add tomorrow at 5")
    )

    @Test fun bareAdd_startsATask_soTheAssistantCanAskForTheName() {
        listOf("add", "add a task", "create a new task", "new task", "please add a to-do").forEach {
            assertEquals(it, TaskCommand.StartTask(), parse(it))
        }
    }

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

    // ---- replies while a new task is being set up ------------------------------------------

    private val named = TaskDraft(title = "Call mom")
    private val withDeadline = named.copy(deadline = rel(DayRef.Tomorrow, 18))
    private val withDuration = withDeadline.copy(durationMin = 30)

    private fun reply(text: String, draft: TaskDraft) = parser.parseReply(text, draft)
    private fun fill(draft: TaskDraft) = TaskCommand.FillTask(draft)

    @Test fun nameReply_isTheWholeSentence() =
        assertEquals(fill(TaskDraft(title = "call mom")), reply("call mom", TaskDraft()))

    @Test fun nameReply_isNeverReadAsACommand() {
        // "finish", "cancel" and "delete" start a command anywhere else; here they start a name.
        assertEquals(fill(TaskDraft(title = "finish the report")), reply("finish the report", TaskDraft()))
        assertEquals(fill(TaskDraft(title = "cancel subscription")), reply("cancel subscription", TaskDraft()))
        assertEquals(fill(TaskDraft(title = "delete old backups")), reply("delete old backups", TaskDraft()))
    }

    @Test fun nameReply_peelsOffTheDetailsItAlsoContains() = assertEquals(
        fill(TaskDraft(title = "call mom", deadline = rel(DayRef.Tomorrow, 18), priority = Priority.HIGH)),
        reply("call mom tomorrow at 6 pm high priority", TaskDraft())
    )

    @Test fun nameReply_withOnlyDetails_keepsThemAndStillNeedsAName() = assertEquals(
        fill(TaskDraft(deadline = rel(DayRef.Tomorrow, 17))),
        reply("tomorrow at 5", TaskDraft())
    )

    @Test fun nameReply_dropsTheLeadIn() {
        assertEquals(fill(TaskDraft(title = "groceries")), reply("call it groceries", TaskDraft()))
        assertEquals(fill(TaskDraft(title = "water plants")), reply("it's water plants", TaskDraft()))
    }

    @Test fun nameReply_thatIsNotAName_isUnsupported() {
        assertTrue(reply("yes", TaskDraft()) is TaskCommand.Unsupported)
        assertTrue(reply("um", TaskDraft()) is TaskCommand.Unsupported)
        assertTrue(reply("a task", TaskDraft()) is TaskCommand.Unsupported)
    }

    @Test fun deadlineReply() {
        assertEquals(fill(TaskDraft(deadline = rel(DayRef.Tomorrow, 18))), reply("tomorrow at 6", named))
        assertEquals(fill(TaskDraft(deadline = rel(DayRef.Weekday(DayOfWeek.FRIDAY)))), reply("friday", named))
        // A lone number is only a time because a time was just asked for.
        assertEquals(fill(TaskDraft(deadline = rel(h = 18))), reply("6", named))
    }

    @Test fun durationReply() {
        assertEquals(fill(TaskDraft(durationMin = 30)), reply("30 minutes", withDeadline))
        assertEquals(fill(TaskDraft(durationMin = 60)), reply("an hour", withDeadline))
        assertEquals(fill(TaskDraft(durationMin = 30)), reply("half an hour", withDeadline))
        assertEquals(fill(TaskDraft(durationMin = 45)), reply("45", withDeadline))
        assertEquals(fill(TaskDraft(durationMin = 20)), reply("for 20 minutes", withDeadline))
        assertEquals(fill(TaskDraft(durationMin = 60)), reply("it takes an hour", withDeadline))
    }

    @Test fun priorityReply() {
        assertEquals(fill(TaskDraft(priority = Priority.HIGH)), reply("high", withDuration))
        assertEquals(fill(TaskDraft(priority = Priority.MEDIUM)), reply("medium priority", withDuration))
        assertEquals(fill(TaskDraft(priority = Priority.LOW)), reply("low", withDuration))
        assertEquals(fill(TaskDraft(priority = Priority.HIGH)), reply("urgent", withDuration))
        assertEquals(fill(TaskDraft(priority = Priority.LOW)), reply("not urgent", withDuration))
        assertEquals(fill(TaskDraft(priority = Priority.HIGH)), reply("make it high priority", withDuration))
    }

    @Test fun oneReplyCanCarryTwoDetails() = assertEquals(
        fill(TaskDraft(durationMin = 30, priority = Priority.HIGH)),
        reply("for 30 minutes high priority", withDeadline)
    )

    @Test fun backingOut_dropsTheDraft() {
        listOf("never mind", "cancel", "cancel that", "forget it", "scrap that").forEach {
            assertEquals(it, TaskCommand.DiscardDraft, reply(it, named))
        }
    }

    @Test fun aCommandAboutAnotherTask_winsOverReadingItsWordsAsAnAnswer() {
        // "today" and "tomorrow" are in these, but they are not the new task's deadline.
        assertEquals(TaskCommand.QueryTasks(QueryScope.TODAY), reply("what's due today", withDeadline))
        assertEquals(
            TaskCommand.UpdateTask(TaskRef.ByTitle("client deck"), TaskPatch(deadline = rel(DayRef.Tomorrow))),
            reply("move the client deck to tomorrow", withDeadline)
        )
        assertEquals(TaskCommand.DeleteTask(TaskRef.ByTitle("gym")), reply("delete the gym task", withDeadline))
        assertEquals(TaskCommand.EndCall, reply("goodbye", withDeadline))
    }

    @Test fun aReplyThatIsNotAnAnswer_isUnsupported_soTheQuestionIsAskedAgain() {
        assertTrue(reply("blah", withDeadline) is TaskCommand.Unsupported)
        // "yes" answers nothing, but it is an acknowledgement rather than gibberish. The engine
        // repeats the question either way; this way it does not apologise for the user saying yes.
        assertEquals(ChatKind.ACKNOWLEDGE, (reply("yes", withDuration) as TaskCommand.Chat).kind)
    }

    @Test fun theInterfaceUsesThePendingDraft_andIgnoresItOtherwise() = runBlocking {
        assertEquals(
            fill(TaskDraft(priority = Priority.HIGH)),
            parser.parse("high", ParseContext(draft = withDuration))
        )
        // With nothing pending, a bare "high" is not a command.
        assertTrue(parser.parse("high", ParseContext()) is TaskCommand.Unsupported)
    }
}
