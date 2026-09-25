package com.example.kukoo

import com.example.kukoo.domain.ConflictKind
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Planner
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val planner = Planner()

    private fun task(
        id: Long, title: String, minutes: Int, deadline: Long?,
        priority: Priority = Priority.MEDIUM, status: TaskStatus = TaskStatus.OPEN
    ) = Task(id, title, deadline, minutes, priority, status, createdAt = 0)

    private fun plan(
        tasks: List<Task>, from: Long, to: Long, now: Long, fixed: List<TimeRange> = emptyList()
    ) = planner.plan(tasks, TimeRange(from, to), PlanScope.AFTERNOON, TODAY, now, fixed)

    @Test
    fun documentExample_edfOrder_andExactDeadlineShortfall() {
        val tasks = listOf(
            task(1, "Design Review", 45, today(18, 30)),
            task(2, "Follow-up", 30, today(16, 30)),
            task(3, "Client Deck", 60, today(17, 20))
        )
        val p = plan(tasks, today(16), today(19), now = today(16))

        assertEquals(listOf("Follow-up", "Client Deck", "Design Review"), p.blocks.map { it.title })
        assertEquals(listOf(today(16), today(16, 30), today(17, 30)), p.blocks.map { it.start })
        assertEquals(today(18, 15), p.blocks.last().end)

        assertEquals(1, p.conflicts.size)
        val c = p.conflicts.single()
        assertEquals("Client Deck", c.title)
        assertEquals(ConflictKind.LATE, c.kind)
        assertEquals(10, c.minutes)
    }

    @Test
    fun noConflict_whenEverythingFits() {
        val p = plan(
            listOf(task(1, "A", 30, today(17)), task(2, "B", 30, today(18))),
            today(9), today(18), now = today(9)
        )
        assertTrue(p.conflicts.isEmpty())
        assertEquals(60, p.totalMinutes)
    }

    @Test
    fun tieOnDeadline_higherPriorityFirst_thenShorterFirst() {
        val d = today(17)
        val p = plan(
            listOf(
                task(1, "Low", 30, d, Priority.LOW),
                task(2, "HighLong", 60, d, Priority.HIGH),
                task(3, "HighShort", 20, d, Priority.HIGH)
            ),
            today(9), today(18), now = today(9)
        )
        assertEquals(listOf("HighShort", "HighLong", "Low"), p.blocks.map { it.title })
    }

    @Test
    fun tasksWithoutDeadline_goLast() {
        val p = plan(
            listOf(task(1, "Someday", 30, null), task(2, "Soon", 30, today(12))),
            today(9), today(18), now = today(9)
        )
        assertEquals(listOf("Soon", "Someday"), p.blocks.map { it.title })
    }

    @Test
    fun doneTasks_areIgnored() {
        val p = plan(
            listOf(task(1, "Done", 30, today(12), status = TaskStatus.DONE), task(2, "Open", 30, today(12))),
            today(9), today(18), now = today(9)
        )
        assertEquals(listOf("Open"), p.blocks.map { it.title })
    }

    @Test
    fun planStartsAtNextFiveMinuteSlot_neverInThePast() {
        val p = plan(listOf(task(1, "A", 30, today(17))), today(9), today(18), now = today(16, 2))
        assertEquals(today(16, 5), p.blocks.single().start)
    }

    @Test
    fun windowStartsLater_thanNow_usesWindowStart() {
        val p = plan(listOf(task(1, "A", 30, today(17))), today(12), today(18), now = today(9))
        assertEquals(today(12), p.blocks.single().start)
    }

    @Test
    fun taskSplitsAroundFixedBlock_inMeaningfulChunks() {
        val fixed = listOf(TimeRange(today(10), today(10, 30)))
        val p = plan(listOf(task(1, "Long", 90, today(12))), today(9), today(12), now = today(9), fixed = fixed)

        assertEquals(2, p.blocks.size)
        assertEquals(today(9) to today(10), p.blocks[0].start to p.blocks[0].end)
        assertEquals(today(10, 30) to today(11), p.blocks[1].start to p.blocks[1].end)
        assertEquals(listOf(1, 2), p.blocks.map { it.part })
        assertEquals(listOf(2, 2), p.blocks.map { it.partCount })
        assertTrue(p.conflicts.isEmpty())
    }

    @Test
    fun split_neverLeavesASliver_andFreeTimeStaysUsable() {
        // gap 9:00-9:50 (50 min), fixed 9:50-10:00, gap 10:00-12:00. Task needs 60.
        val fixed = listOf(TimeRange(today(9, 50), today(10)))
        val p = plan(listOf(task(1, "Work", 60, today(12))), today(9), today(12), now = today(9), fixed = fixed)

        // 50-min gap: taking 50 would leave a 10-min tail, so it takes 35 and leaves a 25-min tail.
        assertEquals(35, p.blocks[0].minutes)
        assertEquals(25, p.blocks[1].minutes)
        assertEquals(today(10), p.blocks[1].start)
    }

    @Test
    fun smallTask_isNeverSplit() {
        // 30-min task, gaps of 20 and 60: must go whole into the 60 gap.
        val fixed = listOf(TimeRange(today(9, 20), today(10)))
        val p = plan(listOf(task(1, "Short", 30, today(12))), today(9), today(11), now = today(9), fixed = fixed)
        assertEquals(1, p.blocks.size)
        assertEquals(today(10), p.blocks.single().start)
    }

    @Test
    fun overflow_placesWhatFits_andReportsExactUnplacedMinutes() {
        val p = plan(
            listOf(task(1, "A", 60, today(17)), task(2, "B", 60, today(18))),
            today(16), today(17, 30), now = today(16)
        )
        val c = p.conflicts.single { it.kind == ConflictKind.NO_ROOM }
        assertEquals("B", c.title)
        assertEquals(30, c.minutes) // 30 of B's 60 minutes fit after A
        assertEquals(listOf("A", "B"), p.blocks.map { it.title })
        assertEquals(30, p.blocks.last().minutes)
    }

    @Test
    fun overflow_gapTooSmallToBeUseful_placesNothing() {
        val p = plan(
            listOf(task(1, "A", 60, today(17)), task(2, "B", 60, today(18))),
            today(16), today(17, 10), now = today(16)
        )
        val c = p.conflicts.single { it.kind == ConflictKind.NO_ROOM }
        assertEquals(60, c.minutes)
        assertEquals(listOf("A"), p.blocks.map { it.title })
    }

    @Test
    fun emptyFreeTime_whenNowIsAfterWindow() {
        val p = plan(listOf(task(1, "A", 30, today(17))), today(9), today(18), now = today(18, 30))
        assertTrue(p.blocks.isEmpty())
        assertEquals(ConflictKind.NO_ROOM, p.conflicts.single().kind)
        assertEquals(30, p.conflicts.single().minutes)
    }

    @Test
    fun overdueTask_isScheduledFirst_andReportedLate() {
        val p = plan(
            listOf(task(1, "Overdue", 30, today(9)), task(2, "Later", 30, today(17))),
            today(9), today(18), now = today(10)
        )
        assertEquals("Overdue", p.blocks.first().title)
        val c = p.conflicts.single()
        assertEquals("Overdue", c.title)
        assertEquals(90, c.minutes) // finishes 10:30, deadline was 9:00
    }

    @Test
    fun planIsDeterministic() {
        val tasks = listOf(task(1, "A", 30, today(17)), task(2, "B", 45, today(17)), task(3, "C", 20, null))
        val a = plan(tasks, today(9), today(18), now = today(9, 1))
        val b = plan(tasks.reversed(), today(9), today(18), now = today(9, 1))
        assertEquals(a, b)
    }
}
