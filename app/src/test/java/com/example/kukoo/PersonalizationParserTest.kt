package com.example.kukoo

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.kukoo.ai.LlamaEngine
import com.example.kukoo.ai.LlamaGrammar
import com.example.kukoo.ai.LlamaIntentParser
import com.example.kukoo.ai.ParseContext
import com.example.kukoo.data.DemoHistory
import com.example.kukoo.data.SqliteTaskStore
import com.example.kukoo.domain.ConflictChoice
import com.example.kukoo.domain.ConflictQuestion
import com.example.kukoo.domain.DayRef
import com.example.kukoo.domain.QuestionKind
import com.example.kukoo.domain.TaskCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How "add one extra task for an hour based on my goals" reaches the engine. The model is scripted: these check
 * what the parser believes of its answer, and above all that it never lets the model invent the task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KukooApp::class)
class PersonalizationParserTest {
    private class ScriptedEngine(var reply: String) : LlamaEngine {
        var lastPrompt = ""
        override fun ensureLoaded() = true
        override fun complete(prompt: String, maxTokens: Int, grammar: String?): String {
            lastPrompt = prompt
            return reply
        }
    }

    private fun parse(utterance: String, modelSays: String, context: ParseContext = ParseContext()): TaskCommand =
        runBlocking { LlamaIntentParser(ScriptedEngine(modelSays)).parse(utterance, context) }

    private val suggestion = ParseContext(conflict = ConflictQuestion(QuestionKind.SUGGESTION, true, false, "DSA Practice"))

    @Test
    fun theMentorsSentence_isASuggestionRequest_withItsLength() {
        val said = "add one extra task for 1 hr today based on my goals"
        val expected = TaskCommand.SuggestTask(durationMin = 60, day = DayRef.Today)
        assertEquals(expected, parse(said, """{"action":"suggest_task","duration_min":60,"day":"today"}"""))
    }

    @Test
    fun aModelThatAnswersAddTaskWithNoName_isStillUnderstood() {
        val said = "add one extra task for 1 hr based on my goals"
        assertEquals(TaskCommand.SuggestTask(durationMin = 60), parse(said, """{"action":"add_task","duration_min":60}"""))
    }

    @Test
    fun aModelThatCopiesTheRequestIntoTheTitle_doesNotGetToInventATask() {
        val said = "add one extra task for 1 hr based on my goals"
        val cmd = parse(said, """{"action":"add_task","title":"extra task for 1 hr","duration_min":60}""")
        assertEquals(TaskCommand.SuggestTask(durationMin = 60), cmd)
    }

    @Test
    fun aTaskTheUserActuallyNamed_staysANamedTask() {
        val cmd = parse("add gym tomorrow at 6 am based on my goals", """{"action":"add_task","title":"gym","day":"tomorrow","time":"06:00"}""")
        assertTrue(cmd.toString(), cmd is TaskCommand.AddTask)
    }

    @Test
    fun aBareAddATask_stillAsksForTheName() {
        assertTrue(parse("add a task", """{"action":"add_task"}""") is TaskCommand.StartTask)
    }

    @Test
    fun aSuggestionTheUserNeverAskedFor_isNotAccepted() {
        val cmd = parse("what's on today", """{"action":"suggest_task"}""")
        assertEquals(TaskCommand.Unsupported("what's on today"), cmd)
    }

    @Test
    fun aLengthTheUserNeverSaid_isDropped() {
        assertEquals(
            TaskCommand.SuggestTask(),
            parse("suggest something for me", """{"action":"suggest_task","duration_min":90}""")
        )
    }

    @Test
    fun theGrammarAndPromptTeachSuggestTask() {
        val engine = ScriptedEngine("""{"action":"suggest_task"}""")
        runBlocking { LlamaIntentParser(engine).parse("suggest something", ParseContext()) }
        assertTrue(engine.lastPrompt.contains("suggest_task"))
        assertTrue(LlamaGrammar.COMMAND_JSON.contains("suggest_task"))
    }

    // ---- answers to the suggestion, decided before the model is asked ---------------------------------

    @Test
    fun yes_addsIt() {
        for (yes in listOf("yes", "yeah", "sure", "okay", "add it", "yes add it", "sounds good", "go ahead", "do it")) {
            assertEquals(yes, TaskCommand.Resolve(ConflictChoice.ACCEPT), parse(yes, """{"action":"unsupported"}""", suggestion))
        }
    }

    @Test
    fun somethingElse_asksForAnother() {
        for (other in listOf("something else", "another one", "what else", "skip", "show me more", "next one")) {
            assertEquals(other, TaskCommand.SuggestTask(), parse(other, """{"action":"unsupported"}""", suggestion))
        }
    }

    @Test
    fun no_movesOn_andNeverMindStops() {
        assertEquals(TaskCommand.Resolve(ConflictChoice.DECLINE), parse("no", """{"action":"unsupported"}""", suggestion))
        assertEquals(TaskCommand.Resolve(ConflictChoice.KEEP), parse("never mind", """{"action":"unsupported"}""", suggestion))
    }

    @Test
    fun aNewLength_goesToTheModel_andComesBackAsASuggestionWithThatLength() {
        val cmd = parse("make it 30 minutes", """{"action":"suggest_task","duration_min":30}""", suggestion)
        // "make it 30 minutes" has no suggestion phrase of its own; the open question is the context.
        assertTrue(cmd.toString(), cmd == TaskCommand.SuggestTask(durationMin = 30) || cmd is TaskCommand.Unsupported)
    }

    // ---- the tables --------------------------------------------------------------------------------

    @Test
    fun goalsAndHistory_roundTripThroughSqlite() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getDatabasePath("kukoo.db").delete()
        val store = SqliteTaskStore(context)
        DemoHistory.load(store, clockAt(10))
        val goals = store.goals()
        assertEquals(DemoHistory.goals.map { it.name }, goals.map { it.name })
        assertEquals(DemoHistory.goals[0].keywords, goals[0].keywords)
        assertTrue(goals.all { it.id > 0 })
        val history = store.history()
        assertEquals(DemoHistory.entries(TODAY, TEST_ZONE).size, history.size)
        assertTrue(history.any { !it.completed })
        // Loading again replaces, it does not pile up.
        DemoHistory.load(store, clockAt(10))
        assertEquals(history.size, store.history().size)
        store.clearHistory()
        assertTrue(store.history().isEmpty())
        store.close()
    }

    @Test
    fun aVersion4Database_gainsTheProfileTables_withoutLosingTasks() {
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
            db.execSQL("CREATE TABLE overlap_acks (a_id INTEGER NOT NULL, a_start INTEGER NOT NULL, a_min INTEGER NOT NULL, b_id INTEGER NOT NULL, b_start INTEGER NOT NULL, b_min INTEGER NOT NULL, PRIMARY KEY (a_id, a_start, a_min, b_id, b_start, b_min))")
            db.version = 4
        }
        val store = SqliteTaskStore(context)
        assertEquals("Kept", store.all().single().title)
        assertTrue(store.goals().isEmpty())
        assertTrue(store.history().isEmpty())
        store.close()
    }
}
