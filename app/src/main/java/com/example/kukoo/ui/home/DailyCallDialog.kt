package com.example.kukoo.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/** Picks the time of the daily "Your Tasks" call. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCallDialog(
    current: LocalTime?,
    canUseFullScreen: Boolean,
    onOpenFullScreenSettings: () -> Unit,
    onSet: (LocalTime) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit
) {
    val initial = current ?: LocalTime.of(9, 0)
    val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily call") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Kukoo will ring you at this time every day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TimePicker(state = picker)
                if (!canUseFullScreen) {
                    Text(
                        "To ring over the lock screen, allow full-screen notifications for Kukoo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onOpenFullScreenSettings) { Text("Open settings") }
                }
                if (current != null) {
                    TextButton(onClick = onTurnOff) {
                        Text("Turn off daily call", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(LocalTime.of(picker.hour, picker.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
