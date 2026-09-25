package com.example.kukoo.domain

import java.time.LocalDate

/**
 * Deterministic scheduler from the implementation document:
 *  1. free time = working window - fixed blocks (and nothing in the past)
 *  2. order tasks earliest-deadline-first
 *  3. greedy time-boxing into the earliest free slots
 *  4. split a task across slots only in meaningful chunks
 *  5. slack check with the exact shortfall
 * Pure and side-effect free, so the same input always yields the same plan.
 */
class Planner(private val config: PlannerConfig = PlannerConfig()) {

    fun plan(
        tasks: List<Task>,
        window: TimeRange,
        scope: PlanScope,
        date: LocalDate,
        now: Long,
        fixedBlocks: List<TimeRange> = emptyList(),
        source: PlanSource = PlanSource.LOCAL
    ): Plan {
        val gaps = freeTime(window, fixedBlocks, now).toMutableList()
        val minChunk = config.minChunkMin * MILLIS_PER_MINUTE

        val ordered = tasks
            .filter { !it.isDone && it.durationMin > 0 }
            .sortedWith(
                compareBy<Task> { it.deadline ?: Long.MAX_VALUE }
                    .thenByDescending { it.priority.rank }
                    .thenBy { it.durationMin }
                    .thenBy { it.id }
            )

        val blocks = mutableListOf<ScheduledBlock>()
        val conflicts = mutableListOf<Conflict>()

        for (task in ordered) {
            var remaining = task.durationMin * MILLIS_PER_MINUTE
            val chunks = mutableListOf<TimeRange>()
            var i = 0
            while (remaining > 0 && i < gaps.size) {
                val gap = gaps[i]
                var take = minOf(gap.length, remaining)
                if (take < remaining) {
                    // Splitting: never create a sliver, on either side of the split.
                    if (remaining - take < minChunk) take = remaining - minChunk
                    if (take < minChunk) {
                        i++
                        continue
                    }
                }
                chunks += TimeRange(gap.start, gap.start + take)
                remaining -= take
                val left = gap.length - take
                if (left == 0L) {
                    gaps.removeAt(i)
                } else {
                    gaps[i] = TimeRange(gap.start + take, gap.end)
                    if (remaining > 0) i++ // leftover here is too small to continue this task
                }
            }

            chunks.forEachIndexed { index, chunk ->
                blocks += ScheduledBlock(
                    taskId = task.id,
                    title = task.title,
                    priority = task.priority,
                    start = chunk.start,
                    end = chunk.end,
                    part = index + 1,
                    partCount = chunks.size
                )
            }

            val deadline = task.deadline
            if (remaining > 0) {
                conflicts += Conflict(task.id, task.title, ConflictKind.NO_ROOM, ceilMinutes(remaining), deadline)
            } else if (deadline != null && chunks.isNotEmpty() && chunks.last().end > deadline) {
                conflicts += Conflict(
                    task.id, task.title, ConflictKind.LATE, ceilMinutes(chunks.last().end - deadline), deadline
                )
            }
        }

        return Plan(
            scope = scope,
            date = date,
            window = window,
            blocks = blocks.sortedBy { it.start },
            conflicts = conflicts,
            source = source,
            generatedAt = now
        )
    }

    /** Working window minus fixed blocks, clipped to start no earlier than [now] (rounded up to a slot). */
    fun freeTime(window: TimeRange, fixedBlocks: List<TimeRange>, now: Long): List<TimeRange> {
        val start = maxOf(window.start, roundUpToSlot(now))
        if (start >= window.end) return emptyList()

        val free = mutableListOf<TimeRange>()
        var cursor = start
        for (block in fixedBlocks.sortedBy { it.start }) {
            if (block.end <= cursor) continue
            if (block.start >= window.end) break
            if (block.start > cursor) free += TimeRange(cursor, block.start)
            cursor = maxOf(cursor, block.end)
            if (cursor >= window.end) break
        }
        if (cursor < window.end) free += TimeRange(cursor, window.end)
        return free
    }

    private fun roundUpToSlot(millis: Long): Long {
        val slot = config.slotMin * MILLIS_PER_MINUTE
        return ((millis + slot - 1) / slot) * slot
    }

    private fun ceilMinutes(millis: Long): Int =
        ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
}
