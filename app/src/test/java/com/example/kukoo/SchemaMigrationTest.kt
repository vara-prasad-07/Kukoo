package com.example.kukoo

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.OverlapAck
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A version-1 database (no recurrence / notes) upgrades in place without losing tasks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KukooApp::class)
class SchemaMigrationTest {
    @Test
    fun upgradeFromV1_keepsExistingTasks_andAddsNewColumns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("kukoo.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, deadline_at INTEGER, " +
                    "duration_min INTEGER NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, completed_at INTEGER)"
            )
            db.execSQL("INSERT INTO tasks (title, deadline_at, duration_min, priority, status, created_at) VALUES ('Old task', 1000, 30, 'HIGH', 'OPEN', 5)")
            db.version = 1
        }

        val store = SqliteTaskStore(context)
        val old = store.all().single()
        assertEquals("Old task", old.title)
        assertEquals(Recurrence.NONE, old.recurrence)
        assertNull(old.notes)
        assertNull(old.reminderMin)

        val saved = store.insert(
            Task(title = "New", deadline = 900_000, createdAt = 1, recurrence = Recurrence.WEEKLY, notes = "n", reminderMin = 15)
        )
        assertEquals(15, store.get(saved.id)?.reminderMin)
        assertEquals(saved, store.get(saved.id))
        store.close()
    }

    /** A version-3 database (tasks, but no remembered overlaps) gains the overlap table without losing tasks. */
    @Test
    fun upgradeFromV3_addsTheOverlapTable_andKeepsTasks() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("kukoo.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, deadline_at INTEGER, " +
                    "duration_min INTEGER NOT NULL, priority TEXT NOT NULL, status TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, completed_at INTEGER, recurrence TEXT NOT NULL DEFAULT 'NONE', " +
                    "notes TEXT, reminder_min INTEGER)"
            )
            db.execSQL("INSERT INTO tasks (title, deadline_at, duration_min, priority, status, created_at) VALUES ('Kept', 1000, 30, 'LOW', 'OPEN', 5)")
            db.version = 3
        }
        val store = SqliteTaskStore(context)
        assertEquals("Kept", store.all().single().title)
        assertEquals(emptySet<OverlapAck>(), store.acks())

        val ack = OverlapAck(1, 1000, 30, 2, 1500, 30)
        store.addAck(ack)
        store.addAck(ack) // the same decision twice is still one row
        assertEquals(setOf(ack), store.acks())

        // Removing either task forgets the decision.
        store.delete(1)
        assertEquals(emptySet<OverlapAck>(), store.acks())
        store.close()
    }
}
