package com.moyeoyo.app.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.model.NotificationUi
import kotlinx.coroutines.tasks.await

class NotificationRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val notificationsCollection = db.collection("notifications")
    private val TAG = "NotificationRepository"

    /**
     * 현재 사용자의 모든 알림을 Firestore에서 비동기적으로 가져옵니다.
     * (현재는 더미 데이터를 반환하며, 실제 구현이 필요합니다.)
     */
    suspend fun getNotifications(): List<NotificationUi> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        // TODO: Firestore에서 'receiverUid'가 현재 UID와 일치하는 알림 문서를 쿼리하는 로직을 여기에 구현합니다.

        // 임시로 더미 데이터 반환
        return listOf(
            NotificationUi("친구 요청", "김철수님이 친구 요청을 보냈습니다", "1분 전", "friend_request"),
            NotificationUi("약속 변경", "모임 장소가 변경되었습니다", "1시간 전", "changed")
        )
    }

    /**
     * 알림을 읽음 처리합니다.
     * @param notificationId 읽음 처리할 알림 문서 ID
     */
    suspend fun markAsRead(notificationId: String): Boolean {
        // TODO: Firestore 문서의 'isRead' 필드를 true로 업데이트하는 로직 구현
        return true // 임시 반환
    }
}