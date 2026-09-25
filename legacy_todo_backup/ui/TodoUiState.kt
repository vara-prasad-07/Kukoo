package com.example.kukoo.ui

import com.example.kukoo.model.Category
import com.example.kukoo.model.Priority
import com.example.kukoo.model.TaskFilter
import com.example.kukoo.model.TaskSort
import com.example.kukoo.model.TaskStats
import com.example.kukoo.model.TaskWithSubtasks

data class TodoUiState(
    val filteredTasks: List<TaskWithSubtasks> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: TaskFilter = TaskFilter.ALL,
    val selectedCategory: Category? = null,
    val selectedPriority: Priority? = null,
    val sortOption: TaskSort = TaskSort.DUE_DATE,
    val selectedTaskForDetail: TaskWithSubtasks? = null,
    val showAddSheet: Boolean = false,
    val taskToEdit: TaskWithSubtasks? = null,
    val stats: TaskStats = TaskStats(),
    val isLoading: Boolean = true
)
