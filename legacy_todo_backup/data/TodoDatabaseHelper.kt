package com.example.kukoo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.kukoo.model.Category
import com.example.kukoo.model.Priority
import com.example.kukoo.model.Subtask
import com.example.kukoo.model.Task
import com.example.kukoo.model.TaskWithSubtasks
import java.util.Calendar

class TodoDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kukoo_todo.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_TASKS = "tasks"
        private const val TABLE_SUBTASKS = "subtasks"

        // Task columns
        private const val COL_TASK_ID = "id"
        private const val COL_TASK_TITLE = "title"
        private const val COL_TASK_DESC = "description"
        private const val COL_TASK_COMPLETED = "is_completed"
        private const val COL_TASK_PRIORITY = "priority"
        private const val COL_TASK_CATEGORY = "category"
        private const val COL_TASK_DUE_DATE = "due_date"
        private const val COL_TASK_CREATED_AT = "created_at"
        private const val COL_TASK_COMPLETED_AT = "completed_at"
        private const val COL_TASK_FLAGGED = "is_flagged"

        // Subtask columns
        private const val COL_SUBTASK_ID = "id"
        private const val COL_SUBTASK_TASK_ID = "task_id"
        private const val COL_SUBTASK_TITLE = "title"
        private const val COL_SUBTASK_COMPLETED = "is_completed"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTasksTable = """
            CREATE TABLE $TABLE_TASKS (
                $COL_TASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TASK_TITLE TEXT NOT NULL,
                $COL_TASK_DESC TEXT,
                $COL_TASK_COMPLETED INTEGER NOT NULL DEFAULT 0,
                $COL_TASK_PRIORITY TEXT NOT NULL,
                $COL_TASK_CATEGORY TEXT NOT NULL,
                $COL_TASK_DUE_DATE INTEGER,
                $COL_TASK_CREATED_AT INTEGER NOT NULL,
                $COL_TASK_COMPLETED_AT INTEGER,
                $COL_TASK_FLAGGED INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val createSubtasksTable = """
            CREATE TABLE $TABLE_SUBTASKS (
                $COL_SUBTASK_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SUBTASK_TASK_ID INTEGER NOT NULL,
                $COL_SUBTASK_TITLE TEXT NOT NULL,
                $COL_SUBTASK_COMPLETED INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY ($COL_SUBTASK_TASK_ID) REFERENCES $TABLE_TASKS($COL_TASK_ID) ON DELETE CASCADE
            )
        """.trimIndent()

        db.execSQL(createTasksTable)
        db.execSQL(createSubtasksTable)

        seedInitialTasks(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SUBTASKS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_TASKS")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun seedInitialTasks(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        // Task 1: Today due High Priority
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, 17)
        calendar.set(Calendar.MINUTE, 0)
        val today5pm = calendar.timeInMillis

        val task1Id = insertTask(
            db, Task(
                title = "Quarterly Project Review & Report",
                description = "Finalize Q3 performance metrics, summarize client deliverables, and prepare presentation for leadership team.",
                priority = Priority.HIGH,
                category = Category.WORK,
                dueDate = today5pm,
                isFlagged = true,
                createdAt = now - 3600000 * 4
            )
        )
        insertSubtask(db, Subtask(taskId = task1Id, title = "Gather revenue figures from Finance team"))
        insertSubtask(db, Subtask(taskId = task1Id, title = "Draft slide deck executive summary"))
        insertSubtask(db, Subtask(taskId = task1Id, title = "Send meeting invite to stakeholders"))

        // Task 2: Health & Wellness
        val task2Id = insertTask(
            db, Task(
                title = "Annual Health & Fitness Checkup",
                description = "Schedule routine health screening with Dr. Smith and request blood panel results.",
                priority = Priority.MEDIUM,
                category = Category.HEALTH,
                dueDate = now + 86400000 * 2,
                isFlagged = false,
                createdAt = now - 3600000 * 2
            )
        )
        insertSubtask(db, Subtask(taskId = task2Id, title = "Call clinic receptionist to confirm appointment"))
        insertSubtask(db, Subtask(taskId = task2Id, title = "Fill out online pre-visit questionnaire", isCompleted = true))

        // Task 3: Personal Shopping
        val task3Id = insertTask(
            db, Task(
                title = "Weekly Grocery & Household Items",
                description = "Stock up on organic produce, whole grains, coffee beans, and pantry staples.",
                priority = Priority.LOW,
                category = Category.SHOPPING,
                dueDate = now + 86400000,
                isFlagged = false,
                createdAt = now - 3600000 * 1
            )
        )
        insertSubtask(db, Subtask(taskId = task3Id, title = "Fresh spinach & avocados", isCompleted = true))
        insertSubtask(db, Subtask(taskId = task3Id, title = "Dark roast coffee beans"))
        insertSubtask(db, Subtask(taskId = task3Id, title = "Almond milk & Greek yogurt"))

        // Task 4: Education
        val task4Id = insertTask(
            db, Task(
                title = "Complete Jetpack Compose Advanced Architecture Chapter",
                description = "Read chapter on custom layouts, side-effects, and state optimization patterns.",
                priority = Priority.MEDIUM,
                category = Category.EDUCATION,
                dueDate = now + 86400000 * 3,
                isFlagged = true,
                createdAt = now
            )
        )
        insertSubtask(db, Subtask(taskId = task4Id, title = "Review LaunchedEffect vs DisposableEffect"))
        insertSubtask(db, Subtask(taskId = task4Id, title = "Build sample custom Layout composable"))

        // Task 5: Completed task
        val task5Id = insertTask(
            db, Task(
                title = "Pay Monthly Utility & Internet Bills",
                description = "Electricity, high-speed fiber internet, and water bills.",
                isCompleted = true,
                priority = Priority.HIGH,
                category = Category.GENERAL,
                dueDate = now - 86400000,
                completedAt = now - 3600000 * 12,
                createdAt = now - 86400000 * 2
            )
        )
        insertSubtask(db, Subtask(taskId = task5Id, title = "Verify automatic payment receipt", isCompleted = true))
    }

    private fun insertTask(db: SQLiteDatabase, task: Task): Long {
        val values = ContentValues().apply {
            put(COL_TASK_TITLE, task.title)
            put(COL_TASK_DESC, task.description)
            put(COL_TASK_COMPLETED, if (task.isCompleted) 1 else 0)
            put(COL_TASK_PRIORITY, task.priority.name)
            put(COL_TASK_CATEGORY, task.category.name)
            put(COL_TASK_DUE_DATE, task.dueDate)
            put(COL_TASK_CREATED_AT, task.createdAt)
            put(COL_TASK_COMPLETED_AT, task.completedAt)
            put(COL_TASK_FLAGGED, if (task.isFlagged) 1 else 0)
        }
        return db.insert(TABLE_TASKS, null, values)
    }

    private fun insertSubtask(db: SQLiteDatabase, subtask: Subtask): Long {
        val values = ContentValues().apply {
            put(COL_SUBTASK_TASK_ID, subtask.taskId)
            put(COL_SUBTASK_TITLE, subtask.title)
            put(COL_SUBTASK_COMPLETED, if (subtask.isCompleted) 1 else 0)
        }
        return db.insert(TABLE_SUBTASKS, null, values)
    }

    // --- CRUD Operations ---

    fun getAllTasksWithSubtasks(): List<TaskWithSubtasks> {
        val tasks = mutableListOf<Task>()
        val db = readableDatabase

        val cursor = db.query(
            TABLE_TASKS,
            null,
            null,
            null,
            null,
            null,
            "$COL_TASK_CREATED_AT DESC"
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(COL_TASK_ID))
                val title = c.getString(c.getColumnIndexOrThrow(COL_TASK_TITLE))
                val desc = c.getString(c.getColumnIndexOrThrow(COL_TASK_DESC)) ?: ""
                val isCompleted = c.getInt(c.getColumnIndexOrThrow(COL_TASK_COMPLETED)) == 1
                val priorityStr = c.getString(c.getColumnIndexOrThrow(COL_TASK_PRIORITY))
                val categoryStr = c.getString(c.getColumnIndexOrThrow(COL_TASK_CATEGORY))
                val dueDate = if (c.isNull(c.getColumnIndexOrThrow(COL_TASK_DUE_DATE))) null else c.getLong(c.getColumnIndexOrThrow(COL_TASK_DUE_DATE))
                val createdAt = c.getLong(c.getColumnIndexOrThrow(COL_TASK_CREATED_AT))
                val completedAt = if (c.isNull(c.getColumnIndexOrThrow(COL_TASK_COMPLETED_AT))) null else c.getLong(c.getColumnIndexOrThrow(COL_TASK_COMPLETED_AT))
                val isFlagged = c.getInt(c.getColumnIndexOrThrow(COL_TASK_FLAGGED)) == 1

                tasks.add(
                    Task(
                        id = id,
                        title = title,
                        description = desc,
                        isCompleted = isCompleted,
                        priority = Priority.fromString(priorityStr),
                        category = Category.fromString(categoryStr),
                        dueDate = dueDate,
                        createdAt = createdAt,
                        completedAt = completedAt,
                        isFlagged = isFlagged
                    )
                )
            }
        }

        return tasks.map { task ->
            TaskWithSubtasks(
                task = task,
                subtasks = getSubtasksForTask(db, task.id)
            )
        }
    }

    private fun getSubtasksForTask(db: SQLiteDatabase, taskId: Long): List<Subtask> {
        val subtasks = mutableListOf<Subtask>()
        val cursor = db.query(
            TABLE_SUBTASKS,
            null,
            "$COL_SUBTASK_TASK_ID = ?",
            arrayOf(taskId.toString()),
            null,
            null,
            "$COL_SUBTASK_ID ASC"
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(c.getColumnIndexOrThrow(COL_SUBTASK_ID))
                val title = c.getString(c.getColumnIndexOrThrow(COL_SUBTASK_TITLE))
                val isCompleted = c.getInt(c.getColumnIndexOrThrow(COL_SUBTASK_COMPLETED)) == 1
                subtasks.add(
                    Subtask(
                        id = id,
                        taskId = taskId,
                        title = title,
                        isCompleted = isCompleted
                    )
                )
            }
        }
        return subtasks
    }

    fun insertTaskWithSubtasks(task: Task, subtasks: List<String>): Long {
        val db = writableDatabase
        db.beginTransaction()
        var newTaskId: Long = -1
        try {
            val values = ContentValues().apply {
                put(COL_TASK_TITLE, task.title)
                put(COL_TASK_DESC, task.description)
                put(COL_TASK_COMPLETED, if (task.isCompleted) 1 else 0)
                put(COL_TASK_PRIORITY, task.priority.name)
                put(COL_TASK_CATEGORY, task.category.name)
                put(COL_TASK_DUE_DATE, task.dueDate)
                put(COL_TASK_CREATED_AT, task.createdAt)
                put(COL_TASK_COMPLETED_AT, task.completedAt)
                put(COL_TASK_FLAGGED, if (task.isFlagged) 1 else 0)
            }
            newTaskId = db.insert(TABLE_TASKS, null, values)

            for (subtaskTitle in subtasks) {
                if (subtaskTitle.isNotBlank()) {
                    val subValues = ContentValues().apply {
                        put(COL_SUBTASK_TASK_ID, newTaskId)
                        put(COL_SUBTASK_TITLE, subtaskTitle.trim())
                        put(COL_SUBTASK_COMPLETED, 0)
                    }
                    db.insert(TABLE_SUBTASKS, null, subValues)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return newTaskId
    }

    fun updateTask(task: Task) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TASK_TITLE, task.title)
            put(COL_TASK_DESC, task.description)
            put(COL_TASK_COMPLETED, if (task.isCompleted) 1 else 0)
            put(COL_TASK_PRIORITY, task.priority.name)
            put(COL_TASK_CATEGORY, task.category.name)
            put(COL_TASK_DUE_DATE, task.dueDate)
            put(COL_TASK_COMPLETED_AT, task.completedAt)
            put(COL_TASK_FLAGGED, if (task.isFlagged) 1 else 0)
        }
        db.update(TABLE_TASKS, values, "$COL_TASK_ID = ?", arrayOf(task.id.toString()))
    }

    fun updateTaskWithSubtasks(task: Task, subtaskTitles: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            updateTask(task)
            val wanted = subtaskTitles.map { it.trim() }.filter { it.isNotEmpty() }
            val existing = getSubtasksForTask(db, task.id)
            val remaining = wanted.toMutableList()
            for (sub in existing) {
                if (!remaining.remove(sub.title)) deleteSubtask(sub.id)
            }
            for (title in remaining) addSubtask(task.id, title)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateTaskCompletion(taskId: Long, isCompleted: Boolean) {
        val db = writableDatabase
        val completedAt = if (isCompleted) System.currentTimeMillis() else null
        val values = ContentValues().apply {
            put(COL_TASK_COMPLETED, if (isCompleted) 1 else 0)
            put(COL_TASK_COMPLETED_AT, completedAt)
        }
        db.update(TABLE_TASKS, values, "$COL_TASK_ID = ?", arrayOf(taskId.toString()))
    }

    fun updateTaskFlagged(taskId: Long, isFlagged: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TASK_FLAGGED, if (isFlagged) 1 else 0)
        }
        db.update(TABLE_TASKS, values, "$COL_TASK_ID = ?", arrayOf(taskId.toString()))
    }

    fun deleteTask(taskId: Long) {
        val db = writableDatabase
        db.delete(TABLE_TASKS, "$COL_TASK_ID = ?", arrayOf(taskId.toString()))
    }

    fun clearCompletedTasks() {
        val db = writableDatabase
        db.delete(TABLE_TASKS, "$COL_TASK_COMPLETED = 1", null)
    }

    fun addSubtask(taskId: Long, title: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SUBTASK_TASK_ID, taskId)
            put(COL_SUBTASK_TITLE, title.trim())
            put(COL_SUBTASK_COMPLETED, 0)
        }
        return db.insert(TABLE_SUBTASKS, null, values)
    }

    fun updateSubtaskCompletion(subtaskId: Long, isCompleted: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SUBTASK_COMPLETED, if (isCompleted) 1 else 0)
        }
        db.update(TABLE_SUBTASKS, values, "$COL_SUBTASK_ID = ?", arrayOf(subtaskId.toString()))
    }

    fun deleteSubtask(subtaskId: Long) {
        val db = writableDatabase
        db.delete(TABLE_SUBTASKS, "$COL_SUBTASK_ID = ?", arrayOf(subtaskId.toString()))
    }
}
