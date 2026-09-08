package com.multibrowser.antidetect.data.model

data class SavedTabEntity(
    val id: String,
    val profileId: String,
    val title: String,
    val url: String,
    val tabOrder: Int = 0,
    val isCurrentTab: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
