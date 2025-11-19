package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import com.moyeoyo.app.data.repository.NotificationRepository

@Singleton
class FriendRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val usersCollection = db.collection("users")
    private val friendRequestsCollection = db.collection("friendRequests")
    private val TAG = "FriendRepository"

    // ⭐ NEW — 알림 전송용 Repository
    private val notificationRepository = NotificationRepository(db, auth)

    // ============================================================
    // 🔍 이메일 기반 사용자 검색 (상태 포함)
    // ============================================================
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

    // ============================================================
    // 🔍 UID → 닉네임 조회
    // ============================================================
    suspend fun getUserNickname(uid: String): String? {
        return try {
            val doc = usersCollection.document(uid).get().await()
            doc.getString("nickname")
        } catch (e: Exception) {
            Log.e(TAG, "Nickname fetch error: ${e.message}")
            null
        }
    }

    // ============================================================
    // 📨 친구 요청 보내기
    // ============================================================
    suspend fun sendFriendRequest(receiverUid: String): Boolean {
        val senderUid = auth.currentUser?.uid ?: return false
        if (senderUid == receiverUid) return false

        return try {
            val request = hashMapOf(
                "senderUid" to senderUid,
                "receiverUid" to receiverUid,
                "timestamp" to com.google.firebase.Timestamp.now()
            )
            val requestId = "${senderUid}_${receiverUid}"

            friendRequestsCollection.document(requestId).set(request).await()

            // ⭐ NEW — Cloud Functions 기반 알림 요청
            notificationRepository.requestFriendNotification(receiverUid, senderUid)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Friend request failed: ${e.message}")
            false
        }
    }

    // ============================================================
    // 📨 친구 요청 목록 조회
    // ============================================================
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

    // ============================================================
    // 🟢 친구 요청 수락
    // ============================================================
    suspend fun acceptFriendRequest(senderUid: String): Boolean {
        val receiverUid = auth.currentUser?.uid ?: return false
        val requestId = "${senderUid}_${receiverUid}"

        return try {
            db.runTransaction { t ->
                t.delete(friendRequestsCollection.document(requestId))

                val senderRef = usersCollection.document(senderUid)
                val receiverRef = usersCollection.document(receiverUid)

                t.update(senderRef, "friends", FieldValue.arrayUnion(receiverUid))
                t.update(receiverRef, "friends", FieldValue.arrayUnion(senderUid))
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Accept failed: ${e.message}")
            false
        }
    }

    // ============================================================
    // 📜 내 친구 목록 조회
    // ============================================================
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

    // ============================================================
    // ❌ 친구 삭제
    // ============================================================
    suspend fun removeFriend(friendUid: String): Boolean {
        val myUid = auth.currentUser?.uid ?: return false

        return try {
            db.runTransaction { t ->
                val myRef = usersCollection.document(myUid)
                val friendRef = usersCollection.document(friendUid)

                t.update(myRef, "friends", FieldValue.arrayRemove(friendUid))
                t.update(friendRef, "friends", FieldValue.arrayRemove(myUid))
            }.await()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Remove friend failed: ${e.message}")
            false
        }
    }

    // ============================================================
    // ⭐ NEW — "친구 초대 링크" 전용 친구 자동 추가 기능
    // ============================================================
    suspend fun acceptFriendByInvite(inviterUid: String): Boolean {
        val myUid = auth.currentUser?.uid ?: return false
        if (myUid == inviterUid) return false

        return try {
            db.runTransaction { t ->
                val myRef = usersCollection.document(myUid)
                val inviterRef = usersCollection.document(inviterUid)

                t.update(myRef, "friends", FieldValue.arrayUnion(inviterUid))
                t.update(inviterRef, "friends", FieldValue.arrayUnion(myUid))
            }.await()

            Log.d(TAG, "Friends linked via invitation: $myUid ↔ $inviterUid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Invite friend add failed: ${e.message}")
            false
        }
    }
}
