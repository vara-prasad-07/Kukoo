package com.example.kukoo.domain

import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** One task the user wants placed in a day plan. Everything but the title is optional. */
data class PlanItemSpec(
    val title: String,
    val durationMin: Int? = null,
    val priority: Priority? = null,
    /** A fixed start time the user gave ("doctor at 4"); without one the planner picks the slot. */
    val at: LocalTime? = null
)

/** Where the planning conversation is: waiting for the task list, or waiting for a yes on a proposal. */
enum class PlanStage { ASK_TASKS, REVIEW }

/**
 * What a parser needs to know about the planning conversation so a short reply ("yes", "make it an hour")
 * is read as an answer to what the assistant just asked.
 */
data class PlanningState(val stage: PlanStage, val dayLabel: String, val taskTitles: List<String>)

/**
 * Best-guess length and importance for a task the user only named. The user asked the assistant to plan
 * *optimally*, which includes working these out, so they are estimated here, from the words in the title,
 * in one place that is easy to check. Anything the user actually said overrides them.
 */
object TaskEstimator {
    private class Rule(val minutes: Int, val words: List<String>)

    // The first rule with a matching word wins, so the more specific ones come first.
    private val lengths = listOf(
        Rule(15, listOf("pay", "bill", "submit", "book", "booking", "order", "renew", "post", "send", "reply", "email", "mail", "text", "message")),
        Rule(20, listOf("call", "calling", "phone", "ring")),
        Rule(120, listOf("exam", "gate", "jee", "neet", "upsc", "gre", "gmat", "prepar", "revis", "study", "studying", "learn", "practic", "homework", "assignment", "thesis", "movie", "film")),
        Rule(90, listOf("project", "report", "presentation", "essay", "coding", "code", "develop", "write", "writing", "design")),
        Rule(60, listOf("gym", "workout", "exercis", "running", "jogging", "yoga", "swim", "swimming", "cardio", "training", "sport", "cricket", "football", "badminton", "doctor", "dentist", "hospital", "clinic", "appointment", "checkup", "therapy", "physio", "meeting", "interview", "class", "lecture", "session")),
        Rule(45, listOf("shop", "shopping", "grocer", "market", "mall", "errand", "cook", "cooking", "meal", "lunch", "dinner", "breakfast", "clean", "cleaning", "laundry", "wash", "washing", "tidy", "read", "reading")),
        Rule(30, listOf("walk", "walking", "stroll", "meditat", "relax", "nap", "break")),
    )

    private val high = setOf(
        "doctor", "dentist", "hospital", "clinic", "appointment", "exam", "gate", "jee", "neet", "upsc", "interview",
        "deadline", "submit", "urgent", "bill", "pay", "rent", "tax", "presentation", "assignment", "thesis", "meeting"
    )
    private val low = setOf(
        "laundry", "clean", "tidy", "shop", "shopping", "grocery", "groceries", "movie", "film", "game", "games",
        "relax", "nap", "netflix", "walk", "read", "reading", "chill", "hobby"
    )

    const val DEFAULT_MINUTES = 45

    fun durationFor(title: String): Int {
        val words = tokens(title)
        return lengths.firstOrNull { rule -> rule.words.any { hit(words, it) } }?.minutes ?: DEFAULT_MINUTES
    }

    fun priorityFor(title: String): Priority {
        val words = tokens(title)
        return when {
            high.any { hit(words, it) } -> Priority.HIGH
            low.any { hit(words, it) } -> Priority.LOW
            else -> Priority.MEDIUM
        }
    }

