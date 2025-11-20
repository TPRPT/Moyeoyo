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
                        title = doc.getString("title"),
                        message = doc.getString("message"),
                        senderUid = doc.getString("senderUid"),
                        createdAt = doc.getTimestamp("createdAt"),
                        read = doc.getBoolean("read") ?: false,
                        id = doc.id
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

}
