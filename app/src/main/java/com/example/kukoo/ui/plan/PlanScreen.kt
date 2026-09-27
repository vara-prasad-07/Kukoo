package com.example.kukoo.ui.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.kukoo.domain.Conflict
import com.example.kukoo.domain.MILLIS_PER_MINUTE
import com.example.kukoo.domain.Plan
import com.example.kukoo.domain.ScheduledBlock
import com.example.kukoo.domain.TimeFormat
import com.example.kukoo.ui.home.shortDuration
import com.example.kukoo.ui.theme.Danger
import com.example.kukoo.ui.theme.color

/** Screen 4 of 4: the computed timeline plus any deadline conflict. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    plan: Plan,
    format: TimeFormat,
    doneLabel: String,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${plan.scope.label.replaceFirstChar { it.uppercase() }} plan",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            format.day(plan.date).replaceFirstChar { it.uppercase() } + " · " + plan.source.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp)
                ) { Text(doneLabel) }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
        ) {
            item(key = "summary") {
                Text(
                    text = if (plan.blocks.isEmpty()) "Nothing scheduled" else
                        "${plan.blocks.map { it.taskId }.distinct().size} tasks · ${shortDuration(plan.totalMinutes)} of work",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            if (plan.conflicts.isNotEmpty()) {
                item(key = "conflicts") { ConflictCard(plan.conflicts, format) }
            }

            if (plan.isEmpty) {
                item(key = "empty") {
                    Text(
                        "Nothing is scheduled.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            }

            if (plan.unplaced.isNotEmpty()) {
                item(key = "unplaced") {
                    Text(
                        "No room today for " + plan.unplaced.joinToString(", ") + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }

            itemsIndexed(plan.blocks, key = { i, b -> "${b.taskId}-${b.start}-$i" }) { index, block ->
                val previous = plan.blocks.getOrNull(index - 1)
                val gapMinutes = previous?.let { ((block.start - it.end) / MILLIS_PER_MINUTE).toInt() } ?: 0
                if (gapMinutes > 0) FreeGap(gapMinutes)
                BlockRow(
                    block = block,
                    format = format,
                    isLast = index == plan.blocks.lastIndex
                )
            }
        }
    }
}

@Composable
private fun ConflictCard(conflicts: List<Conflict>, format: TimeFormat) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Danger.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, Danger.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (conflicts.size == 1) "Conflict" else "${conflicts.size} conflicts",
                    style = MaterialTheme.typography.titleMedium,
                    color = Danger
                )
            }
            conflicts.forEach { Text(conflictText(it, format), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun conflictText(c: Conflict, format: TimeFormat): String =
    "${c.firstTitle} and ${c.secondTitle} overlap by ${format.minutes(c.minutes)}, " +
        "from ${format.clockTime(c.start)} to ${format.clockTime(c.end)}."

@Composable
private fun FreeGap(minutes: Int) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Spacer(Modifier.width(92.dp))
        Text(
            "Free · ${shortDuration(minutes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BlockRow(block: ScheduledBlock, format: TimeFormat, isLast: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(Modifier.width(72.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(format.clockTime(block.start), style = MaterialTheme.typography.labelLarge)
            Text(
                format.clockTime(block.end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (!isLast) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )
            }
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(block.priority.color())
            )
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, if (block.overlapsWith.isNotEmpty()) Danger.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(block.title, style = MaterialTheme.typography.titleMedium)
                val suggested = if (block.proposed) " · suggested time" else ""
                Text(
                    shortDuration(block.minutes) + suggested,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (block.overlapsWith.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Danger, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Overlaps " + block.overlapsWith.distinct().joinToString(", "),
                            style = MaterialTheme.typography.labelMedium,
                            color = Danger
                        )
                    }
                }
            }
        }
    }
}
