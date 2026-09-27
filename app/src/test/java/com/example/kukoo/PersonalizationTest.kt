package com.example.kukoo

import com.example.kukoo.data.DemoHistory
import com.example.kukoo.domain.ConflictChoice
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.Goal
import com.example.kukoo.domain.HistoryEntry
import com.example.kukoo.domain.LocalReplanner
import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.ProfileStore
import com.example.kukoo.domain.QuestionKind
import com.example.kukoo.domain.Recommender
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskCommand
import com.example.kukoo.domain.TaskEngine
import com.example.kukoo.domain.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime

private class FakeProfile : ProfileStore {
    private var goalRows = listOf<Goal>()
    private val rows = mutableListOf<HistoryEntry>()
    override fun goals() = goalRows
    override fun replaceGoals(goals: List<Goal>) { goalRows = goals.mapIndexed { i, g -> g.copy(id = i + 1L) } }
    override fun history(): List<HistoryEntry> = rows.toList()
    override fun addHistory(entries: List<HistoryEntry>) { rows += entries }
    override fun clearHistory() { rows.clear() }
}

private fun localTime(millis: Long): LocalTime = Instant.ofEpochMilli(millis).atZone(TEST_ZONE).toLocalTime()

/**
 * Personalised suggestions: "add one extra task for an hour based on my goals". The past is the two-week demo
 * history (the same rows the app loads into its database), so these are the scenarios shown to a reviewer.
 * Friday 2026-09-25; each test picks the time of day.
 */
class PersonalizationTest {

    private class Rig(val store: FakeStore, val profile: FakeProfile, val engine: TaskEngine, val clock: Clock)

    private fun rig(hour: Int, minute: Int = 0, tasks: List<Task> = emptyList(), history: Boolean = true): Rig {
        val clock = clockAt(hour, minute)
        val store = FakeStore(tasks)
        val profile = FakeProfile()
        if (history) DemoHistory.load(profile, clock)
        return Rig(store, profile, TaskEngine(store, LocalReplanner(), clock, profile = profile), clock)
    }

    private fun Rig.say(command: TaskCommand) = runBlocking { engine.executeSpoken(command) }
    private fun Rig.yes() = say(TaskCommand.Resolve(ConflictChoice.ACCEPT))
    private fun Rig.another() = say(TaskCommand.SuggestTask())
    private fun Rig.rank(day: Int = 0, minutes: Int? = null) =
        Recommender(clock).rank(store.all(), profile, TODAY.plusDays(day.toLong()), minutes)

    // ---- the story the history tells ----------------------------------------------------------

    @Test
    fun theDemoHistory_isTwoWeeksOfHabits_withOneOffsThatAreNotHabits() {
        val r = rig(10)
        val habits = r.engine.habits().associateBy { it.title }
        val gate = habits.getValue("GATE Preparation")
        assertEquals(7, gate.doneDays7)
        assertEquals(14, gate.streak)
        assertEquals(6, habits.getValue("DSA Practice").doneDays7)
        assertTrue(habits.getValue("Gym").completionRate < habits.getValue("DSA Practice").completionRate)
        assertEquals(1, habits.getValue("Doctor appointment").doneCount)
        assertEquals("Crack GATE 2027", gate.goal?.name)
        assertNull(habits.getValue("Doctor appointment").goal)
    }

    // ---- scenario 1: it knows what you usually do, and when ------------------------------------

    @Test
    fun inTheMorning_itSuggestsGatePrep_atItsUsualTime() {
        val r = rig(8)
        val best = r.rank(minutes = 60).first()
        assertEquals("GATE Preparation", best.title)
        assertEquals(60, best.durationMin)
        assertEquals(Priority.HIGH, best.priority)
        assertTrue(localTime(best.start) in LocalTime.of(8, 30)..LocalTime.of(9, 30))
    }

    @Test
    fun inTheAfternoon_itSuggestsDsaPractice_forTheEveningSlotItIsUsuallyDoneIn() {
        val r = rig(16)
        val res = r.say(TaskCommand.SuggestTask(durationMin = 60))
        assertEquals(Outcome.NEEDS_INFO, res.outcome)
        val text = res.spoken
        assertTrue(text, text.startsWith("Based on your routine, I'd add DSA Practice today, "))
        assertTrue(text, text.contains("1 hour, high priority"))
        assertTrue(text, text.contains("You did it on 6 of the last 7 days"))
        assertTrue(text, text.contains("it supports your Crack GATE 2027 goal"))
        assertTrue(text, text.contains("you usually do it around 7 PM"))
        assertTrue(text, text.endsWith("Should I add it, or would you like something else?"))
        // It only asked: nothing is written yet.
        assertTrue(r.store.all().isEmpty())
    }

