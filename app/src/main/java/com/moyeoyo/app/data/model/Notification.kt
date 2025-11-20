package com.moyeoyo.app.data.model

data class Notification(
    val title: String? = null,
    val message: String? = null,
    val createdAt: com.google.firebase.Timestamp? = null,
    val senderUid: String? = null,
    val read: Boolean = false,
    val id: String = ""
)