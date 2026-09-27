package com.example.kukoo.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Download
import com.example.kukoo.ui.ModelRow
import com.example.kukoo.ui.SetupState

/**
 * One-time download of everything the assistant needs to run offline: the speech models and the
 * NPU language model. Reachable from the Home menu, so it can be re-opened to add the optional
 * natural voice or to retry a failed pull.
 */
@Composable
fun ModelSetupScreen(
    state: SetupState,
    onBack: () -> Unit,
    onDownloadSpeech: () -> Unit,
    onDownloadSpeechModel: (String) -> Unit,
    onDownloadLlm: () -> Unit,
    onRequestMic: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text("On-device AI", style = MaterialTheme.typography.headlineSmall)
        }

        Text(
            "Everything runs on this phone. These downloads happen once — after that the assistant " +
                "works with the network off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp),
        )

        if (!state.micGranted) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Microphone access is off", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Speech recognition runs locally, but Android still needs the permission.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Button(onClick = onRequestMic) { Text("Allow microphone") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Section(title = "Language model") {
            state.llm?.let { ModelRowCard(it) }
            Text(
                if (state.chipset.isNotEmpty()) "Target chipset: ${state.chipset}" else "Detecting chipset…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Several GB, and it needs a Snapdragon 8 Elite or 8 Elite Gen 5. Until it is " +
                    "installed, commands are understood by the built-in rule parser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(
                onClick = onDownloadLlm,
                enabled = !state.busy && state.llm?.installed != true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.llm?.installed == true) "Installed" else "Download language model")
            }
        }

        Spacer(Modifier.height(24.dp))

        Section(title = "Speech") {
            state.speech.forEach { row ->
                ModelRowCard(
                    row,
                    onDownload = if (!row.installed && !row.downloading && !state.busy) {
                        { onDownloadSpeechModel(row.id) }
                    } else null,
                )
            }
            Spacer(Modifier.height(12.dp))
            val allDone = state.speech.isNotEmpty() && state.speech.all { it.installed }
            OutlinedButton(
                onClick = onDownloadSpeech,
                enabled = !state.busy && !allDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (allDone) "All voices installed" else "Download all missing")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun ModelRowCard(row: ModelRow, onDownload: (() -> Unit)? = null) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onDownload != null) Modifier.clickable(onClickLabel = "Download ${row.label}", onClick = onDownload) else Modifier),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.label, fontWeight = FontWeight.Medium)
                    Text(
                        row.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDownload != null) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Tap to download",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (row.installed) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Installed",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (row.downloading) {
                Spacer(Modifier.height(10.dp))
                // An indeterminate bar while the total size is still unknown.
                if (row.fraction > 0f) {
                    LinearProgressIndicator(
                        progress = { row.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
