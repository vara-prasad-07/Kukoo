package com.example.kukoo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kukoo.model.Category
import com.example.kukoo.model.Priority
import com.example.kukoo.model.Task
import com.example.kukoo.model.TaskFilter
import com.example.kukoo.model.TaskSort
import com.example.kukoo.model.TaskStats
import com.example.kukoo.model.TaskWithSubtasks
import com.example.kukoo.ui.TodoUiState
import com.example.kukoo.ui.TodoViewModel
import com.example.kukoo.ui.components.AddEditTaskSheet
import com.example.kukoo.ui.components.EmptyState
import com.example.kukoo.ui.components.FilterBar
import com.example.kukoo.ui.components.TaskDashboard
import com.example.kukoo.ui.components.TaskDetailSheet
import com.example.kukoo.ui.components.TaskItem
import com.example.kukoo.ui.theme.KukooTheme

@Composable
fun TodoListScreen(
    modifier: Modifier = Modifier,
    viewModel: TodoViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    TodoListContent(
        uiState = uiState,
        onSearchQueryChange = { viewModel.setSearchQuery(it) },
        onFilterSelected = { viewModel.setFilter(it) },
        onCategorySelected = { viewModel.setCategoryFilter(it) },
        onPrioritySelected = { viewModel.setPriorityFilter(it) },
        onSortSelected = { viewModel.setSortOption(it) },
        onToggleTaskCompletion = { viewModel.toggleTaskCompletion(it) },
        onToggleTaskFlagged = { viewModel.toggleTaskFlagged(it) },
        onOpenTaskDetail = { viewModel.openTaskDetail(it) },
        onOpenEditTaskSheet = { viewModel.openEditTaskSheet(it) },
        onDeleteTask = { viewModel.deleteTask(it) },
        onOpenAddTaskSheet = { viewModel.openAddTaskSheet() },
        onCloseAddEditSheet = { viewModel.closeAddEditSheet() },
        onCloseTaskDetail = { viewModel.closeTaskDetail() },
        onClearCompletedTasks = { viewModel.clearCompletedTasks() },
        onSaveTask = { id, title, desc, priority, category, due, subtasks ->
            viewModel.saveTask(id, title, desc, priority, category, due, subtasks)
        },
        onToggleSubtask = { subtaskId, isCompleted ->
            viewModel.toggleSubtaskCompletion(subtaskId, isCompleted)
        },
        onAddSubtask = { taskId, title ->
            viewModel.addSubtaskToTask(taskId, title)
        },
        onDeleteSubtask = { viewModel.deleteSubtask(it) },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListContent(
    uiState: TodoUiState,
    onSearchQueryChange: (String) -> Unit,
    onFilterSelected: (TaskFilter) -> Unit,
    onCategorySelected: (Category?) -> Unit,
    onPrioritySelected: (Priority?) -> Unit,
    onSortSelected: (TaskSort) -> Unit,
    onToggleTaskCompletion: (Task) -> Unit,
    onToggleTaskFlagged: (Task) -> Unit,
    onOpenTaskDetail: (TaskWithSubtasks) -> Unit,
    onOpenEditTaskSheet: (TaskWithSubtasks) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onOpenAddTaskSheet: () -> Unit,
    onCloseAddEditSheet: () -> Unit,
    onCloseTaskDetail: () -> Unit,
    onClearCompletedTasks: () -> Unit,
    onSaveTask: (Long, String, String, Priority, Category, Long?, List<String>) -> Unit,
    onToggleSubtask: (Long, Boolean) -> Unit,
    onAddSubtask: (Long, String) -> Unit,
    onDeleteSubtask: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tasks",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                actions = {
                    if (uiState.stats.completedTasks > 0) {
                        IconButton(onClick = onClearCompletedTasks) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = "Clear completed tasks",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                ),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenAddTaskSheet,
                icon = { Icon(Icons.Default.Add, contentDescription = "Add Task") },
                text = { Text("New task") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(2.dp, 2.dp, 2.dp, 2.dp)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Dashboard Banner
            TaskDashboard(stats = uiState.stats)

            // Search & Filter Bar
            FilterBar(
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                selectedFilter = uiState.selectedFilter,
                onFilterSelected = onFilterSelected,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = onCategorySelected,
                selectedPriority = uiState.selectedPriority,
                onPrioritySelected = onPrioritySelected,
                selectedSort = uiState.sortOption,
                onSortSelected = onSortSelected
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Task List or Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (uiState.filteredTasks.isEmpty()) {
                    EmptyState(
                        selectedFilter = uiState.selectedFilter,
                        searchQuery = uiState.searchQuery,
                        onAddTaskClick = onOpenAddTaskSheet,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 88.dp)
                    ) {
                        items(
                            items = uiState.filteredTasks,
                            key = { it.task.id }
                        ) { taskWithSubtasks ->
                            TaskItem(
                                taskWithSubtasks = taskWithSubtasks,
                                onToggleCompletion = { onToggleTaskCompletion(taskWithSubtasks.task) },
                                onToggleFlagged = { onToggleTaskFlagged(taskWithSubtasks.task) },
                                onClick = { onOpenTaskDetail(taskWithSubtasks) },
                                onEditClick = { onOpenEditTaskSheet(taskWithSubtasks) },
                                onDeleteClick = { onDeleteTask(taskWithSubtasks.task.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet for Adding / Editing Tasks
    if (uiState.showAddSheet) {
        AddEditTaskSheet(
            taskToEdit = uiState.taskToEdit,
            onDismiss = onCloseAddEditSheet,
            onSave = onSaveTask
        )
    }

    // Modal Sheet for Task Details & Subtasks Checklist
    if (uiState.selectedTaskForDetail != null) {
        TaskDetailSheet(
            taskWithSubtasks = uiState.selectedTaskForDetail,
            onDismiss = onCloseTaskDetail,
            onToggleCompletion = {
                uiState.selectedTaskForDetail.task.let { onToggleTaskCompletion(it) }
            },
            onToggleSubtask = onToggleSubtask,
            onAddSubtask = { title ->
                uiState.selectedTaskForDetail.task.id.let { onAddSubtask(it, title) }
            },
            onDeleteSubtask = onDeleteSubtask,
            onEditClick = {
                val detail = uiState.selectedTaskForDetail
                onCloseTaskDetail()
                onOpenEditTaskSheet(detail)
            },
            onDeleteTaskClick = {
                val taskId = uiState.selectedTaskForDetail.task.id
                onCloseTaskDetail()
                onDeleteTask(taskId)
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TodoListContentPreview() {
    KukooTheme {
        TodoListContent(
            uiState = TodoUiState(
                isLoading = false,
                stats = TaskStats(totalTasks = 3, completedTasks = 1, todayTasks = 1, highPriorityTasks = 1),
                filteredTasks = listOf(
                    TaskWithSubtasks(
                        task = Task(
                            id = 1,
                            title = "Quarterly Project Review & Report",
                            description = "Finalize Q3 performance metrics and prepare presentation deck.",
                            priority = Priority.HIGH,
                            category = Category.WORK,
                            dueDate = System.currentTimeMillis() + 3600000 * 3,
                            isFlagged = true
                        )
                    ),
                    TaskWithSubtasks(
                        task = Task(
                            id = 2,
                            title = "Weekly Grocery Shopping",
                            description = "Fresh spinach, coffee beans, almond milk",
                            priority = Priority.MEDIUM,
                            category = Category.SHOPPING
                        )
                    )
                )
            ),
            onSearchQueryChange = {},
            onFilterSelected = {},
            onCategorySelected = {},
            onPrioritySelected = {},
            onSortSelected = {},
            onToggleTaskCompletion = {},
            onToggleTaskFlagged = {},
            onOpenTaskDetail = {},
            onOpenEditTaskSheet = {},
            onDeleteTask = {},
            onOpenAddTaskSheet = {},
            onCloseAddEditSheet = {},
            onCloseTaskDetail = {},
            onClearCompletedTasks = {},
            onSaveTask = { _, _, _, _, _, _, _ -> },
            onToggleSubtask = { _, _ -> },
            onAddSubtask = { _, _ -> },
            onDeleteSubtask = {}
        )
    }
}
