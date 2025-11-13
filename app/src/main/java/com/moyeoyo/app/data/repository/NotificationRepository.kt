package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.moyeoyo.app.data.model.NotificationUi
import kotlinx.coroutines.tasks.await

class NotificationRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val notificationsCollection = db.collection("notifications")
    private val usersCollection = db.collection("users")
    private val TAG = "NotificationRepository"

    /**
     * 현재 로그인된 사용자의 Firestore 문서에 FCM 토큰을 저장/업데이트합니다.
     * @param token FCM 토큰 문자열
     * @return 성공 여부
     */
    suspend fun updateFcmToken(token: String): Boolean {
        val currentUid = auth.currentUser?.uid ?: return false

        return try {
            usersCollection.document(currentUid)
                .update("fcmToken", token)
                .await()
            Log.d(TAG, "FCM token updated successfully for $currentUid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating FCM token for $currentUid: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ FINAL MODIFIED: 친구 요청 알림 발송을 위해 Firestore에 요청 문서를 생성합니다.
     * Cloud Functions가 이 문서 생성을 감지하여 푸시 알림을 발송합니다.
     * @param receiverUid 요청을 받은 사용자 (알림 수신자)
     * @param senderUid 요청을 보낸 사용자 (알림 발신자)
     * @return 요청 문서 생성 성공 여부
     */
    suspend fun requestFriendNotification(receiverUid: String, senderUid: String): Boolean {
        // 알림 요청 문서에 필요한 데이터
        val notificationRequest = hashMapOf(
            "type" to "friend_request",
            "receiverUid" to receiverUid,
            "senderUid" to senderUid,
            "timestamp" to com.google.firebase.Timestamp.now(), // Firestore Timestamp 사용
            "isSent" to false // Cloud Function이 발송 후 true로 업데이트할 수 있는 필드
        )

        return try {
            // Firestore 'notifications' 컬렉션에 문서를 추가합니다.
            notificationsCollection.add(notificationRequest).await()
            Log.i(TAG, "Friend notification request document created for $receiverUid.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification request document: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ FINAL MODIFIED: Firestore에서 알림 문서를 조회하여 NotificationUi 리스트로 반환합니다.
     * @return 알림의 UID 리스트
     */
    suspend fun getNotifications(): List<NotificationUi> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val snapshot = notificationsCollection
                .whereEqualTo("receiverUid", uid)
                .orderBy("timestamp", Query.Direction.DESCENDING) // 최신순 정렬
                .limit(20)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                // Firestore 문서 필드를 NotificationUi 모델로 변환
                val type = doc.getString("type") ?: "general"
                val senderUid = doc.getString("senderUid") // 알림을 보낸 사용자 UID

                // (실제 닉네임을 사용하려면 여기서 senderUid로 users 컬렉션에 추가 쿼리가 필요합니다.
                // 복잡성을 줄이기 위해 간단한 메시지 로직을 유지합니다.)

                NotificationUi(
                    title = when(type) {
                        "friend_request" -> "새로운 친구 요청"
                        "confirmed" -> "약속 확정"
                        else -> "새 알림"
                    },
                    message = when(type) {
                        "friend_request" -> "새로운 친구 요청이 도착했습니다."
                        "confirmed" -> doc.getString("message") ?: "약속이 확정되었습니다."
                        else -> doc.getString("message") ?: "내용 없음"
                    },
                    time = "최신 알림", // Timestamp 포맷 로직이 필요
                    type = type
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notifications: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 알림을 읽음 처리합니다.
     * @param notificationId 읽음 처리할 알림 문서 ID
     */
    suspend fun markAsRead(notificationId: String): Boolean {
        return try {
            notificationsCollection.document(notificationId)
                .update("isRead", true)
                .await()
            Log.d(TAG, "Notification marked as read: $notificationId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error marking notification as read: ${e.message}", e)
            false
        }
    }
}