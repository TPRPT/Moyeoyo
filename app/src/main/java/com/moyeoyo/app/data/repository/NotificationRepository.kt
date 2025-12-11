package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import com.moyeoyo.app.data.model.Notification
import kotlinx.coroutines.tasks.await

class NotificationRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    private val TAG = "NotificationRepo"

    private val functions: FirebaseFunctions =
        FirebaseFunctions.getInstance("asia-east1")

    /**
     * FCM Token 저장
     */
    suspend fun updateFcmToken(token: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false

        return try {
            db.collection("users").document(uid)
                .update("fcmToken", token)
                .await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update token: ${e.message}")
            false
        }
    }

    /**
     * Cloud Functions 호출 → 친구 요청 알림
     */
    suspend fun sendFriendRequestNotification(
        receiverUid: String,
        senderUid: String
    ): Boolean {

        return try {
            val data = hashMapOf(
                "receiverUid" to receiverUid,
                "senderUid" to senderUid
            )

            val result: HttpsCallableResult = functions
                .getHttpsCallable("sendFriendRequestNotification")
                .call(data)
                .await()

            Log.d(TAG, "Cloud Function success: ${result.data}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Cloud Function error: ${e.message}")
            false
        }
    }

    /**
     * Firestore 알림 가져오기
     */
    suspend fun getNotifications(): List<Notification> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val snap = db.collection("users")
                .document(uid)
                .collection("notifications")
                .orderBy("createdAt")
                .get()
                .await()

            snap.documents.mapNotNull { doc ->
                try {
                    Notification(
                        id = doc.id,
                        title = doc.getString("title"),
                        message = doc.getString("message"),
                        type = doc.getString("type") ?: "unknown",
                        groupId = doc.getString("groupId"),
                        senderUid = doc.getString("senderUid"),
                        read = doc.getBoolean("read") ?: false,
                        handled = doc.getBoolean("handled") ?: false,
                        createdAt = doc.getTimestamp("createdAt")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Mapping error: ${e.message}")
                    null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "getNotifications error: ${e.message}")
            emptyList()
        }
    }

    /**
     * 특정 알림 하나만 읽음 처리
     */
    suspend fun markNotificationAsRead(notificationId: String) {
        val uid = auth.currentUser?.uid ?: return

        try {
            db.collection("users")
                .document(uid)
                .collection("notifications")
                .document(notificationId)
                .update("read", true)
                .await()

        } catch (e: Exception) {
            Log.e(TAG, "markNotificationAsRead error: ${e.message}")
        }
    }
    
    /**
     * type과 groupId로 최신 알림을 찾아서 읽음 처리
     * 푸시 알림 클릭 시 자동 읽음 처리용
     */
    suspend fun markNotificationAsReadByTypeAndGroup(type: String, groupId: String? = null) {
        val uid = auth.currentUser?.uid ?: return

        try {
            var query = db.collection("users")
                .document(uid)
                .collection("notifications")
                .whereEqualTo("type", type)
                .whereEqualTo("read", false)
            
            if (groupId != null) {
                query = query.whereEqualTo("groupId", groupId)
            }
            
            // orderBy 없이 모든 미읽음 알림을 가져와서 가장 최신 것 선택
            val snapshot = query.get().await()
            
            // createdAt이 가장 최신인 알림 찾기 (클라이언트 측 정렬)
            val latestDoc = snapshot.documents.maxByOrNull { doc ->
                doc.getTimestamp("createdAt")?.seconds ?: 0L
            }
            
            latestDoc?.let { doc ->
                doc.reference.update("read", true).await()
                Log.d(TAG, "Marked notification as read: type=$type, groupId=$groupId, id=${doc.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "markNotificationAsReadByTypeAndGroup error: ${e.message}")
        }
    }

    /**
     * 로컬 알림을 Firestore에 저장 (인앱 알림창 표시용)
     */
    suspend fun createLocalNotification(
        title: String,
        message: String,
        type: String,
        groupId: String? = null
    ): Boolean {
        val uid = auth.currentUser?.uid ?: return false

        return try {
            val notificationData = hashMapOf<String, Any>(
                "title" to title,
                "message" to message,
                "type" to type,
                "read" to false,
                "handled" to false,
                "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            
            groupId?.let { notificationData["groupId"] = it }
            
            db.collection("users")
                .document(uid)
                .collection("notifications")
                .add(notificationData)
                .await()
            
            Log.d(TAG, "Local notification created in Firestore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local notification: ${e.message}")
            false
        }
    }

    /**
     * 알림 일괄 읽음 처리
     */
    suspend fun markNotificationsAsRead(notificationIds: List<String>) {
        val uid = auth.currentUser?.uid ?: return

        if (notificationIds.isEmpty()) return

        try {
            val batch = db.batch()
            val colRef = db.collection("users")
                .document(uid)
                .collection("notifications")

            notificationIds.forEach { id ->
                val docRef = colRef.document(id)
                batch.update(docRef, "read", true)
            }

            batch.commit().await()

        } catch (e: Exception) {
            Log.e(TAG, "markNotificationsAsRead error: ${e.message}")
        }
    }

    /**
     * 친구 요청 알림 처리
     */
    suspend fun markNotificationAsHandled(id: String) {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users").document(uid)
            .collection("notifications")
            .document(id)
            .update("handled", true)
            .await()
    }

    /**
     * 알림 삭제
     */
    suspend fun deleteNotification(notificationId: String) {
        val uid = auth.currentUser?.uid ?: return

        try {
            db.collection("users")
                .document(uid)
                .collection("notifications")
                .document(notificationId)
                .delete()
                .await()

            Log.d(TAG, "Notification deleted: $notificationId")

        } catch (e: Exception) {
            Log.e(TAG, "deleteNotification error: ${e.message}")
        }
    }


}
