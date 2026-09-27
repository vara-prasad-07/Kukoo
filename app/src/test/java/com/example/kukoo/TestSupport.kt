package com.example.kukoo

import com.example.kukoo.domain.OverlapAck
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStore
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

val TEST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

/** Friday 2026-09-25 at [hour]:[minute] local time. */
fun clockAt(hour: Int, minute: Int = 0): Clock =
    Clock.fixed(
        LocalDateTime.of(2026, 9, 25, hour, minute).atZone(TEST_ZONE).toInstant(),
        TEST_ZONE
    )

fun millis(date: LocalDate, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(TEST_ZONE).toInstant().toEpochMilli()

val TODAY: LocalDate = LocalDate.of(2026, 9, 25)
val TOMORROW: LocalDate = TODAY.plusDays(1)

fun today(hour: Int, minute: Int = 0) = millis(TODAY, hour, minute)
fun tomorrow(hour: Int, minute: Int = 0) = millis(TOMORROW, hour, minute)

class FakeStore(initial: List<Task> = emptyList()) : TaskStore {
    private val rows = LinkedHashMap<Long, Task>()
    private var nextId = 1L

    init {
        initial.forEach { insert(it) }
    }

    override fun all(): List<Task> = rows.values.toList()
    override fun get(id: Long): Task? = rows[id]
    override fun insert(task: Task): Task {
        val saved = task.copy(id = nextId++)
        rows[saved.id] = saved
        return saved
    }

    override fun update(task: Task) {
        check(rows.containsKey(task.id)) { "no such task ${task.id}" }
        rows[task.id] = task
    }

    override fun delete(id: Long): Boolean {
        ackSet.removeAll { it.aId == id || it.bId == id }
        return rows.remove(id) != null
    }
    override fun deleteAll() {
        rows.clear()
        ackSet.clear()
    }

    private val ackSet = mutableSetOf<OverlapAck>()
    override fun acks(): Set<OverlapAck> = ackSet.toSet()
    override fun addAck(ack: OverlapAck) { ackSet += ack }
}
