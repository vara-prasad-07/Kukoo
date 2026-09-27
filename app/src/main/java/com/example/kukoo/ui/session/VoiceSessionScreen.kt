package com.example.kukoo.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kukoo.domain.Outcome
import com.example.kukoo.ui.Phase
import com.example.kukoo.ui.SessionState
import com.example.kukoo.ui.Turn
import com.example.kukoo.ui.common.LightSystemBarIcons
import com.example.kukoo.ui.theme.CallAnswer
import com.example.kukoo.ui.theme.CallBackgroundBottom
import com.example.kukoo.ui.theme.CallBackgroundTop
import com.example.kukoo.ui.theme.CallDecline
import com.example.kukoo.ui.theme.Indigo40
import com.example.kukoo.ui.theme.PriorityMedium
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import com.example.kukoo.ui.UiEvent
import java.time.Clock

/** The supported commands. Tapping one sends it, so the demo works even if the microphone does not. */
private val SUGGESTIONS = listOf(
    "What's on today?",
    "Add a task",
    "Plan my day",
    "Move the client deck to tomorrow",
    "Mark the follow-up as done",
    "Delete the expense task",
    "Change the time to 6 PM",
    "Replan my afternoon"
)

private val UndoAmber = Color(0xFFFFB74D)

@Composable
private fun ActionChip(label: String, accent: Color?, enabled: Boolean, onClick: () -> Unit) {
    val base = accent ?: Color.White
    Surface(
        shape = CircleShape,
        color = base.copy(alpha = if (accent != null) 0.22f else 0.16f),
        border = BorderStroke(1.dp, base.copy(alpha = 0.6f)),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = base.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** Screen 3 of 4: the hands-free voice conversation; the assistant listens again after every reply. */
@Composable
fun VoiceSessionScreen(
    session: SessionState,
    clock: Clock,
    onSend: (String) -> Unit,
    onMicTap: () -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    onToggleHandsFree: () -> Unit,
    onEnd: () -> Unit,
    canUndo: Boolean = false,
    events: Flow<UiEvent> = emptyFlow(),
    onUndo: () -> Unit = {}
) {
    LightSystemBarIcons()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(events) {
        events.collectLatest { event ->
            if (event is UiEvent.ShowUndoSnackbar) {
                val result = snackbar.showSnackbar(event.message, actionLabel = "Undo", withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) onUndo()
            }
        }
    }

    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(session.startedAt) {
        while (true) {
            elapsedSeconds = ((clock.millis() - session.startedAt) / 1000).coerceAtLeast(0)
            delay(1000)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(session.turns.size) {
        if (session.turns.isNotEmpty()) listState.animateScrollToItem(session.turns.size - 1)
    }

    var draft by rememberSaveable { mutableStateOf("") }
    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        onSend(text)
    }
    val canSend = session.phase != Phase.THINKING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom)))
            .systemBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Your Tasks", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                text = "${clockText(elapsedSeconds)} · ${statusText(session.phase)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(session.turns, key = { it.id }) { TurnBubble(it) }
        }

        SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 8.dp))

        // Context chips first: Undo when there is something to undo, then adjustments for the last task.
        if (canUndo || session.quickActions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canUndo) {
                    ActionChip("Undo", accent = UndoAmber, enabled = canSend) { onSend("undo") }
                }
                session.quickActions.forEach { action ->
                    ActionChip(action.label, accent = null, enabled = canSend) { onSend(action.text) }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SUGGESTIONS.forEach { suggestion ->
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(enabled = canSend) { onSend(suggestion) }
                ) {
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = if (canSend) 0.9f else 0.4f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Type a request…", color = Color.White.copy(alpha = 0.5f)) },
            singleLine = true,
            enabled = canSend,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = {
                IconButton(onClick = ::send, enabled = canSend && draft.isNotBlank()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White.copy(alpha = if (canSend && draft.isNotBlank()) 1f else 0.35f)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                disabledTextColor = Color.White.copy(alpha = 0.5f),
                focusedBorderColor = Color.White.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                disabledBorderColor = Color.White.copy(alpha = 0.15f),
                cursorColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AutoToggle(on = session.handsFree, onClick = onToggleHandsFree)
            TalkButton(
                listening = session.phase == Phase.LISTENING,
                handsFree = session.handsFree,
                onTap = onMicTap,
                onPress = onMicPress,
                onRelease = onMicRelease
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(CallDecline)
                    .clickable(onClickLabel = "End call", onClick = onEnd),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White)
            }
        }

        Text(
            text = session.hint ?: if (session.micReady) micHint(session.phase, session.handsFree) else "Microphone input is not set up yet — use the box or a suggestion",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp)
        )
    }
}

/** Switches between hold-to-talk and the assistant listening by itself after each reply. */
@Composable
private fun AutoToggle(on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (on) CallAnswer.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f))
            .semantics { contentDescription = if (on) "Hands-free on" else "Hands-free off" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("Auto", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * Hold-to-talk by default: recording lasts as long as the button is held. In hands-free mode the
 * assistant listens by itself, and a tap sends what has been said so far.
 */
@Composable
private fun TalkButton(
    listening: Boolean,
    handsFree: Boolean,
    onTap: () -> Unit,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    // The animation only exists while listening, so an idle screen does no per-frame work.
    val scale = if (listening) {
        val pulse = rememberInfiniteTransition(label = "mic pulse")
        val pulseScale by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "mic pulse scale"
        )
        pulseScale
    } else {
        1f
    }
    val gesture = if (handsFree) {
        Modifier.clickable(onClick = onTap)
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                onPress()
                try {
                    awaitRelease()
                } finally {
                    onRelease()
                }
            })
        }
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (listening) CallAnswer else Indigo40)
            .semantics {
                contentDescription = if (handsFree) "Tap to send or pause" else "Hold to talk"
            }
            .then(gesture),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

@Composable
private fun TurnBubble(turn: Turn) {
    val mine = turn.fromUser
    val problem = turn.outcome == Outcome.REJECTED ||
        turn.outcome == Outcome.UNSUPPORTED ||
        turn.outcome == Outcome.NEEDS_CLARIFICATION
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 18.dp
            ),
            color = if (mine) Indigo40 else Color.White.copy(alpha = 0.10f),
            border = if (problem && !mine) BorderStroke(1.dp, PriorityMedium.copy(alpha = 0.7f)) else null,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

private fun micHint(phase: Phase, handsFree: Boolean): String = when {
    !handsFree -> when (phase) {
        Phase.LISTENING -> "Listening — release to send"
        Phase.SPEAKING -> "Hold the mic to interrupt and talk"
        Phase.THINKING -> "One moment…"
        Phase.IDLE -> "Hold the mic to talk, release to send"
    }
    else -> when (phase) {
        Phase.LISTENING -> "Listening — just talk. Tap the mic when you're done."
        Phase.SPEAKING -> "Tap the mic to interrupt and talk"
        Phase.THINKING -> "One moment…"
        Phase.IDLE -> "Tap the mic to talk"
    }
}

private fun statusText(phase: Phase): String = when (phase) {
    Phase.IDLE -> "Your turn"
    Phase.LISTENING -> "Listening…"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Speaking…"
}

private fun clockText(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
