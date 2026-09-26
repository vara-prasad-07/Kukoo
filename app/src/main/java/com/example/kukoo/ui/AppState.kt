package com.example.kukoo.ui

import com.example.kukoo.domain.Outcome
import com.example.kukoo.domain.Plan
import com.example.kukoo.domain.Task

/** The four screens from the implementation document. */
enum class Screen { HOME, INCOMING_CALL, SESSION, PLAN }

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
    val hint: String? = null,
    /** Adjustments that make sense for the task the last reply was about. */
    val quickActions: List<QuickAction> = emptyList()
)

/** [task] == null means "new task". [error] is the engine's reason for refusing a save. */
data class EditorState(val task: Task?, val error: String? = null)

data class AppState(
    val screen: Screen = Screen.HOME,
    val tasks: List<Task> = emptyList(),
    val loaded: Boolean = false,
    val session: SessionState = SessionState(),
    val plan: Plan? = null,
    val planReturnsTo: Screen = Screen.HOME,
    val editor: EditorState? = null,
    val notice: String? = null,
    val nextCallAt: Long? = null,
    /** Show the call above the lock screen (only for calls started by the alarm). */
    val overLockscreen: Boolean = false,
    /** True while the engine has a change it can still undo. */
    val canUndo: Boolean = false
)
