package com.example.kukoo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.kukoo.domain.OverlapAck
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
                $NOTES TEXT,
                $REMINDER INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tasks_deadline ON $TABLE($DEADLINE)")
        createAckTable(db)
    }

    private fun createAckTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $ACKS (a_id INTEGER NOT NULL, a_start INTEGER NOT NULL, a_min INTEGER NOT NULL, " +
                "b_id INTEGER NOT NULL, b_start INTEGER NOT NULL, b_min INTEGER NOT NULL, " +
                "PRIMARY KEY (a_id, a_start, a_min, b_id, b_start, b_min))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations only ever add columns, so existing tasks are preserved.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $RECURRENCE TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $NOTES TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $REMINDER INTEGER")
        }
        if (oldVersion < 4) createAckTable(db)
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

    override fun delete(id: Long): Boolean {
        val gone = writableDatabase.delete(TABLE, "$ID = ?", arrayOf(id.toString())) > 0
        writableDatabase.delete(ACKS, "a_id = ? OR b_id = ?", arrayOf(id.toString(), id.toString()))
        return gone
    }

    override fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
        writableDatabase.delete(ACKS, null, null)
    }

    override fun acks(): Set<OverlapAck> {
        val out = mutableSetOf<OverlapAck>()
        readableDatabase.query(ACKS, null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                out += OverlapAck(
                    c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3), c.getLong(4), c.getInt(5)
                )
            }
        }
        return out
    }

    override fun addAck(ack: OverlapAck) {
        writableDatabase.insertWithOnConflict(
            ACKS, null,
            ContentValues().apply {
                put("a_id", ack.aId); put("a_start", ack.aStart); put("a_min", ack.aMin)
                put("b_id", ack.bId); put("b_start", ack.bStart); put("b_min", ack.bMin)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
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
        put(REMINDER, reminderMin)
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
            notes = getColumnIndexOrThrow(NOTES).let { if (isNull(it)) null else getString(it) },
            reminderMin = longOrNull(REMINDER)?.toInt()
        )
    }

    private companion object {
        const val DB_NAME = "kukoo.db"
        const val DB_VERSION = 4
        const val TABLE = "tasks"
        const val ACKS = "overlap_acks"
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
        const val REMINDER = "reminder_min"
    }
}
