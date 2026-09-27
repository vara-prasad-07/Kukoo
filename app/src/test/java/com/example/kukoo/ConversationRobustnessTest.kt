package com.example.kukoo

import com.example.kukoo.ai.RuleBasedIntentParser
import com.example.kukoo.ai.SpeechRepair
import com.example.kukoo.domain.ChatKind
import com.example.kukoo.domain.DeadlineSpec
import com.example.kukoo.domain.FuzzyText
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskDraft
import com.example.kukoo.domain.TitleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * Every case here is something a real recognizer produced on the test device and the assistant
 * then got wrong. They are kept verbatim so the same mistakes cannot come back.
 */
class ConversationRobustnessTest {
    private val parser = RuleBasedIntentParser()
    private fun parse(s: String) = parser.parseNow(s)

    // ---- what the recognizer actually hears ---------------------------------------------

    @Test fun anHour_survivesEveryMishearing() {
        // A deadline is already set, so the next question is "how long?" and the reply is a duration.
        val draft = TaskDraft(title = "Gym", deadline = at5pm, durationMin = null)
        listOf("an hour", "Honor.", "on her", "An our", "and our").forEach { heard ->
            val command = parser.parseReply(heard, draft)
            assertEquals(heard, 60, (command as? TaskCommand.FillTask)?.draft?.durationMin)
        }
    }

    @Test fun highPriority_survivesTheHiMishearing() {
        // Name, deadline and duration known, so the next question is "how important?".
        val draft = TaskDraft(title = "Gym", deadline = at5pm, durationMin = 60)
        listOf("high priority", "Hi Priority.", "hi priority", "High.", "hi").forEach { heard ->
            val command = parser.parseReply(heard, draft)
            assertEquals(heard, Priority.HIGH, (command as? TaskCommand.FillTask)?.draft?.priority)
        }
    }

    @Test fun repairNeverRewritesWordsThatBelongInATaskTitle() {
        // "honor" is only "an hour" when answering a question, never inside a new task's name.
        assertTrue(SpeechRepair.repairCommand("add honor roll ceremony").contains("honor roll"))
        val added = parse("add honor roll ceremony at 5 pm") as TaskCommand.AddTask
        assertTrue(added.title, added.title.contains("honor", ignoreCase = true))
        // A bare greeting stays a greeting; only "hi priority" becomes a priority.
        assertEquals(ChatKind.GREETING, (parse("hi") as TaskCommand.Chat).kind)
    }

    // ---- misheard task names ------------------------------------------------------------

    @Test fun aMisheardTaskName_stillFindsTheRealTask() {
        val tasks = listOf(
            task(1, "Gym"),
            task(2, "Client Deck"),
            task(3, "Expense report")
        )
        fun found(query: String) = (TitleMatcher.resolve(query, tasks) as? TitleMatcher.Match.Found)?.task?.title
        assertEquals("Gym", found("gim"))
        assertEquals("Gym", found("jim"))
        assertEquals("Client Deck", found("client dec"))
        assertEquals("Expense report", found("expence report"))
        // Something that genuinely is not there must still not match.
        assertEquals(null, found("payroll"))
    }

    @Test fun fuzzyMatching_prefersTheExactTitle() {
        val tasks = listOf(task(1, "Gym"), task(2, "Jim's birthday"))
        val match = TitleMatcher.resolve("gym", tasks)
        assertEquals("Gym", (match as TitleMatcher.Match.Found).task.title)
    }

    @Test fun soundsAlikeScoring() {
        assertTrue(FuzzyText.similar("gym", "gim"))
        assertTrue(FuzzyText.similar("deck", "dec"))
        assertTrue("unrelated words must not match", !FuzzyText.similar("gym", "payroll"))
    }

    // ---- conversation, not commands -------------------------------------------------------

    @Test fun pleasantries_areAnsweredNotRejected() {
        assertEquals(ChatKind.THANKS, (parse("Yeah, thank you") as TaskCommand.Chat).kind)
        assertEquals(ChatKind.THANKS, (parse("thanks") as TaskCommand.Chat).kind)
        assertEquals(ChatKind.GREETING, (parse("hello") as TaskCommand.Chat).kind)
        assertEquals(ChatKind.ACKNOWLEDGE, (parse("okay") as TaskCommand.Chat).kind)
        assertEquals(ChatKind.HELP, (parse("what can you do") as TaskCommand.Chat).kind)
    }

    @Test fun aPleasantryNeverSwallowsARealCommand() {
        // "okay" in front of a command must not make the whole thing small talk.
        assertTrue(parse("okay move the client deck to five") is TaskCommand.UpdateTask)
        assertTrue(parse("yes delete the expense task") is TaskCommand.DeleteTask)
    }

    @Test fun changeWithoutAValue_asksInsteadOfInventingATask() {
        listOf("Can you modify the gym?", "change the gym", "edit my gym").forEach { heard ->
            assertTrue(heard, parse(heard) is TaskCommand.AskWhatToChange)
        }
    }

    @Test fun mortify_isReadAsModify_andMovesTheTask() {
        // Verbatim from the device: this previously created a task called "Mortify the gym".
        val command = parse("Can you mortify the gym to four PM?")
        assertTrue(command.toString(), command is TaskCommand.UpdateTask)
    }

    private val at5pm = DeadlineSpec.Relative(time = LocalTime.of(17, 0))

    private fun task(id: Long, title: String) = Task(id = id, title = title, createdAt = 0)
}
