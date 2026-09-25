package com.example.kukoo.data

import android.content.Context
import com.example.kukoo.model.Task
import com.example.kukoo.model.TaskWithSubtasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TodoRepository(context: Context) {
    private val dbHelper = TodoDatabaseHelper(context)
    private val _tasksFlow = MutableStateFlow<List<TaskWithSubtasks>>(emptyList())
    val tasksFlow: StateFlow<List<TaskWithSubtasks>> = _tasksFlow.asStateFlow()

    suspend fun refreshTasks() {
        withContext(Dispatchers.IO) {
            val tasks = dbHelper.getAllTasksWithSubtasks()
            _tasksFlow.value = tasks
        }
    }

    suspend fun addTask(task: Task, subtasks: List<String>): Long {
        return withContext(Dispatchers.IO) {
            val id = dbHelper.insertTaskWithSubtasks(task, subtasks)
            refreshTasks()
            id
        }
    }

    suspend fun updateTask(task: Task) {
        withContext(Dispatchers.IO) {
            dbHelper.updateTask(task)
            refreshTasks()
        }
    }

    suspend fun updateTask(task: Task, subtasks: List<String>) {
        withContext(Dispatchers.IO) {
            dbHelper.updateTaskWithSubtasks(task, subtasks)
            refreshTasks()
        }
    }

    suspend fun toggleTaskCompletion(taskId: Long, isCompleted: Boolean) {
        withContext(Dispatchers.IO) {
            dbHelper.updateTaskCompletion(taskId, isCompleted)
            refreshTasks()
        }
    }

    suspend fun toggleTaskFlagged(taskId: Long, isFlagged: Boolean) {
        withContext(Dispatchers.IO) {
            dbHelper.updateTaskFlagged(taskId, isFlagged)
            refreshTasks()
        }
    }

    suspend fun deleteTask(taskId: Long) {
        withContext(Dispatchers.IO) {
            dbHelper.deleteTask(taskId)
            refreshTasks()
        }
    }

    suspend fun clearCompletedTasks() {
        withContext(Dispatchers.IO) {
            dbHelper.clearCompletedTasks()
            refreshTasks()
        }
    }

    suspend fun addSubtask(taskId: Long, title: String) {
        withContext(Dispatchers.IO) {
            dbHelper.addSubtask(taskId, title)
            refreshTasks()
        }
    }

    suspend fun toggleSubtaskCompletion(subtaskId: Long, isCompleted: Boolean) {
        withContext(Dispatchers.IO) {
            dbHelper.updateSubtaskCompletion(subtaskId, isCompleted)
            refreshTasks()
        }
    }

    suspend fun deleteSubtask(subtaskId: Long) {
        withContext(Dispatchers.IO) {
            dbHelper.deleteSubtask(subtaskId)
            refreshTasks()
        }
    }
}
