package com.example.kukoo.model

data class Task(
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val isCompleted: Boolean = false,
    val priority: Priority = Priority.NONE,
    val category: Category = Category.GENERAL,
    val dueDate: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val isFlagged: Boolean = false
)

data class TaskWithSubtasks(
    val task: Task,
    val subtasks: List<Subtask> = emptyList()
) {
    val completedSubtasksCount: Int get() = subtasks.count { it.isCompleted }
    val totalSubtasksCount: Int get() = subtasks.size
    val subtaskProgress: Float get() = if (subtasks.isEmpty()) 0f else completedSubtasksCount.toFloat() / totalSubtasksCount.toFloat()
}
