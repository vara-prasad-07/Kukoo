package com.example.kukoo.model

data class TaskStats(
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val todayTasks: Int = 0,
    val highPriorityTasks: Int = 0,
    val overdueTasks: Int = 0
) {
    val pendingTasks: Int get() = totalTasks - completedTasks
    val completionRatio: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks.toFloat()
    val completionPercentage: Int get() = (completionRatio * 100).toInt()
}
