package com.example.kukoo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.kukoo.data.TodoRepository
import com.example.kukoo.model.Category
import com.example.kukoo.model.Priority
import com.example.kukoo.model.Task
import com.example.kukoo.model.TaskFilter
import com.example.kukoo.model.TaskSort
import com.example.kukoo.model.TaskStats
import com.example.kukoo.model.TaskWithSubtasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

class TodoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository(application)

    private val _uiState = MutableStateFlow(TodoUiState())
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.refreshTasks()
            } catch (e: Exception) {
                android.util.Log.e("TodoViewModel", "Initial load failed", e)
                _uiState.update { it.copy(isLoading = false) }
            }
            repository.tasksFlow.collect { rawTasks ->
                updateFilteredTasks(rawTasks)
            }
        }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TodoViewModel", "Operation failed", e)
            }
        }
    }

    private fun updateFilteredTasks(allTasks: List<TaskWithSubtasks>) {
        val currentState = _uiState.value
        val query = currentState.searchQuery.trim().lowercase()
        val filter = currentState.selectedFilter
        val categoryFilter = currentState.selectedCategory
        val priorityFilter = currentState.selectedPriority
        val sort = currentState.sortOption

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        val endOfToday = calendar.timeInMillis

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfToday = calendar.timeInMillis

        // Compute overall stats
        val total = allTasks.size
        val completed = allTasks.count { it.task.isCompleted }
        val todayCount = allTasks.count {
            val due = it.task.dueDate
            due != null && due in startOfToday..endOfToday && !it.task.isCompleted
        }
        val highPriority = allTasks.count { it.task.priority == Priority.HIGH && !it.task.isCompleted }
        val overdue = allTasks.count {
            val due = it.task.dueDate
            due != null && due < startOfToday && !it.task.isCompleted
        }

        val stats = TaskStats(
            totalTasks = total,
            completedTasks = completed,
            todayTasks = todayCount,
            highPriorityTasks = highPriority,
            overdueTasks = overdue
        )

        // Filter tasks
        var filtered = allTasks.filter { taskWithSub ->
            val task = taskWithSub.task

            // Filter tab condition
            val matchesFilter = when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.TODAY -> task.dueDate != null && task.dueDate in startOfToday..endOfToday
                TaskFilter.UPCOMING -> task.dueDate != null && task.dueDate > endOfToday && !task.isCompleted
                TaskFilter.FLAGGED -> task.isFlagged
                TaskFilter.COMPLETED -> task.isCompleted
            }

            // Search query condition
            val matchesQuery = query.isEmpty() ||
                    task.title.lowercase().contains(query) ||
                    task.description.lowercase().contains(query) ||
                    taskWithSub.subtasks.any { it.title.lowercase().contains(query) }

            // Category condition
            val matchesCategory = categoryFilter == null || task.category == categoryFilter

            // Priority condition
            val matchesPriority = priorityFilter == null || task.priority == priorityFilter

            matchesFilter && matchesQuery && matchesCategory && matchesPriority
        }

        // Sort tasks
        filtered = when (sort) {
            TaskSort.DUE_DATE -> filtered.sortedWith(
                compareBy<TaskWithSubtasks> { it.task.isCompleted }
                    .thenBy(nullsLast()) { it.task.dueDate }
                    .thenByDescending { it.task.priority.level }
            )
            TaskSort.PRIORITY -> filtered.sortedWith(
                compareBy<TaskWithSubtasks> { it.task.isCompleted }
                    .thenByDescending { it.task.priority.level }
                    .thenBy(nullsLast()) { it.task.dueDate }
            )
            TaskSort.TITLE -> filtered.sortedWith(
                compareBy<TaskWithSubtasks> { it.task.isCompleted }
                    .thenBy { it.task.title.lowercase() }
            )
            TaskSort.CREATED_DATE -> filtered.sortedWith(
                compareBy<TaskWithSubtasks> { it.task.isCompleted }
                    .thenByDescending { it.task.createdAt }
            )
        }

        _uiState.update {
            // Refresh the open detail sheet from the latest state, not a stale snapshot
            val updatedSelectedDetail = it.selectedTaskForDetail?.let { currentDetail ->
                allTasks.find { t -> t.task.id == currentDetail.task.id }
            }
            it.copy(
                filteredTasks = filtered,
                stats = stats,
                selectedTaskForDetail = updatedSelectedDetail,
                isLoading = false
            )
        }
    }

    // --- Actions ---

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            updateFilteredTasks(repository.tasksFlow.value)
        }
    }

    fun setFilter(filter: TaskFilter) {
        _uiState.update { it.copy(selectedFilter = filter) }
        viewModelScope.launch {
            updateFilteredTasks(repository.tasksFlow.value)
        }
    }

    fun setCategoryFilter(category: Category?) {
        val newCategory = if (_uiState.value.selectedCategory == category) null else category
        _uiState.update { it.copy(selectedCategory = newCategory) }
        viewModelScope.launch {
            updateFilteredTasks(repository.tasksFlow.value)
        }
    }

    fun setPriorityFilter(priority: Priority?) {
        val newPriority = if (_uiState.value.selectedPriority == priority) null else priority
        _uiState.update { it.copy(selectedPriority = newPriority) }
        viewModelScope.launch {
            updateFilteredTasks(repository.tasksFlow.value)
        }
    }

    fun setSortOption(sort: TaskSort) {
        _uiState.update { it.copy(sortOption = sort) }
        viewModelScope.launch {
            updateFilteredTasks(repository.tasksFlow.value)
        }
    }

    fun toggleTaskCompletion(task: Task) {
        launchSafely {
            repository.toggleTaskCompletion(task.id, !task.isCompleted)
        }
    }

    fun toggleTaskFlagged(task: Task) {
        launchSafely {
            repository.toggleTaskFlagged(task.id, !task.isFlagged)
        }
    }

    fun toggleSubtaskCompletion(subtaskId: Long, isCompleted: Boolean) {
        launchSafely {
            repository.toggleSubtaskCompletion(subtaskId, !isCompleted)
        }
    }

    fun deleteTask(taskId: Long) {
        launchSafely {
            if (_uiState.value.selectedTaskForDetail?.task?.id == taskId) {
                _uiState.update { it.copy(selectedTaskForDetail = null) }
            }
            repository.deleteTask(taskId)
        }
    }

    fun clearCompletedTasks() {
        launchSafely {
            repository.clearCompletedTasks()
        }
    }

    fun openAddTaskSheet() {
        _uiState.update { it.copy(showAddSheet = true, taskToEdit = null) }
    }

    fun openEditTaskSheet(taskWithSubtasks: TaskWithSubtasks) {
        _uiState.update { it.copy(showAddSheet = true, taskToEdit = taskWithSubtasks) }
    }

    fun closeAddEditSheet() {
        _uiState.update { it.copy(showAddSheet = false, taskToEdit = null) }
    }

    fun openTaskDetail(taskWithSubtasks: TaskWithSubtasks) {
        _uiState.update { it.copy(selectedTaskForDetail = taskWithSubtasks) }
    }

    fun closeTaskDetail() {
        _uiState.update { it.copy(selectedTaskForDetail = null) }
    }

    fun saveTask(
        id: Long,
        title: String,
        description: String,
        priority: Priority,
        category: Category,
        dueDate: Long?,
        subtasks: List<String>
    ) {
        launchSafely {
            if (id == 0L) {
                val newTask = Task(
                    title = title.trim(),
                    description = description.trim(),
                    priority = priority,
                    category = category,
                    dueDate = dueDate
                )
                repository.addTask(newTask, subtasks)
            } else {
                val existing = _uiState.value.taskToEdit?.task
                if (existing != null) {
                    val updatedTask = existing.copy(
                        title = title.trim(),
                        description = description.trim(),
                        priority = priority,
                        category = category,
                        dueDate = dueDate
                    )
                    repository.updateTask(updatedTask, subtasks)
                }
            }
            closeAddEditSheet()
        }
    }

    fun addSubtaskToTask(taskId: Long, title: String) {
        if (title.isBlank()) return
        launchSafely {
            repository.addSubtask(taskId, title)
        }
    }

    fun deleteSubtask(subtaskId: Long) {
        launchSafely {
            repository.deleteSubtask(subtaskId)
        }
    }
}
