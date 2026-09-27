package com.example.kukoo

import com.example.kukoo.domain.ConflictDetector
import com.example.kukoo.domain.DeadlineResolver
import com.example.kukoo.domain.Overlap
import com.example.kukoo.domain.OverlapAck
import com.example.kukoo.domain.PlannerConfig
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.SlotFinder
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * A task's start time is when it begins, so a conflict is two `[start, start + duration)` intervals that
 * share time. These pin down exactly what is, and is not, an overlap.
 */
class ConflictDetectorTest {
    private val clock = clockAt(9)
    private val detector = ConflictDetector(DeadlineResolver(clock))
    private val now = clock.millis()

    private var nextId = 1L
    private fun task(title: String, start: Long?, minutes: Int, status: TaskStatus = TaskStatus.OPEN,
                     recurrence: Recurrence = Recurrence.NONE, id: Long = nextId++) =
        Task(id, title, start, minutes, Priority.MEDIUM, status, createdAt = 0, recurrence = recurrence)

    private fun overlaps(vararg tasks: Task, acks: Set<OverlapAck> = emptySet()) =
        detector.overlaps(tasks.toList(), now, acks)

    private fun titles(o: Overlap) = o.first.task.title to o.second.task.title

    @Test fun backToBackTasks_doNotConflict() {
        assertTrue(overlaps(task("A", today(10), 30), task("B", today(10, 30), 30)).isEmpty())
    }

    @Test fun oneMinuteOfSharedTime_isExactlyOneMinute() {
        val o = overlaps(task("A", today(10), 30), task("B", today(10, 29), 30)).single()
        assertEquals(1, o.minutes)
        assertEquals(TimeRange(today(10, 29), today(10, 30)), o.range)
    }

    @Test fun aLongTaskContainingAShortOne_overlapsForTheShortOnesLength() {
        val o = overlaps(task("Workshop", today(10), 180), task("Call", today(11), 20)).single()
        assertEquals(20, o.minutes)
        assertEquals("Workshop" to "Call", titles(o))
    }

    @Test fun identicalTimes_overlapForTheWholeDuration() {
        val o = overlaps(task("A", today(10), 45), task("B", today(10), 45)).single()
        assertEquals(45, o.minutes)
    }

    @Test fun aChain_reportsEachOverlappingPair_notTheEnds() {
        // A and B overlap, B and C overlap, A and C do not.
        val found = overlaps(task("A", today(10), 40), task("B", today(10, 30), 40), task("C", today(11, 5), 30))
        assertEquals(listOf("A" to "B", "B" to "C"), found.map(::titles))
    }

    @Test fun doneTasks_unscheduledTasks_andFinishedOverlaps_areIgnored() {
        assertTrue(overlaps(task("A", today(10), 30, TaskStatus.DONE), task("B", today(10), 30)).isEmpty())
        assertTrue(overlaps(task("A", null, 30), task("B", today(10), 30)).isEmpty())
        // Both ended before now (9:00): nothing left to warn about.
        assertTrue(overlaps(task("A", today(7), 30), task("B", today(7), 30)).isEmpty())
    }

    @Test fun anOverlapThatIsStillRunning_isKept_untilItEnds() {
        val a = task("A", today(8, 30), 60)   // ends 9:30
        val b = task("B", today(8, 45), 60)   // ends 9:45
        assertEquals(1, overlaps(a, b).size)
        // Shared time is 8:45-9:30; at 9:30 it is over.
        assertTrue(detector.overlaps(listOf(a, b), today(9, 30)).isEmpty())
    }

    @Test fun theSameTaskIsNeverInConflictWithItself() {
        val daily = task("Gym", today(10), 600, recurrence = Recurrence.DAILY)  // 10 hours: next start is inside itself
        assertTrue(overlaps(daily).isEmpty())
    }

    @Test fun acknowledgedOverlap_isHidden_untilEitherTaskChanges() {
        val a = task("A", today(10), 30)
        val b = task("B", today(10, 15), 30)
        val ack = overlaps(a, b).single().ack
        assertTrue(overlaps(a, b, acks = setOf(ack)).isEmpty())
        // Moved by one minute, the old acknowledgement no longer applies.
        assertEquals(1, overlaps(a, b.copy(deadline = today(10, 16)), acks = setOf(ack)).size)
        // Longer, same.
        assertEquals(1, overlaps(a.copy(durationMin = 45), b, acks = setOf(ack)).size)
    }

    @Test fun order_ofTheInput_neverChangesTheAnswer() {
        val tasks = (1..12).map { task("T$it", today(9 + it % 5, (it * 7) % 60), 20 + it * 5) }
        val expected = detector.overlaps(tasks, now)
        repeat(20) { seed ->
            assertEquals(expected, detector.overlaps(tasks.shuffled(Random(seed)), now))
        }
    }

