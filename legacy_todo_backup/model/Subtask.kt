package com.example.kukoo.model

data class Subtask(
    val id: Long = 0,
    val taskId: Long,
    val title: String,
    val isCompleted: Boolean = false
)
