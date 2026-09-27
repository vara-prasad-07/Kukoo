package com.example.kukoo.data

import com.example.kukoo.domain.Goal
import com.example.kukoo.domain.GoalKind
import com.example.kukoo.domain.HistoryEntry
import com.example.kukoo.domain.MILLIS_PER_MINUTE
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.ProfileStore
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStore
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The task list used in the demo script (client deck, follow-up, design review, expense task).
 * Deadlines are set relative to "now" so that "Replan my afternoon" produces a real timeline
 * with one small deadline conflict, whenever the demo is started.
 *
 * With a [ProfileStore] it also loads the personalisation demo: the user's goals and two weeks of past activity
 * (see [DemoHistory]), so "add one extra task based on my goals" has something real to learn from.
 */
object DemoData {

    fun reset(store: TaskStore, clock: Clock, profile: ProfileStore? = null) {
        store.deleteAll()
        profile?.let { DemoHistory.load(it, clock) }
        val now = clock.millis()

        fun inMinutes(minutes: Int): Long {
            val slot = 5 * MILLIS_PER_MINUTE
            val target = now + minutes * MILLIS_PER_MINUTE
            return ((target + slot - 1) / slot) * slot
        }

        val tomorrowNoon = LocalDate.now(clock).plusDays(1).atTime(LocalTime.of(12, 0))
            .atZone(clock.zone).toInstant().toEpochMilli()

        val seed = listOf(
            Task(title = "Follow-up", deadline = inMinutes(40), durationMin = 30, priority = Priority.MEDIUM, createdAt = now),
            Task(title = "Client Deck", deadline = inMinutes(80), durationMin = 60, priority = Priority.HIGH, createdAt = now),
            Task(title = "Design Review", deadline = inMinutes(150), durationMin = 45, priority = Priority.MEDIUM, createdAt = now),
            Task(title = "Expense report", deadline = tomorrowNoon, durationMin = 20, priority = Priority.LOW, createdAt = now),
            Task(title = "Book flights", deadline = null, durationMin = 30, priority = Priority.LOW, createdAt = now)
        )
        seed.forEach { store.insert(it) }
    }
}

/**
 * A believable fortnight for a GATE aspirant, stored in the database exactly as real use would leave it, so the
 * recommender is exercised on data rather than on special cases. The pattern is deliberate:
 *
 *  - GATE Preparation: every morning at ~9, 2 hours (a 14-day streak; the strongest habit, in the morning)
 *  - DSA Practice: most evenings at ~7 PM, 1 hour, missed twice (a strong habit, in the evening)
 *  - Gym: ~6:30 AM, an hour, skipped now and then (a weaker, less reliable habit)
 *  - Read a book, Guitar practice: occasional, low-priority interests
 *  - Pay electricity bill, Doctor appointment: one-offs, which must NOT be recommended
 *
 * Days are counted back from today, so the story is the same whenever the demo is loaded.
 */
object DemoHistory {

    val goals = listOf(
        Goal(
            name = "Crack GATE 2027", kind = GoalKind.GOAL, weight = 3, dailyTargetMin = 240,
            keywords = listOf("gate", "dsa", "algorithm", "dbms", "os", "aptitude", "mock test", "revision"),
            starterTitle = "GATE revision", starterMin = 60
        ),
        Goal(
            name = "Stay fit", kind = GoalKind.GOAL, weight = 2, dailyTargetMin = 60,
            keywords = listOf("gym", "workout", "run", "yoga", "cardio", "exercise"),
            starterTitle = "Workout", starterMin = 45
        ),
        Goal(
            name = "Learn guitar", kind = GoalKind.INTEREST, weight = 1, dailyTargetMin = 30,
            keywords = listOf("guitar", "music"), starterTitle = "Guitar practice", starterMin = 30
        ),
        Goal(
            name = "Read more", kind = GoalKind.INTEREST, weight = 1, dailyTargetMin = 30,
            keywords = listOf("read", "book"), starterTitle = "Read a book", starterMin = 30
        ),
        Goal(
            name = "Build side projects", kind = GoalKind.INTEREST, weight = 2, dailyTargetMin = 60,
            keywords = listOf("side project"), starterTitle = "Side project work", starterMin = 60
        )
    )

    /** Replaces the goals and the history with the demo ones. */
    fun load(profile: ProfileStore, clock: Clock) {
        profile.replaceGoals(goals)
        profile.clearHistory()
        profile.addHistory(entries(LocalDate.now(clock), clock.zone))
    }

    fun entries(today: LocalDate, zone: ZoneId): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()

        // A little jitter so the times are not robotic: within +-20 minutes of the usual time, the same every load.
        fun add(daysAgo: Int, title: String, hour: Int, minute: Int, minutes: Int, priority: Priority, done: Boolean = true) {
            val jitter = ((daysAgo * 37) % 5 - 2) * 10
            val start = today.minusDays(daysAgo.toLong()).atTime(hour, minute).plusMinutes(jitter.toLong())
            out += HistoryEntry(
                title = title, startedAt = start.atZone(zone).toInstant().toEpochMilli(),
                durationMin = minutes, priority = priority, completed = done
            )
        }

        for (d in 1..14) {
            add(d, "GATE Preparation", 9, 0, 120, Priority.HIGH)
            add(d, "DSA Practice", 19, 0, 60, Priority.HIGH, done = d != 3 && d != 9)
        }
        for (d in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12)) {
            add(d, "Gym", 6, 30, 60, Priority.MEDIUM, done = d !in setOf(3, 6, 9))
        }
        for (d in listOf(1, 3, 6, 10)) add(d, "Read a book", 21, 0, 30, Priority.LOW)
        add(6, "Guitar practice", 20, 0, 20, Priority.LOW)
        add(10, "Pay electricity bill", 18, 0, 15, Priority.HIGH)
        add(5, "Doctor appointment", 16, 0, 45, Priority.HIGH)
        return out
    }
}
