// com.moyeoyo.app.data.repository.FriendRepository.kt
package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await

class FriendRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val usersCollection = db.collection("users")
    private val friendRequestsCollection = db.collection("friendRequests")
    private val TAG = "FriendRepository"

    /**
     * UID와 함께 검색 상태를 반환하는 헬퍼 함수 (AddFriendActivity용)
     * ⭐ [NEW] 검색 실패 시 상세 상태 코드를 반환합니다.
     * @param email 검색할 이메일
     * @return Pair<UID, Status> - UID가 null이면 Status에 상세 사유 포함 (NOT_FOUND, SELF, ALREADY_FRIEND)
     */
    suspend fun findUserWithStatus(email: String): Pair<String?, String> {
        val currentUid = auth.currentUser?.uid ?: return Pair(null, "NO_AUTH")

        try {
            // 1. 이메일로 UID 검색
            val querySnapshot = usersCollection
                .whereEqualTo("email", email)
                .limit(1)
                .get()
                .await()

            val foundUserDoc = querySnapshot.documents.firstOrNull()
            val foundUid = foundUserDoc?.getString("uid")

            if (foundUid == null) {
                Log.d(TAG, "No user found for email $email")
                return Pair(null, "NOT_FOUND")
            }

            // 2. 본인 확인
            if (foundUid == currentUid) {
                Log.w(TAG, "Search result is self. Excluding.")
                return Pair(null, "SELF")
            }

            // 3. 이미 친구인지 확인
            val friendUids = getFriendUids()

            if (friendUids.contains(foundUid)) {
                Log.d(TAG, "User $foundUid is already a friend. Excluding from search results.")
                return Pair(null, "ALREADY_FRIEND")
            }

            Log.d(TAG, "User found for email $email: UID $foundUid (Not friend)")
            return Pair(foundUid, "FOUND") // 검색 성공 시 UID와 "FOUND" 반환

        } catch (e: Exception) {
            Log.e(TAG, "Error finding user by email: ${e.message}", e)
            return Pair(null, "ERROR")
        }
    }

    /**
     * 이메일로 사용자 UID를 검색합니다.
     * ⭐ [MODIFIED] findUserWithStatus를 호출하여 중복 로직을 제거합니다.
     * @param email 검색할 이메일
     * @return 검색된 사용자의 UID, 없거나 이미 친구이면 null
     */
    suspend fun findUserByEmail(email: String): String? {
        val (uid, _) = findUserWithStatus(email)
        return uid
    }

    /**
     * UID로 사용자의 닉네임을 조회합니다.
     * @param uid 조회할 사용자 UID
     * @return 사용자의 닉네임, 없으면 null
     */
    suspend fun getUserNickname(uid: String): String? {
        return try {
            val snapshot = usersCollection.document(uid).get().await()
            snapshot.getString("nickname")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching nickname for $uid: ${e.message}", e)
            null
        }
    }

    /**
     * 특정 UID를 가진 사용자에게 친구 요청을 보냅니다.
     * @param receiverUid 요청을 받을 사용자 UID
     * @return 성공 여부
     */
    suspend fun sendFriendRequest(receiverUid: String): Boolean {
        val senderUid = auth.currentUser?.uid ?: return false
        if (senderUid == receiverUid) {
            Log.w(TAG, "Cannot send friend request to self.")
            return false
        }

        return try {
            // 2. 친구 요청 문서 생성
            val requestData = hashMapOf(
                "senderUid" to senderUid,
                "receiverUid" to receiverUid,
                "timestamp" to com.google.firebase.Timestamp.now()
            )
            // 요청자-수신자 쌍을 ID로 사용하여 요청을 고유하게 식별합니다.
            val requestId = "${senderUid}_$receiverUid"
            friendRequestsCollection.document(requestId).set(requestData).await()

            Log.d(TAG, "Friend request sent from $senderUid to $receiverUid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send friend request: ${e.message}", e)
            false
        }
    }

    /**
     * 현재 로그인된 사용자가 받은 친구 요청 목록을 조회합니다.
     * @return 요청을 보낸 사용자의 UID 리스트
     */
    suspend fun getPendingRequests(): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val snapshot = friendRequestsCollection
                .whereEqualTo("receiverUid", uid)
                .get()
                .await()

            snapshot.documents.mapNotNull { it.getString("senderUid") }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching pending requests: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 친구 요청을 수락하고 양쪽 사용자 문서의 friends 배열을 업데이트합니다.
     * @param senderUid 요청을 보낸 사용자 UID
     * @return 성공 여부
     */
    suspend fun acceptFriendRequest(senderUid: String): Boolean {
        val receiverUid = auth.currentUser?.uid ?: return false
        val requestId = "${senderUid}_$receiverUid"

        return try {
            db.runTransaction { transaction ->
                // 1. 요청 문서 삭제
                val requestRef = friendRequestsCollection.document(requestId)
                transaction.delete(requestRef)

                // 2. 요청자와 수신자 모두의 friends 배열에 서로 추가 (Atomic Update)
                val senderRef = usersCollection.document(senderUid)
                val receiverRef = usersCollection.document(receiverUid)

                transaction.update(senderRef, "friends", FieldValue.arrayUnion(receiverUid))
                transaction.update(receiverRef, "friends", FieldValue.arrayUnion(senderUid))

                null
            }.await()
            Log.d(TAG, "Friend request accepted: $senderUid and $receiverUid are now friends.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed to accept friend request: ${e.message}", e)
            false
        }
    }

    /**
     * 현재 로그인된 사용자의 친구 목록 UID를 조회합니다.
     * @return 친구의 UID 리스트
     */
    suspend fun getFriendUids(): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return try {
            val doc = usersCollection.document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            doc.get("friends") as? List<String> ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching friend UIDs: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * ⭐ NEW: 친구 목록에서 특정 사용자를 삭제합니다.
     * 양쪽 사용자 문서의 'friends' 배열에서 서로의 UID를 제거합니다.
     * @param friendUid 삭제할 친구의 UID
     * @return 성공 여부
     */
    suspend fun removeFriend(friendUid: String): Boolean {
        val currentUid = auth.currentUser?.uid ?: return false

        return try {
            db.runTransaction { transaction ->
                val myRef = usersCollection.document(currentUid)
                val friendRef = usersCollection.document(friendUid)

                // 1. 내 문서에서 친구 UID 제거
                transaction.update(myRef, "friends", FieldValue.arrayRemove(friendUid))

                // 2. 친구 문서에서 내 UID 제거
                transaction.update(friendRef, "friends", FieldValue.arrayRemove(currentUid))

                null
            }.await()
            Log.d(TAG, "Friend removed successfully: $currentUid removed $friendUid.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed to remove friend: ${e.message}", e)
            false
        }
    }
}