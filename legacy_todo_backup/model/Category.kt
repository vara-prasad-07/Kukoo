package com.example.kukoo.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class Category(
    val title: String,
    val icon: ImageVector,
    val color: Color
) {
    WORK("Work", Icons.Default.BusinessCenter, Color(0xFF3F51B5)),
    PERSONAL("Personal", Icons.Default.Person, Color(0xFF9C27B0)),
    SHOPPING("Shopping", Icons.Default.ShoppingCart, Color(0xFF009688)),
    HEALTH("Health", Icons.Default.Favorite, Color(0xFFE91E63)),
    EDUCATION("Education", Icons.Default.Book, Color(0xFFFF9800)),
    GENERAL("General", Icons.Default.Checklist, Color(0xFF607D8B));

    companion object {
        fun fromString(name: String): Category {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GENERAL
        }
    }
}
