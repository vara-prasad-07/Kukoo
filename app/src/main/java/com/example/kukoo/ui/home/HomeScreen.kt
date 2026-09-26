package com.example.kukoo.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TimeFormat
import com.example.kukoo.ui.AppState
import com.example.kukoo.ui.theme.Danger
import com.example.kukoo.ui.theme.Success
import com.example.kukoo.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalContext
import com.example.kukoo.ui.UiEvent
import java.time.LocalTime
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Screen 1 of 4: today's tasks, deadlines, simple task actions and the talk button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: AppState,
    format: TimeFormat,
    clock: Clock,
    onTalk: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Task) -> Unit,
    onToggleDone: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onPlan: () -> Unit,
    onRingNow: () -> Unit,
    onRingIn: () -> Unit,
    onDailyCall: () -> Unit,
    onResetDemo: () -> Unit,
    onNoticeShown: () -> Unit,
    events: Flow<UiEvent> = emptyFlow(),
    onUndo: () -> Unit = {},
    onSetTime: (Task, Int, Int) -> Unit = { _, _, _ -> }
) {
    val snackbar = remember { SnackbarHostState() }
    // Messages already offered with an Undo button, so the plain notice for the same change isn't shown twice.
    val undoOffered = remember { mutableSetOf<String>() }
    LaunchedEffect(events) {
        events.collectLatest { event ->
            if (event is UiEvent.ShowUndoSnackbar) {
                undoOffered += event.message
                val result = snackbar.showSnackbar(event.message, actionLabel = "Undo", withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed) onUndo()
            }
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            if (!undoOffered.remove(it)) snackbar.showSnackbar(it)
            onNoticeShown()
        }
    }

    var now by remember { mutableLongStateOf(clock.millis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = clock.millis()
        }
    }
    val sections = remember(state.tasks, now) { groupTasks(state.tasks, now, clock.zone) }
    val overdue = sections.firstOrNull { it.key == SectionKey.OVERDUE }?.tasks.orEmpty()
    val today = sections.firstOrNull { it.key == SectionKey.TODAY }?.tasks.orEmpty()
    val workMinutes = (overdue + today).sumOf { it.durationMin }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add task")
                    }
                    Button(
                        onClick = onTalk,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Talk to Kukoo")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "header") {
                Header(
                    date = LocalDate.now(clock),
                    onPlan = onPlan,
                    onRingNow = onRingNow,
                    onRingIn = onRingIn,
                    onDailyCall = onDailyCall,
                    onResetDemo = onResetDemo
                )
            }
            item(key = "summary") {
                SummaryCard(
                    dueToday = today.size,
                    overdue = overdue.size,
                    workMinutes = workMinutes,
                    nextCall = state.nextCallAt?.let { "Daily call at ${format.clockTime(it)}" }
                )
            }

            if (state.loaded && state.tasks.isEmpty()) {
                item(key = "empty") { EmptyState(onAdd) }
            }

            sections.forEach { section ->
                item(key = "section-${section.key}") {
                    Text(
                        text = section.key.title.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (section.key == SectionKey.OVERDUE) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                    )
                }
                items(section.tasks, key = { it.id }) { task ->
                    TaskRow(
                        task = task,
                        format = format,
                        now = now,
                        zone = clock.zone,
                        onClick = { onEdit(task) },
                        onToggleDone = { onToggleDone(task) },
                        onDelete = { onDelete(task) },
                        onSetTime = { h, m -> onSetTime(task, h, m) }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    date: LocalDate,
    onPlan: () -> Unit,
    onRingNow: () -> Unit,
    onRingIn: () -> Unit,
    onDailyCall: () -> Unit,
    onResetDemo: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Your tasks", style = MaterialTheme.typography.headlineMedium)
        }
        IconButton(onClick = onPlan) {
            Icon(Icons.Default.Timeline, contentDescription = "Plan my day")
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Call options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Simulate incoming call") },
                    leadingIcon = { Icon(Icons.Default.Call, contentDescription = null) },
                    onClick = { menu = false; onRingNow() }
                )
                DropdownMenuItem(
                    text = { Text("Ring in 10 seconds") },
                    leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    onClick = { menu = false; onRingIn() }
                )
                DropdownMenuItem(
                    text = { Text("Daily call time…") },
                    leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    onClick = { menu = false; onDailyCall() }
                )
                DropdownMenuItem(
                    text = { Text("Reset demo tasks") },
                    onClick = { menu = false; onResetDemo() }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(dueToday: Int, overdue: Int, workMinutes: Int, nextCall: String?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Due today", dueToday.toString(), Modifier.weight(1f))
                Stat("Overdue", overdue.toString(), Modifier.weight(1f), highlight = overdue > 0)
                Stat("Work left", if (workMinutes == 0) "–" else shortDuration(workMinutes), Modifier.weight(1.2f))
            }
            if (nextCall != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Alarm, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        nextCall,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = if (highlight) Danger else MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun TaskRow(
    task: Task,
    format: TimeFormat,
    now: Long,
    zone: java.time.ZoneId,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    onDelete: () -> Unit,
    onSetTime: (hour: Int, minute: Int) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val deadline = task.deadline
    val isOverdue = !task.isDone && deadline != null && deadline < now

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleDone) {
                Icon(
                    imageVector = if (task.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (task.isDone) "Mark as not done" else "Mark as done",
                    tint = if (task.isDone) Success else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                    color = if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tapping the time opens the native time picker: change it without the full editor.
                    val timeTap = if (task.isDone) Modifier else Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClickLabel = "Change time") {
                            val start = deadline?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                                ?: LocalTime.of(18, 0)
                            android.app.TimePickerDialog(
                                context,
                                { _, h, m -> onSetTime(h, m) },
                                start.hour, start.minute, false
                            ).show()
                        }
                    Row(modifier = timeTap, verticalAlignment = Alignment.CenterVertically) {
                        if (!task.isDone) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (isOverdue) Danger else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                    Text(
                        text = deadlineLabel(task, format),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverdue) Danger else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    }
                    Text(
                        text = "  ·  ${shortDuration(task.durationMin)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!task.isDone) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(task.priority.color())
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Danger) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

private fun deadlineLabel(task: Task, format: TimeFormat): String {
    val d = task.deadline ?: return "No deadline"
    val day = format.day(d).replaceFirstChar { it.uppercase() }
    return "$day, ${format.clockTime(d)}"
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Nothing to do", style = MaterialTheme.typography.titleMedium)
        Text(
            "Add a task, or tap Talk to Kukoo and say it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onAdd) { Text("Add a task") }
    }
}
