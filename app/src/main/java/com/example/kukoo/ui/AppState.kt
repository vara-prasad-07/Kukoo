package com.example.kukoo.ui

import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Plan
import com.example.kukoo.domain.Task

/** The four screens from the implementation document, plus one-off on-device model setup. */
enum class Screen { HOME, INCOMING_CALL, SESSION, PLAN, SETUP }

/** Turn-based voice loop: listen -> think -> speak -> idle. */
enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class Turn(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val outcome: Outcome? = null
)

/** One-shot events for the UI (things to show once, not state to render). */
sealed class UiEvent {
    /** A task change just succeeded; offer to undo it. */
    data class ShowUndoSnackbar(val message: String) : UiEvent()
}

/** A tappable suggestion in the voice session: [text] is sent exactly as if it had been typed. */
data class QuickAction(val label: String, val text: String)

data class SessionState(
    val turns: List<Turn> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val startedAt: Long = 0,
    /** True once a real speech model is installed; until then tap-to-talk explains the fallback. */
    val micReady: Boolean = false,
    /** Hold-to-talk by default; when true the assistant listens by itself after every reply. */
    val handsFree: Boolean = false,
    val hint: String? = null,
    /** Adjustments that make sense for the task the last reply was about. */
    val quickActions: List<QuickAction> = emptyList()
)

/** [task] == null means "new task". [error] is the engine's reason for refusing a save. */
data class EditorState(val task: Task?, val error: String? = null)

/**
 * One downloadable model on the setup screen. [fraction] is only meaningful while downloading;
 * [detail] carries the human-readable size, state or failure reason.
 */
data class ModelRow(
    val id: String,
    val label: String,
    val detail: String,
    val fraction: Float = 0f,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val error: String? = null
)

/** First-run (or on-demand) download of the on-device models. */
data class SetupState(
    val speech: List<ModelRow> = emptyList(),
    val llm: ModelRow? = null,
    val busy: Boolean = false,
    val micGranted: Boolean = false,
    val chipset: String = ""
)

data class AppState(
    val screen: Screen = Screen.HOME,
    val tasks: List<Task> = emptyList(),
    val loaded: Boolean = false,
    val session: SessionState = SessionState(),
    val plan: Plan? = null,
    val planReturnsTo: Screen = Screen.HOME,
    val editor: EditorState? = null,
    val setup: SetupState = SetupState(),
    val notice: String? = null,
    val nextCallAt: Long? = null,
    /** Show the call above the lock screen (only for calls started by the alarm). */
    val overLockscreen: Boolean = false,
    /** Set when the current call is a reminder for one task; null for the daily / manual call. */
    val callTaskId: Long? = null,
    /** True while the engine has a change it can still undo. */
    val canUndo: Boolean = false
)
