package com.moyeoyo.app.data.model

data class NotificationUi(
    val title: String,
    val message: String,
    val time: String,
    val type: String // friend_request, confirmed, d1, changed, canceled 등 알림 종류
)