package com.example.kukoo.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.kukoo.domain.Priority
import com.example.kukoo.domain.Recurrence
import com.example.kukoo.domain.Task
import com.example.kukoo.domain.TimeFormat
import com.example.kukoo.ui.EditorState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val DURATION_PRESETS = listOf(15, 30, 45, 60, 90, 120)

/** Add / edit form. Saving goes through the engine, which returns [EditorState.error] if it refuses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
    editor: EditorState,
    format: TimeFormat,
    clock: Clock,
    onSave: (
        title: String,
        deadline: Long?,
        durationMin: Int,
        priority: Priority,
        notes: String?,
        recurrence: Recurrence
    ) -> Unit,
    onDismiss: () -> Unit
) {
    val existing = editor.task
    val zone = clock.zone

    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var deadline by rememberSaveable(existing?.id) { mutableStateOf(existing?.deadline) }
    var duration by rememberSaveable(existing?.id) { mutableIntStateOf(existing?.durationMin ?: Task.DEFAULT_DURATION_MIN) }
    var priority by rememberSaveable(existing?.id) { mutableStateOf(existing?.priority ?: Priority.MEDIUM) }
    var notes by rememberSaveable(existing?.id) { mutableStateOf(existing?.notes ?: "") }
    var recurrence by rememberSaveable(existing?.id) { mutableStateOf(existing?.recurrence ?: Recurrence.NONE) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(if (existing == null) "New task" else "Edit task", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= Task.MAX_TITLE_LENGTH) title = it },
                label = { Text("What needs doing?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )

            Field("Deadline") {
                val current = deadline
                if (current == null) {
                    OutlinedButton(onClick = { deadline = defaultDeadline(clock) }) { Text("Add deadline") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { pickDate = true },
                            label = { Text(format.day(current).replaceFirstChar { it.uppercase() }) },
                            leadingIcon = { Icon(Icons.Default.Event, contentDescription = null) }
                        )
                        AssistChip(
                            onClick = { pickTime = true },
                            label = { Text(format.clockTime(current)) },
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
                        )
                        IconButton(onClick = { deadline = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove deadline")
                        }
                    }
                }
            }

            Field("Duration") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (DURATION_PRESETS + duration).distinct().sorted().forEach { minutes ->
                        FilterChip(
                            selected = minutes == duration,
                            onClick = { duration = minutes },
                            label = { Text(shortDuration(minutes)) }
                        )
                    }
                }
            }

            Field("Priority") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Priority.entries.forEachIndexed { index, p ->
                        SegmentedButton(
                            selected = p == priority,
                            onClick = { priority = p },
                            shape = SegmentedButtonDefaults.itemShape(index, Priority.entries.size),
                            label = { Text(p.label) }
                        )
                    }
                }
            }

            Field("Repeats") {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Recurrence.entries.forEach { r ->
                        FilterChip(
                            selected = r == recurrence,
                            onClick = { recurrence = r },
                            label = { Text(r.label) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { if (it.length <= Task.MAX_NOTES_LENGTH) notes = it },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth()
            )

            editor.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    onClick = { onSave(title, deadline, duration, priority, notes.ifBlank { null }, recurrence) },
                    enabled = title.isNotBlank()
                ) { Text("Save") }
            }
        }
    }

    if (pickDate) {
        val currentDate = deadline?.let { localDate(it, zone) } ?: LocalDate.now(clock)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = currentDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { picked ->
                        val date = Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate()
                        val time = deadline?.let { localTime(it, zone) } ?: LocalTime.of(18, 0)
                        deadline = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }

    if (pickTime) {
        val currentTime = deadline?.let { localTime(it, zone) } ?: LocalTime.of(18, 0)
        val pickerState = rememberTimePickerState(currentTime.hour, currentTime.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val date = deadline?.let { localDate(it, zone) } ?: LocalDate.now(clock)
                    deadline = LocalDateTime.of(date, LocalTime.of(pickerState.hour, pickerState.minute))
                        .atZone(zone).toInstant().toEpochMilli()
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Today at 6 PM, or tomorrow at 6 PM if that has already passed. */
private fun defaultDeadline(clock: Clock): Long {
    val zone = clock.zone
    var candidate = LocalDate.now(clock).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    if (candidate <= clock.millis()) {
        candidate = LocalDate.now(clock).plusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    }
    return candidate
}

private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
private fun localTime(millis: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
