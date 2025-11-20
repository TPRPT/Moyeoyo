package com.moyeoyo.app.data.model

data class NotificationUi(
    val id: String,          // Firestore 문서 ID
    val title: String,
    val message: String,
    val type: String,        // friend_request / location_input / time_vote 등
    val groupId: String?,    // 그룹 알림이면 포함됨
    val senderUid: String?,  // 친구 요청이면 포함됨
    val time: String,
    val timestamp: Long,
    val read: Boolean        // 읽음 상태
)
