package com.example.kukoo

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.example.kukoo.domain.PlanScope
import com.example.kukoo.domain.Planner
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TaskStatus
import com.example.kukoo.domain.TimeFormat
import com.example.kukoo.domain.TimeRange
import com.example.kukoo.ui.AppState
import com.example.kukoo.ui.Phase
import com.example.kukoo.ui.SessionState
import com.example.kukoo.ui.Turn
import com.example.kukoo.ui.call.IncomingCallScreen
import com.example.kukoo.ui.home.HomeScreen
import com.example.kukoo.ui.plan.PlanScreen
import com.example.kukoo.ui.session.VoiceSessionScreen
import com.example.kukoo.ui.theme.KukooTheme
import com.example.kukoo.domain.Outcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders each of the four screens on the JVM (no emulator), checks the key text is present,
 * and writes PNGs to app/build/screenshots for eyeballing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val clock = clockAt(15)
    private val format = TimeFormat(clock)

    private val tasks = listOf(
        Task(1, "Send invoice", today(9), 20, Priority.LOW, createdAt = 0),
        Task(2, "Follow-up", today(16, 30), 30, Priority.MEDIUM, createdAt = 0),
        Task(3, "Client Deck", today(17, 20), 60, Priority.HIGH, createdAt = 0),
        Task(4, "Design Review", today(18, 30), 45, Priority.MEDIUM, createdAt = 0),
        Task(5, "Expense report", tomorrow(12), 20, Priority.LOW, createdAt = 0),
        Task(6, "Book flights", null, 30, Priority.LOW, createdAt = 0),
        Task(7, "Update resume", today(11), 30, Priority.MEDIUM, TaskStatus.DONE, createdAt = 0, completedAt = today(12))
    )

    private fun shot(name: String) {
        // captureToImage()'s forceRedraw waits on a native draw callback that only lands once a
        // handful of frames have actually been pumped. Screens here run endless ring/clock
        // animations, so autoAdvance (run-until-idle) would spin those forever; instead we pump a
        // small, bounded number of frames by hand -- enough for the redraw callback, not enough to
        // drift the test clock past session-duration-sensitive UI (e.g. quick-reply chips).
        // On machines without a working software GL/Skia backend for Robolectric's NATIVE graphics
        // mode the callback can still never land -- kept best-effort since the onNodeWithText
        // assertions above are the real correctness check, and the PNG is only for eyeballing.
        try {
            repeat(5) { rule.mainClock.advanceTimeByFrame() }
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val dir = File("build/screenshots").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            println("Skipping screenshot '$name': ${e.message}")
        }
    }

    /**
     * The screens run endless timers and animations (call ring pulse, clocks). The test clock is
     * therefore driven by hand: the compose rule would otherwise never become idle.
     */
    private fun themed(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { KukooTheme(darkTheme = dark) { content() } }
        rule.mainClock.advanceTimeBy(600)
    }

    private fun tap(text: String) {
        // The suggestion chips sit in a horizontally scrolling Row, so a chip past the fold has no
        // on-screen center for performClick() to land on. performScrollTo() is not the answer: it
        // waits for its scroll animation to settle, which never happens while the test clock is
        // paused, and it throws outright on screens with nothing scrollable. Both the chips and the
        // call buttons keep their label inside the clickable, so the merged-tree node matched here
        // carries OnClick -- invoking that is position-independent and works off-screen.
        rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(100)
    }

    private fun home(dark: Boolean, state: AppState = AppState(tasks = tasks, loaded = true, nextCallAt = tomorrow(9))) =
        themed(dark) {
            HomeScreen(
                state = state, format = format, clock = clock,
                onTalk = {}, onAdd = {}, onEdit = {}, onToggleDone = {}, onDelete = {},
                onPlan = {}, onRingNow = {}, onRingIn = {}, onDailyCall = {}, onOpenSetup = {},
                onResetDemo = {}, onNoticeShown = {}
            )
        }

    @Test
    fun home_light() {
        home(dark = false)
        rule.onNodeWithText("Your tasks").assertExists()
        rule.onNodeWithText("Client Deck").assertExists()
        rule.onNodeWithText("OVERDUE").assertExists()
        rule.onNodeWithText("Talk to Kukoo").assertExists()
        rule.onNodeWithText("Daily call at 9 AM").assertExists()
        shot("1_home_light")
    }

    @Test
    fun home_dark() {
        home(dark = true)
        rule.onNodeWithText("Follow-up").assertExists()
        shot("1_home_dark")
    }

    @Test
    fun home_empty() {
        home(dark = false, state = AppState(loaded = true))
        rule.onNodeWithText("Nothing to do").assertExists()
        shot("1_home_empty")
    }

    @Test
    fun incomingCall() {
        themed { IncomingCallScreen(clock = clock, format = format, onAnswer = {}, onDecline = {}) }
        rule.onNodeWithText("Your Tasks").assertExists()
        rule.onNodeWithText("Incoming call…").assertExists()
        rule.onNodeWithText("Answer").assertExists()
        rule.onNodeWithText("Decline").assertExists()
        shot("2_incoming_call")
    }

    @Test
    fun answerAndDeclineButtons_fireCallbacks() {
        var answered = 0
        var declined = 0
        themed { IncomingCallScreen(clock, format, onAnswer = { answered++ }, onDecline = { declined++ }) }
        tap("Answer")
        assertEquals(1, answered)
        assertEquals(0, declined)
        tap("Decline")
        assertEquals(1, declined)
    }

    @Test
    fun voiceSession() {
        val session = SessionState(
            turns = listOf(
                Turn(1, false, "Good afternoon. You have 2 tasks due today: Follow-up at 4:30 PM and Client Deck at 5:20 PM. That is about 1 hour 30 minutes of work. What would you like to do?"),
                Turn(2, true, "Move the client deck to tomorrow"),
                Turn(3, false, "Done. Client Deck is now due tomorrow at 5:20 PM.", Outcome.OK),
                Turn(4, true, "Delete the payroll task"),
                Turn(5, false, "I couldn't find a task called payroll.", Outcome.REJECTED)
            ),
            phase = Phase.IDLE,
            startedAt = clock.millis() - 72_000,
            micReady = false
        )
        var sent: String? = null
        themed { VoiceSessionScreen(session, clock, onSend = { sent = it }, onMicPress = {}, onMicRelease = {}, onEnd = {}) }
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("Replan my afternoon").assertExists()
        rule.onNodeWithText("Your turn", substring = true).assertExists()
        shot("3_voice_session")
        tap("Mark the follow-up as done")
        assertEquals("Mark the follow-up as done", sent)
    }

    @Test
    fun planResult_withConflictSplitAndGap() {
        val planner = Planner()
        val plan = planner.plan(
            tasks = listOf(
                Task(2, "Follow-up", today(16, 30), 30, Priority.MEDIUM, createdAt = 0),
                Task(3, "Client Deck", today(17, 20), 60, Priority.HIGH, createdAt = 0),
                Task(4, "Design Review", today(19), 45, Priority.MEDIUM, createdAt = 0),
                Task(6, "Write summary", today(19), 90, Priority.LOW, createdAt = 0)
            ),
            window = TimeRange(today(12), today(20)),
            scope = PlanScope.AFTERNOON,
            date = TODAY,
            now = today(15),
            fixedBlocks = listOf(TimeRange(today(17, 30), today(18)))
        )
        themed { PlanScreen(plan, format, doneLabel = "Back to call", onBack = {}) }
        rule.onNodeWithText("Afternoon plan").assertExists()
        rule.onNodeWithText("Conflict").assertExists()
        rule.onNodeWithText("Back to call").assertExists()
        shot("4_plan_result")
    }

    @Test
    fun planResult_empty() {
        val plan = Planner().plan(emptyList(), TimeRange(today(9), today(18)), PlanScope.DAY, TODAY, today(9))
        themed { PlanScreen(plan, format, doneLabel = "Done", onBack = {}) }
        rule.onNodeWithText("There are no open tasks to schedule.").assertExists()
    }
}
