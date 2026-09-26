package com.example.kukoo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TaskStore

/** Local SQLite persistence for tasks: title, deadline, duration, priority, status, recurrence, notes. */
class SqliteTaskStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), TaskStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $TITLE TEXT NOT NULL,
                $DEADLINE INTEGER,
                $DURATION INTEGER NOT NULL,
                $PRIORITY TEXT NOT NULL,
                $STATUS TEXT NOT NULL,
                $CREATED INTEGER NOT NULL,
                $COMPLETED INTEGER,
                $RECURRENCE TEXT NOT NULL DEFAULT 'NONE',
                $NOTES TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tasks_deadline ON $TABLE($DEADLINE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations only ever add columns, so existing tasks are preserved.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $RECURRENCE TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $NOTES TEXT")
        }
    }

    override fun all(): List<Task> {
        val out = mutableListOf<Task>()
        readableDatabase.query(TABLE, null, null, null, null, null, "$ID ASC").use { c ->
            while (c.moveToNext()) out += c.toTask()
        }
        return out
    }

    override fun get(id: Long): Task? =
        readableDatabase.query(TABLE, null, "$ID = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toTask() else null
        }

    override fun insert(task: Task): Task {
        val id = writableDatabase.insertOrThrow(TABLE, null, task.toValues())
        return task.copy(id = id)
    }

    override fun update(task: Task) {
        val rows = writableDatabase.update(TABLE, task.toValues(), "$ID = ?", arrayOf(task.id.toString()))
        check(rows == 1) { "Task ${task.id} does not exist" }
    }

    override fun delete(id: Long): Boolean =
        writableDatabase.delete(TABLE, "$ID = ?", arrayOf(id.toString())) > 0

    override fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun Task.toValues() = ContentValues().apply {
        put(TITLE, title)
        put(DEADLINE, deadline)
        put(DURATION, durationMin)
        put(PRIORITY, priority.name)
        put(STATUS, status.name)
        put(CREATED, createdAt)
        put(COMPLETED, completedAt)
        put(RECURRENCE, recurrence.name)
        put(NOTES, notes)
    }

    private fun Cursor.toTask(): Task {
        fun longOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
        return Task(
            id = getLong(getColumnIndexOrThrow(ID)),
            title = getString(getColumnIndexOrThrow(TITLE)),
            deadline = longOrNull(DEADLINE),
            durationMin = getInt(getColumnIndexOrThrow(DURATION)),
            priority = Priority.fromName(getString(getColumnIndexOrThrow(PRIORITY))),
            status = runCatching { TaskStatus.valueOf(getString(getColumnIndexOrThrow(STATUS))) }
                .getOrDefault(TaskStatus.OPEN),
            createdAt = getLong(getColumnIndexOrThrow(CREATED)),
            completedAt = longOrNull(COMPLETED),
            recurrence = Recurrence.fromName(getString(getColumnIndexOrThrow(RECURRENCE))),
            notes = getColumnIndexOrThrow(NOTES).let { if (isNull(it)) null else getString(it) }
        )
    }

    private companion object {
        const val DB_NAME = "kukoo.db"
        const val DB_VERSION = 2
        const val TABLE = "tasks"
        const val ID = "id"
        const val TITLE = "title"
        const val DEADLINE = "deadline_at"
        const val DURATION = "duration_min"
        const val PRIORITY = "priority"
        const val STATUS = "status"
        const val CREATED = "created_at"
        const val COMPLETED = "completed_at"
        const val RECURRENCE = "recurrence"
        const val NOTES = "notes"
    }
}
