package com.moyeoyo.app.data.model

data class Notification(
    val id: String = "",
    val title: String? = null,
    val message: String? = null,
    val type: String? = null,
    val groupId: String? = null,
    val senderUid: String? = null,
    val read: Boolean = false,
    val createdAt: com.google.firebase.Timestamp? = null
)
