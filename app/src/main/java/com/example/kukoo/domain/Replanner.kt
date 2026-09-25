package com.example.kukoo.domain

import java.time.LocalDate

/** Everything a planner needs, as plain data so it can be sent to the laptop later. */
data class PlanRequest(
    val tasks: List<Task>,
    val window: TimeRange,
    val fixedBlocks: List<TimeRange>,
    val scope: PlanScope,
    val date: LocalDate,
    val now: Long
)

/**
 * Seam for "where does the heavy replan run". [LocalReplanner] is the on-device path;
 * an Office Kit implementation (phone -> laptop -> phone) can be added without touching the engine.
 */
interface Replanner {
    suspend fun replan(request: PlanRequest): Plan
}

class LocalReplanner(private val planner: Planner = Planner()) : Replanner {
    override suspend fun replan(request: PlanRequest): Plan = planner.plan(
        tasks = request.tasks,
        window = request.window,
        scope = request.scope,
        date = request.date,
        now = request.now,
        fixedBlocks = request.fixedBlocks,
        source = PlanSource.LOCAL
    )
}
