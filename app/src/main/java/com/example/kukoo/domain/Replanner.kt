package com.example.kukoo.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything needed to lay out one day. */
data class PlanRequest(
    val tasks: List<Task>,
    val acks: Set<OverlapAck>,
    val scope: PlanScope,
    val date: LocalDate,
    val now: Long,
    val zone: ZoneId
)

/** Seam for "where does the day get laid out". Only the on-device implementation exists. */
interface Replanner {
    suspend fun replan(request: PlanRequest): Plan
}

/**
 * Lays out a day: every task that starts (or is still running) on it, in time order, with the tasks
 * they overlap named; then a suggested start time for each task that has none, in the free gaps.
 * It never moves a task the user scheduled.
 */
class LocalReplanner(private val config: PlannerConfig = PlannerConfig()) : Replanner {

    override suspend fun replan(request: PlanRequest): Plan {
        val now = request.now
        val resolver = DeadlineResolver(Clock.fixed(Instant.ofEpochMilli(now), request.zone), config)
        val dayStart = resolver.startOfDay(request.date)
        val dayEnd = resolver.endOfDay(request.date)
        val afternoon = request.scope == PlanScope.AFTERNOON
        // "Afternoon" hides the morning; it never changes what is scheduled.
        val from = if (afternoon) maxOf(dayStart, resolver.at(request.date, config.afternoonStartHour)) else dayStart

        val detector = ConflictDetector(resolver)
        val occurrences = detector.occurrences(request.tasks, now)
        val overlaps = detector.overlaps(request.tasks, now, request.acks)

        val shown = occurrences.filter { it.end > from && it.start <= dayEnd }
        val blocks = shown.map { o ->
            ScheduledBlock(
                taskId = o.task.id,
                title = o.task.title,
                priority = o.task.priority,
                start = o.start,
                end = o.end,
                overlapsWith = overlaps.filter { it.involves(o.task.id) && it.mine(o.task.id).start == o.start }
                    .map { it.other(o.task.id).task.title }
            )
        }.toMutableList()

        val conflicts = overlaps
            .filter { it.range.end > from && it.range.start <= dayEnd }
            .map { Conflict(it.first.task.id, it.first.task.title, it.second.task.id, it.second.task.title, it.range.start, it.range.end) }

        // Tasks with no start time get a suggested one, biggest and most important first.
        val busy = occurrences.map { it.range }.toMutableList()
        val finder = SlotFinder(config, resolver)
        val unplaced = mutableListOf<String>()
        request.tasks
            .filter { !it.isDone && it.deadline == null && it.durationMin > 0 }
            .sortedWith(compareByDescending<Task> { it.priority.rank }.thenByDescending { it.durationMin }.thenBy { it.id })
            .forEach { task ->
                val slot = finder.find(task.durationMin, maxOf(from, dayStart), now, busy)
                if (slot != null && slot <= dayEnd) {
                    val end = slot + task.durationMin * MILLIS_PER_MINUTE
                    busy += TimeRange(slot, end)
                    blocks += ScheduledBlock(task.id, task.title, task.priority, slot, end, proposed = true)
                } else unplaced += task.title
            }

        return Plan(
            scope = request.scope,
            date = request.date,
            blocks = blocks.sortedWith(compareBy<ScheduledBlock> { it.start }.thenBy { it.taskId }),
            conflicts = conflicts,
            unplaced = unplaced,
            source = PlanSource.LOCAL,
            generatedAt = now
        )
    }
}
