package com.example.kukoo.domain

/**
 * A task's start time is when it begins, so a scheduled task occupies exactly
 * `[start, start + duration)`. Two tasks conflict when those intervals share any time; back-to-back
 * tasks (one ends at 9:30, the next starts at 9:30) do not.
 *
 * A repeating task is stored once, at its next start; [ConflictDetector] expands it into one
 * [Occurrence] per repeat so that a future clash is found too.
 */
data class Occurrence(val task: Task, val start: Long) {
    val end: Long get() = start + task.durationMin * MILLIS_PER_MINUTE
    val range: TimeRange get() = TimeRange(start, end)

    /** Only the occurrence a task row is stored at can be moved by editing that row. */
    val isRowStart: Boolean get() = start == task.deadline
}

/** Two occurrences that share [range]. [first] never starts after [second]. */
data class Overlap(val first: Occurrence, val second: Occurrence) {
    val range: TimeRange = TimeRange(maxOf(first.start, second.start), minOf(first.end, second.end))

    /** Exact minutes, rounded up so a 30-second overlap is never reported as zero. */
    val minutes: Int get() = ((range.length + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()

    fun involves(taskId: Long) = first.task.id == taskId || second.task.id == taskId
    fun other(taskId: Long): Occurrence = if (first.task.id == taskId) second else first
    fun mine(taskId: Long): Occurrence = if (first.task.id == taskId) first else second

    val ack: OverlapAck get() = OverlapAck.of(first, second)
}

/**
 * "Keep both": the user knows these two overlap and does not want to hear about it again. The ack names
 * the exact times and lengths, so it stops applying the moment either task is moved or resized.
 */
data class OverlapAck(
    val aId: Long, val aStart: Long, val aMin: Int,
    val bId: Long, val bStart: Long, val bMin: Int
) {
    companion object {
        fun of(x: Occurrence, y: Occurrence): OverlapAck {
            val (a, b) = if (x.task.id <= y.task.id) x to y else y to x
            return OverlapAck(a.task.id, a.start, a.task.durationMin, b.task.id, b.start, b.task.durationMin)
        }
    }
}

/** Finds every pair of open, scheduled tasks whose time overlaps. Pure: same input, same answer. */
class ConflictDetector(private val resolver: DeadlineResolver, private val horizonDays: Int = HORIZON_DAYS) {

    /** Every start of every open scheduled task that has not already finished, repeats expanded. */
    fun occurrences(tasks: List<Task>, now: Long): List<Occurrence> {
        val horizonEnd = now + horizonDays * MILLIS_PER_DAY
        val out = mutableListOf<Occurrence>()
        for (task in tasks) {
            val first = task.deadline
            if (task.isDone || first == null || task.durationMin <= 0) continue
            if (task.recurrence == Recurrence.NONE) {
                Occurrence(task, first).takeIf { it.end > now }?.let { out += it }
                continue
            }
            var start: Long = first
            var guard = 0
            while (start <= horizonEnd && guard++ < MAX_REPEATS) {
                Occurrence(task, start).takeIf { it.end > now }?.let { out += it }
                val next = resolver.nextOccurrence(start, task.recurrence)
                if (next <= start) break
                start = next
            }
        }
        return out.sortedWith(compareBy<Occurrence> { it.start }.thenBy { it.task.id })
    }

    /**
     * The overlaps that still matter: not entirely in the past, and not acknowledged. Sorted by when the
     * overlap begins, then by task id, so the order never depends on how the store lists the tasks.
     */
    fun overlaps(tasks: List<Task>, now: Long, acks: Collection<OverlapAck> = emptySet()): List<Overlap> {
        val occ = occurrences(tasks, now)
        val ackSet = if (acks is Set<*>) acks else acks.toSet()
        val found = mutableListOf<Overlap>()
        for (i in occ.indices) {
            val a = occ[i]
            for (j in i + 1 until occ.size) {
                val b = occ[j]
                if (b.start >= a.end) break
                if (a.task.id == b.task.id) continue
                val overlap = Overlap(a, b)
                if (overlap.range.end <= now) continue
                if (overlap.ack in ackSet) continue
                found += overlap
            }
        }
        return found.sortedWith(
            compareBy<Overlap> { it.range.start }.thenBy { it.first.task.id }.thenBy { it.second.task.id }
        )
    }

    /** The overlaps [candidate] would have with [tasks] (a task with the same id is replaced by it). */
    fun overlapsWith(tasks: List<Task>, candidate: Task, now: Long, acks: Collection<OverlapAck> = emptySet()): List<Overlap> =
        overlaps(tasks.filter { it.id != candidate.id } + candidate, now, acks).filter { it.involves(candidate.id) }

    companion object {
        const val HORIZON_DAYS = 14
        const val MILLIS_PER_DAY = 24 * 60 * MILLIS_PER_MINUTE
        private const val MAX_REPEATS = 800
    }
}

/**
 * Finds free time. A slot is contiguous, sits inside the user's awake hours, and starts on a
 * [PlannerConfig.slotMin] boundary that is not in the past. A gap exactly as long as the task fits.
 */
class SlotFinder(private val config: PlannerConfig, private val resolver: DeadlineResolver) {

    /**
     * The earliest start at or after [notBefore] (and after [now]) where [durationMin] fits between the
     * [busy] intervals, looking [days] days ahead; null if there is no such time.
     */
    fun find(durationMin: Int, notBefore: Long, now: Long, busy: List<TimeRange>, days: Int = SEARCH_DAYS): Long? {
        val need = durationMin * MILLIS_PER_MINUTE
        val floor = roundUp(maxOf(notBefore, now))
        val sorted = busy.sortedBy { it.start }
        var date = resolver.toLocal(floor).toLocalDate()
        repeat(days) {
            val window = TimeRange(resolver.at(date, config.awakeStartHour), resolver.at(date, config.awakeEndHour))
            var cursor = roundUp(maxOf(window.start, floor))
            for (b in sorted) {
                if (b.end <= cursor) continue
                if (b.start >= window.end) break
                if (b.start - cursor >= need && cursor + need <= window.end) return cursor
                cursor = roundUp(maxOf(cursor, b.end))
            }
            if (cursor + need <= window.end) return cursor
            date = date.plusDays(1)
        }
        return null
    }

    /** The nearest free start at or after [candidate]'s own start, ignoring the candidate itself. */
    fun suggestFor(tasks: List<Task>, candidate: Task, now: Long, detector: ConflictDetector): Long? {
        val busy = detector.occurrences(tasks.filter { it.id != candidate.id }, now).map { it.range }
        return find(candidate.durationMin, candidate.deadline ?: now, now, busy)
    }

    private fun roundUp(millis: Long): Long {
        val slot = config.slotMin * MILLIS_PER_MINUTE
        return ((millis + slot - 1) / slot) * slot
    }

    companion object {
        const val SEARCH_DAYS = 7
    }
}
