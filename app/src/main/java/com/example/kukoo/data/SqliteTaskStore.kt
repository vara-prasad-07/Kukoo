package com.example.kukoo.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.kukoo.domain.Goal
import com.example.kukoo.domain.GoalKind
import com.example.kukoo.domain.HistoryEntry
import com.example.kukoo.domain.OverlapAck
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.ProfileStore
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TaskStore

/** Local SQLite persistence for tasks: title, deadline, duration, priority, status, recurrence, notes. */
class SqliteTaskStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION), TaskStore, ProfileStore {

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
        createProfileTables(db)
    }

    /** What the assistant learns from: the user's goals, and what they did (or skipped) before. */
    private fun createProfileTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $GOALS (g_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT NOT NULL, " +
                "keywords TEXT NOT NULL, weight INTEGER NOT NULL, daily_target_min INTEGER NOT NULL, " +
                "starter_title TEXT, starter_min INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $HISTORY (h_id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, " +
                "started_at INTEGER NOT NULL, duration_min INTEGER NOT NULL, priority TEXT NOT NULL, completed INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_started ON $HISTORY(started_at)")
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
        if (oldVersion < 5) createProfileTables(db)
    }

    override fun goals(): List<Goal> {
        val out = mutableListOf<Goal>()
        readableDatabase.query(GOALS, null, null, null, null, null, "g_id ASC").use { c ->
            while (c.moveToNext()) {
                out += Goal(
                    id = c.getLong(c.getColumnIndexOrThrow("g_id")),
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    kind = runCatching { GoalKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))) }.getOrDefault(GoalKind.GOAL),
                    keywords = c.getString(c.getColumnIndexOrThrow("keywords")).split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    weight = c.getInt(c.getColumnIndexOrThrow("weight")),
                    dailyTargetMin = c.getInt(c.getColumnIndexOrThrow("daily_target_min")),
                    starterTitle = c.getColumnIndexOrThrow("starter_title").let { if (c.isNull(it)) null else c.getString(it) },
                    starterMin = c.getInt(c.getColumnIndexOrThrow("starter_min"))
                )
            }
        }
        return out
    }

    override fun replaceGoals(goals: List<Goal>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(GOALS, null, null)
            goals.forEach { g ->
                db.insertOrThrow(GOALS, null, ContentValues().apply {
                    put("name", g.name); put("kind", g.kind.name); put("keywords", g.keywords.joinToString(","))
                    put("weight", g.weight); put("daily_target_min", g.dailyTargetMin)
                    put("starter_title", g.starterTitle); put("starter_min", g.starterMin)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun history(): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        readableDatabase.query(HISTORY, null, null, null, null, null, "started_at ASC").use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("h_id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                    durationMin = c.getInt(c.getColumnIndexOrThrow("duration_min")),
                    priority = Priority.fromName(c.getString(c.getColumnIndexOrThrow("priority"))),
                    completed = c.getInt(c.getColumnIndexOrThrow("completed")) != 0
                )
            }
        }
        return out
    }

    override fun addHistory(entries: List<HistoryEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            entries.forEach { e ->
                db.insertOrThrow(HISTORY, null, ContentValues().apply {
                    put("title", e.title); put("started_at", e.startedAt); put("duration_min", e.durationMin)
                    put("priority", e.priority.name); put("completed", if (e.completed) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clearHistory() {
        writableDatabase.delete(HISTORY, null, null)
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
        const val DB_VERSION = 5
        const val TABLE = "tasks"
        const val ACKS = "overlap_acks"
        const val GOALS = "goals"
        const val HISTORY = "task_history"
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
