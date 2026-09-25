package com.example.kukoo

import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.ui.home.SectionKey
import com.example.kukoo.ui.home.groupTasks
import com.example.kukoo.ui.home.shortDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskGroupingTest {
    private val now = today(15)

    private fun t(id: Long, title: String, deadline: Long?, done: Boolean = false, priority: Priority = Priority.MEDIUM) =
        Task(
            id, title, deadline, 30, priority,
            if (done) TaskStatus.DONE else TaskStatus.OPEN,
            createdAt = 0, completedAt = if (done) now else null
        )

    @Test
    fun bucketsTasksAndOmitsEmptySections() {
        val sections = groupTasks(
            listOf(
                t(1, "late", today(9)),
                t(2, "soon", today(17)),
                t(3, "tomorrow", tomorrow(10)),
                t(4, "someday", null),
                t(5, "finished", today(12), done = true)
            ),
            now, TEST_ZONE
        )
        assertEquals(
            listOf(SectionKey.OVERDUE, SectionKey.TODAY, SectionKey.UPCOMING, SectionKey.NO_DEADLINE, SectionKey.DONE),
            sections.map { it.key }
        )
        assertEquals(listOf("late"), sections[0].tasks.map { it.title })
        assertEquals(listOf("finished"), sections.last().tasks.map { it.title })
    }

    @Test
    fun endOfDayBoundary_usesTheLocalZone_notUtc() {
        // 23:30 IST today is still "today"; 00:30 IST tomorrow is "upcoming".
        val sections = groupTasks(listOf(t(1, "a", today(23, 30)), t(2, "b", tomorrow(0, 30))), now, TEST_ZONE)
        assertEquals(SectionKey.TODAY, sections[0].key)
        assertEquals(SectionKey.UPCOMING, sections[1].key)
    }

    @Test
    fun sortsByDeadlineThenPriority() {
        val sections = groupTasks(
            listOf(
                t(1, "later", today(18)),
                t(2, "lowSame", today(17), priority = Priority.LOW),
                t(3, "highSame", today(17), priority = Priority.HIGH)
            ),
            now, TEST_ZONE
        )
        assertEquals(listOf("highSame", "lowSame", "later"), sections.single().tasks.map { it.title })
    }

    @Test
    fun taskDueExactlyNow_isToday_notOverdue() {
        val sections = groupTasks(listOf(t(1, "now", now)), now, TEST_ZONE)
        assertEquals(SectionKey.TODAY, sections.single().key)
    }

    @Test
    fun shortDurationFormats() {
        assertEquals("45 min", shortDuration(45))
        assertEquals("1 hr", shortDuration(60))
        assertEquals("1 hr 30 min", shortDuration(90))
    }
}
