package com.example.kukoo.model

enum class TaskFilter(val displayName: String) {
    ALL("All Tasks"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    FLAGGED("Starred"),
    COMPLETED("Completed")
}

enum class TaskSort(val displayName: String) {
    DUE_DATE("Due Date"),
    PRIORITY("Priority"),
    TITLE("Title"),
    CREATED_DATE("Date Created")
}