    @Test
    fun aOneOffChore_isNeverRecommended() {
        val titles = rig(10).rank().map { it.title }
        assertFalse(titles.contains("Doctor appointment"))
        assertFalse(titles.contains("Pay electricity bill"))
    }

    // ---- scenario 2: yes adds it, with a reminder call; something else moves on ----------------

    @Test
    fun yes_addsTheTask_atTheOfferedTime_withAReminderCall_andUndoRemovesIt() {
        val r = rig(16)
        r.say(TaskCommand.SuggestTask(durationMin = 60))
        val res = r.yes()
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.changedTasks)
        val added = r.store.all().single()
        assertEquals("DSA Practice", added.title)
        assertEquals(60, added.durationMin)
        assertEquals(Priority.HIGH, added.priority)
        assertEquals(TaskStatus.OPEN, added.status)
        assertTrue(localTime(added.deadline!!) in LocalTime.of(18, 30)..LocalTime.of(19, 30))
        assertNotNull(added.reminderMin)
        assertTrue(res.spoken, res.spoken.startsWith("Done. I added DSA Practice for today, "))

        assertEquals("Undid last action.", runBlocking { engine(r).execute(TaskCommand.Undo) }.spoken)
        assertTrue(r.store.all().isEmpty())
    }

    private fun engine(r: Rig) = r.engine

    @Test
    fun somethingElse_neverRepeatsASuggestion_andEventuallyRunsOut() {
        val r = rig(16)
        val seen = mutableListOf<String>()
        var res = r.say(TaskCommand.SuggestTask(durationMin = 30))
        repeat(20) {
            if (!res.spoken.startsWith("Based on your routine")) return@repeat
            seen += res.spoken.substringAfter("I'd add ").substringBefore(" today")
            res = r.another()
        }
        assertEquals(seen.toSet().size, seen.size)
        assertTrue(seen.size >= 3)
        assertTrue(res.spoken, res.spoken.contains("That's everything I'd suggest"))
    }

    @Test
    fun no_isTheSameAsSomethingElse() {
        val r = rig(16)
        val first = r.say(TaskCommand.SuggestTask(durationMin = 60)).spoken
        val second = r.say(TaskCommand.Resolve(ConflictChoice.DECLINE)).spoken
        assertTrue(second, second.startsWith("Okay. Based on your routine"))
        assertFalse(first.substringAfter("I'd add ").substringBefore(" today") ==
            second.substringAfter("I'd add ").substringBefore(" today"))
    }

    @Test
    fun neverMind_endsIt_andAddsNothing() {
        val r = rig(16)
        r.say(TaskCommand.SuggestTask())
        val res = r.say(TaskCommand.Resolve(ConflictChoice.KEEP))
        assertEquals("Okay, I won't add anything.", res.spoken)
        assertTrue(r.store.all().isEmpty())
        assertNull(r.engine.pendingConflict)
    }

    @Test
    fun aShorterLength_keepsTheSameActivity() {
        val r = rig(16)
        r.say(TaskCommand.SuggestTask(durationMin = 60))
        val res = r.say(TaskCommand.SuggestTask(durationMin = 30))
        assertTrue(res.spoken, res.spoken.startsWith("Okay, DSA Practice for 30 minutes"))
        r.yes()
        assertEquals(30, r.store.all().single().durationMin)
    }

    @Test
    fun whileASuggestionIsOpen_theParserIsToldWhatWasAsked() {
        val r = rig(16)
        r.say(TaskCommand.SuggestTask())
        assertEquals(QuestionKind.SUGGESTION, r.engine.pendingConflict?.kind)
    }

    // ---- scenario 3: it notices what today already holds ----------------------------------------

    @Test
    fun anActivityAlreadyOnTodaysList_isNotSuggestedAgain() {
        val r = rig(16)
        r.say(TaskCommand.SuggestTask(durationMin = 60))
        r.yes()
        val titles = r.rank(minutes = 60).map { it.title }
        assertFalse(titles.toString(), titles.contains("DSA Practice"))
        assertTrue(titles.isNotEmpty())
    }

    @Test
    fun afterAHeavyStudyDay_itSteersAwayFromTheGoalThatAlreadyGotItsShare() {
        // 4 hours of GATE study done this morning is the whole daily target for "Crack GATE 2027".
        val done = Task(
            title = "GATE Preparation", deadline = today(9), durationMin = 240, priority = Priority.HIGH,
            status = TaskStatus.DONE, createdAt = today(8), completedAt = today(13)
        )
        val r = rig(15, tasks = listOf(done))
        val ranked = r.rank(minutes = 60)
        assertEquals(ranked.toString(), "Gym", ranked.first().title)
        val dsa = ranked.first { it.title == "DSA Practice" }
        assertTrue("DSA is pushed down by the saturated goal", dsa.score < ranked.first().score)
    }

    @Test
    fun itPlansAroundWhatIsAlreadyBooked() {
        val busy = Task(title = "Client Deck", deadline = today(18, 30), durationMin = 120, createdAt = today(9))
        val r = rig(16, tasks = listOf(busy))
        val dsa = r.rank(minutes = 60).first { it.title == "DSA Practice" }
        // 6:30 to 8:30 PM is taken: it goes in the nearest free time either side, never on top of the deck.
        assertTrue(dsa.end <= today(18, 30) || dsa.start >= today(20, 30))
    }

    @Test
    fun aGoalNothingServes_comesInLast_asAStarter() {
        val ranked = rig(10).rank(minutes = 45)
        val starter = ranked.first { it.title == "Side project work" }
        assertTrue(starter.evidence.fromGoalOnly)
        // Below every real routine (something done on 2+ of the last 7 days), even if above a stray one-time interest.
        val routines = ranked.filter { !it.evidence.fromGoalOnly && it.evidence.doneDays7 >= 2 }
        assertTrue(routines.isNotEmpty())
        assertTrue(routines.all { it.score > starter.score })
    }

    // ---- honesty and edges ------------------------------------------------------------------------

    @Test
    fun withNoHistoryAndNoGoals_itSaysSo_insteadOfInventingSomething() {
        val res = rig(10, history = false).say(TaskCommand.SuggestTask())
        assertEquals(Outcome.OK, res.outcome)
        assertTrue(res.spoken, res.spoken.startsWith("I don't know your routine yet"))
    }

    @Test
    fun tasksMarkedDoneInTheApp_countAsHistoryToo() {
        val done = (1..5).map { d ->
            Task(
                title = "Meditation", deadline = millis(TODAY.minusDays(d.toLong()), 7), durationMin = 15,
                status = TaskStatus.DONE, createdAt = 1, completedAt = millis(TODAY.minusDays(d.toLong()), 7, 20)
            )
        }
        val r = rig(9, tasks = done, history = false)
        val habit = r.engine.habits().single()
        assertEquals("Meditation", habit.title)
        assertEquals(5, habit.doneDays7)
        assertEquals(LocalTime.of(7, 0), habit.usualStart)
        // Without any goals it still recommends what the user really does.
        assertEquals("Meditation", r.rank().first().title)
    }

    @Test
    fun lateInTheEvening_itLooksAtTomorrow_andSaysWhy() {
        val res = rig(21, 30).say(TaskCommand.SuggestTask())
        assertTrue(res.spoken, res.spoken.startsWith("It's too late to fit anything more into today, so I looked at tomorrow. "))
        assertTrue(res.spoken, res.spoken.contains(" tomorrow, "))
    }

    @Test
    fun forTomorrow_theSlotIsTheHabitsUsualTime() {
        val r = rig(16)
        val best = r.rank(day = 1, minutes = 60).first { it.title == "GATE Preparation" }
        assertEquals(TODAY.plusDays(1), Instant.ofEpochMilli(best.start).atZone(TEST_ZONE).toLocalDate())
        assertTrue(localTime(best.start) in LocalTime.of(8, 30)..LocalTime.of(9, 30))
    }

    @Test
    fun aLengthThatCannotFit_isSaidPlainly() {
        val busy = Task(title = "Conference", deadline = today(15), durationMin = 7 * 60, createdAt = today(9))
        val res = rig(15, tasks = listOf(busy)).say(TaskCommand.SuggestTask(durationMin = 120, day = DayRef.Today))
        assertTrue(res.spoken, res.spoken.contains("I couldn't find room for 2 hours today"))
    }

    @Test
    fun theAnswerIsDeterministic() {
        val a = rig(14).rank(minutes = 60).map { it.title to it.start }
        val b = rig(14).rank(minutes = 60).map { it.title to it.start }
        assertEquals(a, b)
    }
}
