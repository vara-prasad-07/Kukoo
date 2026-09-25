package com.example.kukoo.model

import androidx.compose.ui.graphics.Color

enum class Priority(val label: String, val level: Int, val color: Color) {
    HIGH("High", 3, Color(0xFFE53935)),      // Vibrant Red
    MEDIUM("Medium", 2, Color(0xFFFB8C00)),  // Amber / Orange
    LOW("Low", 1, Color(0xFF1E88E5)),        // Royal Blue
    NONE("None", 0, Color(0xFF78909C));      // Slate Grey

    companion object {
        fun fromString(name: String): Priority {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
        }
    }
}
