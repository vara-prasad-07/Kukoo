package com.example.kukoo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.kukoo.model.TaskFilter

@Composable
fun EmptyState(
    selectedFilter: TaskFilter,
    searchQuery: String,
    onAddTaskClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when {
        searchQuery.isNotEmpty() -> "No matching tasks found"
        selectedFilter == TaskFilter.COMPLETED -> "No completed tasks yet"
        selectedFilter == TaskFilter.FLAGGED -> "No starred tasks"
        selectedFilter == TaskFilter.TODAY -> "All clear for today!"
        selectedFilter == TaskFilter.UPCOMING -> "No upcoming tasks scheduled"
        else -> "No tasks created yet"
    }

    val subtitle = when {
        searchQuery.isNotEmpty() -> "Try searching with a different term or clear filters."
        selectedFilter == TaskFilter.COMPLETED -> "Tasks you complete will appear here."
        selectedFilter == TaskFilter.FLAGGED -> "Star important tasks to keep them easily accessible."
        selectedFilter == TaskFilter.TODAY -> "Enjoy your day or add new goals for today."
        else -> "Stay organized and productive by adding your first task below."
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(96.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.TaskAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (searchQuery.isEmpty() && selectedFilter != TaskFilter.COMPLETED) {
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onAddTaskClick,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text("Create New Task")
            }
        }
    }
}
