package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.Timestamp
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val notificationRepository: NotificationRepository   // ⭐ DI로 주입
) {
    private val usersCollection = db.collection("users")
    private val friendRequestsCollection = db.collection("friendRequests")
    private val TAG = "FriendRepository"

    // ===========================
    // 이메일 검색
    // ===========================
    suspend fun findUserWithStatus(email: String): Pair<String?, String> {
        val currentUid = auth.currentUser?.uid ?: return Pair(null, "NO_AUTH")

        return try {
            val snapshot = usersCollection
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()

            val doc = snapshot.documents.firstOrNull()
            val uid = doc?.getString("uid")

            if (uid == null) return Pair(null, "NOT_FOUND")
            if (uid == currentUid) return Pair(null, "SELF")

            val friends = getFriendUids()
            if (friends.contains(uid)) return Pair(null, "ALREADY_FRIEND")

            Pair(uid, "FOUND")
        } catch (e: Exception) {
            Log.e(TAG, "Error finding user: ${e.message}")
            Pair(null, "ERROR")
        }
    }

    suspend fun findUserByEmail(email: String): String? {
        val (uid, _) = findUserWithStatus(email)
        return uid
    }

    // ===========================
    // 닉네임 조회
    // ===========================
    suspend fun getUserNickname(uid: String): String? {
        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.getString("nickname")
        } catch (e: Exception) {
            Log.e(TAG, "Nickname fetch error: ${e.message}")
            null
        }
    }

    suspend fun getUserPhotoUrl(uid: String): String? {
        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.getString("photoUrl")
        } catch (e: Exception) {
            Log.e(TAG, "PhotoUrl fetch error: ${e.message}")
            null
        }
    }

    suspend fun getUserEmail(uid: String): String? {
        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.getString("email")
        } catch (e: Exception) {
            Log.e(TAG, "Email fetch error: ${e.message}")
            null
        }
    }

    // ===========================
    // 친구 요청 보내기
    // ===========================
    suspend fun sendFriendRequest(receiverUid: String): Boolean {
        val senderUid = auth.currentUser?.uid ?: return false
        if (senderUid == receiverUid) return false

        return try {
            val request = mapOf(
                "senderUid" to senderUid,
                "receiverUid" to receiverUid,
                "timestamp" to Timestamp.now()
            )

            val requestId = "${senderUid}_${receiverUid}"

            friendRequestsCollection.document(requestId).set(request).await()

            // ⭐ 수정: 함수 이름 변경됨!!
            notificationRepository.sendFriendRequestNotification(receiverUid, senderUid)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Friend request failed: ${e.message}")
            false
        }
    }

    // ===========================
    // 친구 요청 목록
    // ===========================
    suspend fun getPendingRequests(): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val snapshot = friendRequestsCollection
                .whereEqualTo("receiverUid", uid)
                .get()
                .await()

            snapshot.documents.mapNotNull { it.getString("senderUid") }
        } catch (e: Exception) {
            Log.e(TAG, "Get pending requests failed: ${e.message}")
            emptyList()
        }
    }

    // ===========================
    // 친구 요청 수락
    // ===========================
    suspend fun acceptFriendRequest(senderUid: String): Boolean {
        val receiverUid = auth.currentUser?.uid ?: return false
        val requestId = "${senderUid}_${receiverUid}"

        return try {
            db.runTransaction { t ->
                t.delete(friendRequestsCollection.document(requestId))

                t.update(
                    usersCollection.document(senderUid),
                    "friends",
                    FieldValue.arrayUnion(receiverUid)
                )
                t.update(
                    usersCollection.document(receiverUid),
                    "friends",
                    FieldValue.arrayUnion(senderUid)
                )
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Accept failed: ${e.message}")
            false
        }
    }

    // ===========================
    // 친구 목록 조회
    // ===========================
    suspend fun getFriendUids(): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.get("friends") as? List<String> ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching friend list: ${e.message}")
            emptyList()
        }
    }

    // ===========================
    // 친구 삭제
    // ===========================
    suspend fun removeFriend(friendUid: String): Boolean {
        val myUid = auth.currentUser?.uid ?: return false

        return try {
            db.runTransaction { t ->
                t.update(
                    usersCollection.document(myUid),
                    "friends",
                    FieldValue.arrayRemove(friendUid)
                )
                t.update(
                    usersCollection.document(friendUid),
                    "friends",
                    FieldValue.arrayRemove(myUid)
                )
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Remove friend failed: ${e.message}")
            false
        }
    }

    // ===========================
    // 초대 링크 자동 친구 추가
    // ===========================
    suspend fun acceptFriendByInvite(inviterUid: String): Boolean {
        val myUid = auth.currentUser?.uid ?: return false
        if (myUid == inviterUid) return false

        return try {
            db.runTransaction { t ->
                t.update(
                    usersCollection.document(myUid),
                    "friends",
                    FieldValue.arrayUnion(inviterUid)
                )
                t.update(
                    usersCollection.document(inviterUid),
                    "friends",
                    FieldValue.arrayUnion(myUid)
                )
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Invite friend add failed: ${e.message}")
            false
        }
    }

    // ===========================
    // 유저 전체 정보 조회 (nickname, photoUrl 등)
    // ===========================
    suspend fun getUserProfile(uid: String): Map<String, Any>? {
        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.data
        } catch (e: Exception) {
            Log.e("FriendRepository", "getUserProfile error: ${e.message}")
            null
        }
    }

}
