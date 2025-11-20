package com.moyeoyo.app.data.model

data class NotificationUi(
    val id: String,
    val title: String,
    val message: String,
    val type: String,
    val time: String,
    val timestamp: Long,
    val read: Boolean
)