    private fun tokens(text: String) = Regex("[a-z0-9]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()

    /**
     * A title word matches a keyword exactly, as a plural ("exams"), or, for stems of 5+ letters, by prefix
     * ("revising" for "revis"). Short keywords never match by prefix, or "ready" would count as "read".
     */
    private fun hit(words: List<String>, keyword: String) =
        words.any { it == keyword || it == keyword + "s" || (keyword.length >= 5 && it.startsWith(keyword)) }
}

/** A task going into the plan: either one the user just named, or one already on their list. */
class PlanCandidate(
    val title: String,
    val durationMin: Int,
    val priority: Priority,
    val at: LocalTime?,
    val existing: Task?,
    /** True when the length or priority was worked out by [TaskEstimator], not said by the user. */
    val estimated: Boolean
)

/** A task placed in the day. [blocks] is empty for one that did not fit; more than one when it was split. */
data class PlannedItem(
    val title: String,
    val durationMin: Int,
    val priority: Priority,
    val blocks: List<TimeRange>,
    /** Set for a task that was already on the list: it is scheduled around but left as it is. */
    val existingId: Long?,
    val fixed: Boolean,
    val estimated: Boolean,
    /** How far past its deadline the last block ends; 0 when on time or when there is no deadline. */
    val lateMin: Int = 0
) {
    val isNew: Boolean get() = existingId == null
    val fits: Boolean get() = blocks.isNotEmpty()
    val start: Long get() = blocks.first().start
    /** When the last slot ends (the task's deadline is its start, see [start]). */
    val end: Long get() = blocks.last().end
}

class DayProposal(
    val date: LocalDate,
    /** Everything that got a slot, in time order. */
    val placed: List<PlannedItem>,
    /** What did not fit in the day. */
    val unplaced: List<PlannedItem>,
    /** Pairs of fixed-time tasks that overlap; the plan cannot be added until one moves. */
    val clashes: List<Pair<String, String>>,
    val generatedAt: Long
) {
    val newItems: List<PlannedItem> get() = placed.filter { it.isNew }
}

/**
 * Turns a list of tasks into an actual day: fixed-time tasks stay where the user put them, and the rest go
 * into the free time by [SlotPacker] (earliest deadline first, then priority), with a short
 * break after each task. Pure and deterministic, so the same request always gives the same plan.
 */
class DayPlanner(private val clock: Clock, private val config: PlannerConfig = PlannerConfig()) {
    private val packer = SlotPacker(config)
    private val zone get() = clock.zone