    @Test fun matchesABruteForcePairwiseCheck_onRandomTasks() {
        val random = Random(42)
        repeat(300) {
            val tasks = (1..random.nextInt(2, 9)).map {
                task("T", today(9) + random.nextInt(0, 12 * 4) * 15 * 60_000L, random.nextInt(1, 10) * 15)
            }
            val expected = mutableSetOf<Pair<Long, Long>>()
            for (i in tasks.indices) for (j in i + 1 until tasks.size) {
                val a = tasks[i]; val b = tasks[j]
                val aEnd = a.deadline!! + a.durationMin * 60_000L
                val bEnd = b.deadline!! + b.durationMin * 60_000L
                if (a.deadline!! < bEnd && b.deadline!! < aEnd) expected += minOf(a.id, b.id) to maxOf(a.id, b.id)
            }
            val found = detector.overlaps(tasks, now).map { minOf(it.first.task.id, it.second.task.id) to maxOf(it.first.task.id, it.second.task.id) }.toSet()
            assertEquals(expected, found)
        }
    }

    // ---- repeating tasks ---------------------------------------------------------------------

    @Test fun aRepeatingTask_clashesOnALaterDay() {
        val gym = task("Gym", tomorrow(7), 30, recurrence = Recurrence.DAILY)
        val meeting = task("Meeting", millis(TODAY.plusDays(3), 7, 15), 60)
        val o = overlaps(gym, meeting).single()
        assertEquals(millis(TODAY.plusDays(3), 7), o.first.start)
        assertEquals(15, o.minutes)
    }

    @Test fun weekdaysRecurrence_skipsTheWeekend() {
        val standup = task("Standup", today(10), 30, recurrence = Recurrence.WEEKDAYS)
        val saturday = millis(TODAY.plusDays(1), 10, 10)
        assertTrue(overlaps(standup, task("Brunch", saturday, 60)).isEmpty())
        val monday = millis(TODAY.plusDays(3), 10, 10)
        assertEquals(1, overlaps(standup, task("Planning", monday, 60)).size)
    }

    @Test fun monthlyOn31st_doesNotCrash_andStaysInRange() {
        val zone = ZoneId.of("Asia/Kolkata")
        val start = LocalDate.of(2026, 1, 31).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val c = Clock.fixed(Instant.ofEpochMilli(start - 3_600_000), zone)
        val found = ConflictDetector(DeadlineResolver(c)).occurrences(
            listOf(task("Rent", start, 30, recurrence = Recurrence.MONTHLY)), c.millis()
        )
        assertTrue(found.isNotEmpty())
        assertTrue(found.zipWithNext().all { (a, b) -> b.start > a.start })
    }

    @Test fun aRepeatingTask_keepsItsWallClockTime_acrossADaylightSavingChange() {
        val ny = ZoneId.of("America/New_York")
        val fixed = Clock.fixed(LocalDateTime.of(2026, 3, 7, 12, 0).atZone(ny).toInstant(), ny)  // day before spring-forward
        val start = LocalDateTime.of(2026, 3, 8, 9, 0).atZone(ny).toInstant().toEpochMilli()
        val occ = ConflictDetector(DeadlineResolver(fixed)).occurrences(
            listOf(task("Run", start, 30, recurrence = Recurrence.DAILY)), fixed.millis()
        ).take(3)
        assertEquals(listOf(9, 9, 9), occ.map { Instant.ofEpochMilli(it.start).atZone(ny).hour })
    }

    // ---- free time ---------------------------------------------------------------------------

    private val finder = SlotFinder(PlannerConfig(), DeadlineResolver(clock))
    private fun busy(vararg r: Pair<Long, Long>) = r.map { TimeRange(it.first, it.second) }

    @Test fun aGapExactlyAsLongAsTheTask_fits() {
        val slot = finder.find(60, today(10), now, busy(today(9) to today(10), today(11) to today(12)))
        assertEquals(today(10), slot)
    }

    @Test fun aGapOneMinuteTooShort_isSkipped() {
        val slot = finder.find(60, today(10), now, busy(today(9) to today(10), today(10, 59) to today(12)))
        assertEquals(today(12), slot)
    }

    @Test fun neverSuggestsAStartInThePast() {
        assertEquals(today(9), finder.find(30, today(6), now, emptyList()))
    }

    @Test fun startsOnAFiveMinuteBoundary() {
        val slot = finder.find(30, today(10), now, busy(today(9) to today(10, 2)))!!
        assertEquals(today(10, 5), slot)
    }

    @Test fun staysInsideAwakeHours_andRollsToTheNextDay() {
        // Awake 07:00-23:00. From 22:30 a one-hour task cannot fit today.
        val slot = finder.find(60, today(22, 30), today(22), emptyList())
        assertEquals(tomorrow(7), slot)
    }

    @Test fun aFullWeek_hasNoSlot() {
        val week = (0..7).map { millis(TODAY.plusDays(it.toLong()), 0) to millis(TODAY.plusDays(it + 1L), 0) }
        assertNull(finder.find(30, today(9), now, week.map { TimeRange(it.first, it.second) }))
    }

    @Test fun aSuggestion_neverOverlapsAnything() {
        val random = Random(7)
        repeat(300) {
            val tasks = (1..random.nextInt(0, 8)).map {
                task("T", today(9) + random.nextInt(0, 14 * 4) * 15 * 60_000L, random.nextInt(1, 8) * 15)
            }
            val minutes = random.nextInt(1, 6) * 15
            val slot = finder.find(minutes, now, now, detector.occurrences(tasks, now).map { it.range })
            assertNotNull(slot)
            val end = slot!! + minutes * 60_000L
            assertTrue("suggested $slot overlaps", tasks.none { it.deadline!! < end && slot < it.deadline!! + it.durationMin * 60_000L })
            assertTrue(slot >= now)
        }
    }
}
