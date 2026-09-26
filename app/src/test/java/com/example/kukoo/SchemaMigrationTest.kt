package com.example.kukoo

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.kukoo.data.SqliteTaskStore
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

        val saved = store.insert(Task(title = "New", createdAt = 1, recurrence = Recurrence.WEEKLY, notes = "n"))
        assertEquals(saved, store.get(saved.id))
        store.close()
    }
}