    private fun ms(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    fun propose(date: LocalDate, candidates: List<PlanCandidate>, startAt: LocalTime?, now: Long): DayProposal {
        val start = ms(date, startAt ?: LocalTime.of(config.dayStartHour, 0))
        val window = TimeRange(start, maxOf(start, ms(date, LocalTime.of(config.dayEndHour, 0))))
        val pad = if (candidates.size >= 2) config.breakMin else 0
        val padMs = pad * MILLIS_PER_MINUTE

        val fixed = candidates.filter { it.at != null }.map { c ->
            val s = ms(date, c.at!!)
            c to TimeRange(s, s + c.durationMin * MILLIS_PER_MINUTE)
        }
        val clashes = mutableListOf<Pair<String, String>>()
        for (i in fixed.indices) for (j in i + 1 until fixed.size) {
            val a = fixed[i].second
            val b = fixed[j].second
            if (a.start < b.end && b.start < a.end) clashes += fixed[i].first.title to fixed[j].first.title
        }
        // The break either side of a fixed task is kept free too, so nothing floating is packed against it.
        val fixedBlocks = fixed.map { (_, r) -> TimeRange(r.start - padMs, r.end + padMs) }

        val floating = candidates.filter { it.at == null }
        val tasks = floating.mapIndexed { i, c ->
            val id = i + 1L
            if (c.existing != null) {
                c.existing.copy(id = id, durationMin = c.durationMin + pad, priority = c.priority)
            } else {
                Task(id = id, title = c.title, durationMin = c.durationMin + pad, priority = c.priority, createdAt = now)
            }
        }

        // A task that does not fully fit is taken out and the day planned again without it, so it cannot
        // leave a half-placed sliver that starves the tasks after it.
        val live = floating.indices.toMutableList()
        val dropped = mutableListOf<Int>()
        var plan: PackedDay
        while (true) {
            plan = packer.pack(live.map { tasks[it] }, window, now + config.planLeadMin * MILLIS_PER_MINUTE, fixedBlocks)
            val noRoom = plan.noRoomTaskIds.firstOrNull() ?: break
            val index = (noRoom - 1).toInt()
            live.remove(index)
            dropped += index
        }

        val placed = mutableListOf<PlannedItem>()
        fixed.forEach { (c, range) ->
            placed += PlannedItem(c.title, c.durationMin, c.priority, listOf(range), c.existing?.id, true, c.estimated)
        }
        for (index in live) {
            val c = floating[index]
            val blocks = plan.blocks.filter { it.taskId == index + 1L }.sortedBy { it.start }
                .map { TimeRange(it.start, it.end) }.toMutableList()
            // Take the break back off the end of the last slot: it is time between tasks, not part of the task.
            blocks.lastOrNull()?.let { last ->
                if (padMs > 0 && last.length - padMs >= MIN_BLOCK_MIN * MILLIS_PER_MINUTE) {
                    blocks[blocks.lastIndex] = TimeRange(last.start, last.end - padMs)
                }
            }
            val late = c.existing?.deadline?.let { d ->
                val over = blocks.last().end - d
                if (over > 0) ((over + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt() else 0
            } ?: 0
            placed += PlannedItem(c.title, c.durationMin, c.priority, blocks, c.existing?.id, false, c.estimated, late)
        }
        val unplaced = dropped.sorted().map { i ->
            val c = floating[i]
            PlannedItem(c.title, c.durationMin, c.priority, emptyList(), c.existing?.id, false, c.estimated)
        }
        return DayProposal(date, placed.sortedBy { it.start }, unplaced, clashes, now)
    }

    /**
     * Minutes before [deadline] to phone so the call rings a little before the task starts at [start], or null
     * when there is no room for a call that would still be in the future.
     */
    fun reminderFor(start: Long, deadline: Long, now: Long): Int? {
        for (lead in REMINDER_LEADS_MIN) {
            val rings = start - lead * MILLIS_PER_MINUTE
            if (rings <= now + MILLIS_PER_MINUTE) continue
            val minutes = ((deadline - rings + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
            if (minutes in Task.MIN_REMINDER_MIN..Task.MAX_REMINDER_MIN) return minutes
        }
        return null
    }

    private companion object {
        /** How far ahead of a task's start the reminder call rings, best first. */
        val REMINDER_LEADS_MIN = listOf(10, 5)
        const val MIN_BLOCK_MIN = 5
    }
}

/** One slot of work handed out by [SlotPacker]; a task split across gaps gets several with the same [taskId]. */
internal data class PackedBlock(val taskId: Long, val start: Long, val end: Long)

/** [noRoomTaskIds] are the tasks that could not be placed in full. */
internal class PackedDay(val blocks: List<PackedBlock>, val noRoomTaskIds: List<Long>)

/**
 * Greedy time-boxing for [DayPlanner]: free time is the window minus fixed blocks (and nothing in the past),
 * tasks go earliest deadline first, and a task is split across gaps only in chunks of [PlannerConfig.minChunkMin].
 */
internal class SlotPacker(private val config: PlannerConfig) {

    fun pack(tasks: List<Task>, window: TimeRange, now: Long, fixedBlocks: List<TimeRange>): PackedDay {
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

        val blocks = mutableListOf<PackedBlock>()
        val noRoom = mutableListOf<Long>()
        for (task in ordered) {
            var remaining = task.durationMin * MILLIS_PER_MINUTE
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
                blocks += PackedBlock(task.id, gap.start, gap.start + take)
                remaining -= take
                val left = gap.length - take
                if (left == 0L) {
                    gaps.removeAt(i)
                } else {
                    gaps[i] = TimeRange(gap.start + take, gap.end)
                    if (remaining > 0) i++ // leftover here is too small to continue this task
                }
            }
            if (remaining > 0) noRoom += task.id
        }
        return PackedDay(blocks.sortedBy { it.start }, noRoom)
    }

    private fun freeTime(window: TimeRange, fixedBlocks: List<TimeRange>, now: Long): List<TimeRange> {
        val slot = config.slotMin * MILLIS_PER_MINUTE
        val start = maxOf(window.start, ((now + slot - 1) / slot) * slot)
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
}
