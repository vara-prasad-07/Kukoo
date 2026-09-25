package com.example.kukoo.data

import com.example.kukoo.domain.MILLIS_PER_MINUTE
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStore
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime

/**
 * The task list used in the demo script (client deck, follow-up, design review, expense task).
 * Deadlines are set relative to "now" so that "Replan my afternoon" produces a real timeline
 * with one small deadline conflict, whenever the demo is started.
 */
object DemoData {

    fun reset(store: TaskStore, clock: Clock) {
        store.deleteAll()
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
