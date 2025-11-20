package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.model.Vote
import com.moyeoyo.app.data.model.FinalCandidateData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 모임(Group) 데이터의 CRUD를 처리하는 Repository.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    private val groupsCollection = db.collection("groups")
    private val usersCollection = db.collection("users")
    private val TAG = "GroupRepository"

    /**
     * [이 함수는 createGroupWithMembers로 대체되거나 병행 사용됩니다. 새로운 그룹 생성 로직에서는 createGroupWithMembers를 사용하세요.]
     * 새로운 모임 방을 생성하고 Firestore에 저장합니다. (호스트만 포함)
     */
    suspend fun createGroup(groupName: String): String? {
        val hostUid = auth.currentUser?.uid ?: return null

        // createGroupWithMembers를 사용하여 단일 멤버로 그룹 생성
        return createGroupWithMembers(groupName, emptyList())
    }

    /**
     * ⭐ NEW: 새로운 모임 방을 생성하고 멤버 목록을 포함하여 Firestore에 저장합니다.
     * @param groupName 그룹 이름
     * @param friendUids 선택된 친구들의 UID 목록 (호스트 제외)
     * @return 생성된 그룹 ID, 실패 시 null
     */
    suspend fun createGroupWithMembers(groupName: String, friendUids: List<String>): String? {
        val hostUid = auth.currentUser?.uid ?: return null

        // 멤버 목록: 호스트 + 선택된 친구들 (Set을 사용하여 중복 방지)
        val allMemberUids = (friendUids + hostUid).distinct()

        val newGroup = Group(
            groupName = groupName,
            hostUid = hostUid,
            memberUids = allMemberUids, // ⭐ 멤버 목록 포함
            status = "VOTING"
        )

        return try {
            val groupRef = groupsCollection.add(newGroup).await()
            val groupId = groupRef.id

            // 1. Host의 inputLocation 문서 생성 (호스트만 필수)
            groupsCollection.document(groupId)
                .collection("inputLocations")
                .document(hostUid)
                .set(mapOf(
                    "latLng" to GeoPoint(0.0, 0.0),
                    "transportMode" to "UNKNOWN",
                    "timestamp" to Timestamp.now()
                )).await()

            // 2. vote 하위 문서 생성 (투표 상태 관리용)
            groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
                .set(Vote(
                    status = "RANKING",
                    rankedUsers = emptyList(),
                    finalCandidates = emptyList(),
                    finalVotedUsers = emptyList()
                )).await()

            // 3. 모든 멤버의 users 문서에 groupId를 groups 배열에 추가 (일괄 쓰기 사용)
            val batch = db.batch()
            allMemberUids.forEach { uid ->
                val userRef = usersCollection.document(uid)
                batch.update(userRef, "groups", FieldValue.arrayUnion(groupId))
            }
            batch.commit().await()

            Log.d("GroupRepository", "Group created successfully with ID: $groupId, Members: ${allMemberUids.size}, Vote document created")
            groupId
        } catch (e: Exception) {
            Log.e("GroupRepository", "Group creation with members failed: ${e.message}", e)
            null
        }
    }


    /**
     * ⭐ NEW: 그룹 ID로 Firestore에서 그룹 데이터를 조회합니다. (딥링크 모달용)
     */
    suspend fun getGroupById(groupId: String): Group? {
        return try {
            val snapshot = groupsCollection.document(groupId).get().await()
            if (snapshot.exists()) {
                // Group.kt (모델 파일)의 존재를 가정하고 toObject로 변환
                snapshot.toObject(Group::class.java)?.copy(id = snapshot.id)
            } else {
                Log.w("GroupRepository", "Group not found for ID: $groupId")
                null
            }
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error fetching group $groupId: ${e.message}", e)
            null
        }
    }


    /**
     * 특정 그룹에 현재 로그인된 사용자를 멤버로 추가합니다. (딥링크 처리 로직)
     * @param groupId 참여할 그룹 ID
     * @return 성공 여부
     */
    suspend fun joinGroup(groupId: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val groupRef = groupsCollection.document(groupId)
        val userRef = usersCollection.document(uid)

        return try {
            db.runTransaction { transaction ->
                val groupSnapshot = transaction.get(groupRef)

                if (!groupSnapshot.exists()) {
                    Log.e("GroupRepo", "Transaction Aborted: Group document $groupId does not exist.")
                    throw IllegalStateException("Group document does not exist.")
                }

                @Suppress("UNCHECKED_CAST")
                val memberUids = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()

                // 이미 참여했는지 확인
                if (memberUids.contains(uid)) {
                    Log.d("GroupRepo", "User $uid already joined group $groupId. Skipping write.")
                    return@runTransaction null // 이미 참여했으므로 성공으로 간주하고 트랜잭션 종료
                }

                // 1. 그룹 멤버 배열 업데이트 (groups/{groupId})
                val newMembers = memberUids + uid
                transaction.update(groupRef, "memberUids", newMembers)

                // 2. inputLocations 하위 컬렉션 문서 생성
                val locationRef = groupRef.collection("inputLocations").document(uid)
                transaction.set(locationRef, mapOf(
                    "latLng" to GeoPoint(0.0, 0.0), // GeoPoint 사용
                    "transportMode" to "UNKNOWN",
                    "timestamp" to Timestamp.now()
                ))

                // 3. 참여하는 사용자의 users 문서에 groupId 추가
                transaction.update(userRef, "groups", FieldValue.arrayUnion(groupId))

                null // 트랜잭션 성공 신호
            }.await()

            Log.d("GroupRepo", "Join Group Transaction FINAL SUCCESS for $groupId.")
            true
        } catch (e: Exception) {
            Log.e("GroupRepo", "Join Group Transaction FAILED for $groupId. Reason: ${e.message}", e)
            false
        }
    }

    /**
     * 현재 로그인된 사용자가 참여 중인 모든 그룹의 목록을 조회합니다.
     * 💻 최적화: users/{uid} 문서의 groups 배열을 사용하여 그룹 ID 목록을 가져옵니다.
     * @return Group 객체 리스트 (List<Group>)
     */
    suspend fun getGroupsForUser(): List<Group> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            // 1. users 컬렉션에서 그룹 ID 목록 조회
            val userDoc = usersCollection.document(uid).get().await()

            @Suppress("UNCHECKED_CAST")
            val groupIds = userDoc.get("groups") as? List<String> ?: emptyList()

            if (groupIds.isEmpty()) return emptyList()

            val groups = mutableListOf<Group>()

            // 2. groups 컬렉션에서 실제 그룹 문서 조회 (whereIn은 최대 10개까지 지원하므로 청킹)
            groupIds.chunked(10).forEach { chunk ->
                val snapshot = groupsCollection
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()
                groups.addAll(snapshot.toObjects(Group::class.java))
            }

            // groups 배열 순서를 유지하고 싶다면 여기서 정렬 로직 추가

            groups

        } catch (e: Exception) {
            Log.e("GroupRepository", "Error fetching user groups: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 특정 그룹의 상세 정보를 Group 객체로 조회합니다.
     */
    suspend fun getGroupDetail(groupId: String): Group? {
        return getGroupById(groupId) // 새로 추가된 함수 재사용
    }

    /**
     * ⭐ NEW: 확정된 일정 정보(장소/시간)를 Firestore에 저장하고 그룹 상태를 변경합니다.
     */
    suspend fun confirmGroupSchedule(
        groupId: String,
        confirmedPlace: Map<String, Any>,
        confirmedTime: Timestamp,
        newTitle: String
    ): Boolean {
        val groupRef = groupsCollection.document(groupId)

        return try {
            val updates = hashMapOf<String, Any>(
                "confirmedPlace" to confirmedPlace,
                "confirmedTime" to confirmedTime,
                "status" to "CONFIRMED",
                "groupName" to newTitle // 사용자가 입력한 제목으로 그룹 이름 업데이트
            )

            groupRef.update(updates).await()
            Log.d(TAG, "Group schedule confirmed for ID: $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm group schedule for ID $groupId: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ NEW: 특정 멤버를 그룹에서 강퇴시킵니다. (removeMember)
     * @param groupId 그룹 ID
     * @param memberUidToRemove 강퇴할 멤버의 UID
     * @return 성공 여부
     */
    suspend fun removeMember(groupId: String, memberUidToRemove: String): Boolean {
        val groupRef = groupsCollection.document(groupId)
        val userRef = usersCollection.document(memberUidToRemove)

        return try {
            db.runTransaction { transaction ->
                val groupSnapshot = transaction.get(groupRef)

                if (!groupSnapshot.exists()) {
                    throw IllegalStateException("Group document does not exist.")
                }

                @Suppress("UNCHECKED_CAST")
                val currentMembers = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()

                if (!currentMembers.contains(memberUidToRemove)) {
                    Log.w(TAG, "Member to remove is not in the group.")
                    return@runTransaction null
                }

                // 1. 그룹 멤버 배열 업데이트 (groups/{groupId})
                val newMembers = currentMembers.filter { it != memberUidToRemove }
                transaction.update(groupRef, "memberUids", newMembers)

                // 2. 강퇴된 사용자의 users 문서에서 groupId 제거
                transaction.update(userRef, "groups", FieldValue.arrayRemove(groupId))

                // 3. 강퇴된 멤버의 inputLocation 문서 삭제
                val locationRef = groupRef.collection("inputLocations").document(memberUidToRemove)
                transaction.delete(locationRef)

                null // 트랜잭션 성공
            }.await()

            Log.d(TAG, "Member $memberUidToRemove removed from group $groupId.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove member $memberUidToRemove from group $groupId: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ NEW: 일반 멤버가 그룹을 나갑니다. (leaveGroup)
     */
    suspend fun leaveGroup(groupId: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false

        // removeMember 함수를 재사용하여 나가기 처리
        return removeMember(groupId, uid)
    }

    /**
     * 그룹과 그 하위 컬렉션의 모든 데이터를 삭제합니다.
     */
    suspend fun deleteGroup(groupId: String): Boolean {
        val groupRef = groupsCollection.document(groupId)

        return try {
            // 1. 그룹 멤버 UID 목록 조회
            val groupSnapshot = groupRef.get().await()
            @Suppress("UNCHECKED_CAST")
            val memberUids = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()

            // 2. 하위 컬렉션 삭제
            deleteCollection(groupRef.collection("inputLocations"))
            deleteCollection(groupRef.collection("placeCandidates"))
            deleteCollection(groupRef.collection("timeCandidates"))

            // 3. 그룹 문서 삭제
            groupRef.delete().await()

            // 4. 모든 멤버의 users 문서에서 groupId를 groups 배열에서 제거 (일괄 쓰기 사용)
            val batch = db.batch()
            memberUids.forEach { uid ->
                val userRef = usersCollection.document(uid)
                batch.update(userRef, "groups", FieldValue.arrayRemove(groupId))
            }
            batch.commit().await()

            Log.d("GroupRepository", "Group deleted successfully: $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete group $groupId: ${e.message}", e)
            false
        }
    }

    /**
     * Firestore 컬렉션의 모든 문서를 삭제하는 유틸리티 함수입니다.
     */
    private suspend fun deleteCollection(collectionRef: CollectionReference, batchSize: Int = 100) {
        val snapshot = collectionRef.limit(batchSize.toLong()).get().await()
        if (snapshot.isEmpty) {
            return
        }

        val batch = db.batch()
        snapshot.documents.forEach { document ->
            batch.delete(document.reference)
        }
        batch.commit().await()
    }

    // =========================
    // Firestore: vote (투표 상태 관리)
    // =========================

    /**
     * 1차 순위 투표 완료한 사용자를 rankedUsers 배열에 추가
     */
    suspend fun addUserToRankedList(groupId: String, uid: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update("rankedUsers", FieldValue.arrayUnion(uid)).await()
            Log.d(TAG, "User $uid added to rankedUsers for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user to rankedUsers: ${e.message}", e)
            throw e
        }
    }

    /**
     * 모든 사용자가 1차 순위 투표를 완료했는지 확인
     * @return true if all members have completed ranking, false otherwise
     */
    suspend fun checkAllUsersRanked(groupId: String): Boolean {
        return try {
            // 그룹 멤버 수 확인
            val group = getGroupById(groupId) ?: return false
            val totalMembers = group.memberUids.size

            // vote 문서에서 rankedUsers 확인
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
            val voteSnapshot = voteRef.get().await()

            @Suppress("UNCHECKED_CAST")
            val rankedUsers = voteSnapshot.get("rankedUsers") as? List<String> ?: emptyList()

            val allRanked = rankedUsers.size == totalMembers &&
                    group.memberUids.all { it in rankedUsers }

            Log.d(TAG, "checkAllUsersRanked - groupId: $groupId, totalMembers: $totalMembers, rankedUsers: ${rankedUsers.size}, allRanked: $allRanked")

            allRanked
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check all users ranked: ${e.message}", e)
            false
        }
    }

    /**
     * 투표 상태 업데이트
     */
    suspend fun updateVoteStatus(groupId: String, status: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update("status", status).await()
            Log.d(TAG, "Vote status updated to $status for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update vote status: ${e.message}", e)
            throw e
        }
    }

    /**
     * 최종 후보 3개 업데이트 및 상태를 FINAL_VOTING으로 변경
     */
    suspend fun updateFinalCandidates(groupId: String, finalCandidates: List<FinalCandidateData>) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            // finalCandidates를 Firestore에 저장할 수 있는 형태로 변환
            val candidatesData = finalCandidates.map { candidate ->
                mapOf(
                    "placeId" to candidate.placeId,
                    "name" to candidate.name,
                    "latLng" to candidate.latLng,
                    "totalScore" to candidate.totalScore,
                    "categories" to candidate.categories,
                    "rating" to (candidate.rating ?: ""),
                    "address" to (candidate.address ?: "")
                )
            }

            voteRef.update(
                "finalCandidates", candidatesData,
                "status", "FINAL_VOTING"
            ).await()

            Log.d(TAG, "Final candidates updated (${finalCandidates.size} items) and status changed to FINAL_VOTING for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update final candidates: ${e.message}", e)
            throw e
        }
    }

    /**
     * 최종 투표 완료한 사용자를 finalVotedUsers 배열에 추가
     */
    suspend fun addUserToFinalVotedList(groupId: String, uid: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update("finalVotedUsers", FieldValue.arrayUnion(uid)).await()
            Log.d(TAG, "User $uid added to finalVotedUsers for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user to finalVotedUsers: ${e.message}", e)
            throw e
        }
    }

    /**
     * 모든 사용자가 최종 투표를 완료했는지 확인
     */
    suspend fun checkAllUsersFinalVoted(groupId: String): Boolean {
        return try {
            val group = getGroupById(groupId) ?: return false
            val totalMembers = group.memberUids.size

            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
            // ⚠️ Source.SERVER 추가하여 서버 데이터만 확인 (캐시 문제 방지)
            val voteSnapshot = voteRef.get(Source.SERVER).await()

            @Suppress("UNCHECKED_CAST")
            val finalVotedUsers = voteSnapshot.get("finalVotedUsers") as? List<String> ?: emptyList()

            val allVoted = finalVotedUsers.size == totalMembers &&
                    group.memberUids.all { it in finalVotedUsers }

            Log.d(TAG, "checkAllUsersFinalVoted - groupId: $groupId, totalMembers: $totalMembers, finalVotedUsers: ${finalVotedUsers.size}, allVoted: $allVoted")

            allVoted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check all users final voted: ${e.message}", e)
            false
        }
    }

    /**
     * 투표 상태 조회
     */
    suspend fun getVoteStatus(groupId: String): Vote? {
        return try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")
            val snapshot = voteRef.get().await()

            if (snapshot.exists()) {
                convertSnapshotToVote(snapshot)
            } else {
                Log.w(TAG, "Vote document not found for group $groupId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get vote status: ${e.message}", e)
            null
        }
    }

    /**
     * Firestore 스냅샷을 Vote 객체로 변환
     */
    private fun convertSnapshotToVote(snapshot: com.google.firebase.firestore.DocumentSnapshot): Vote {
        val status = snapshot.get("status") as? String ?: "RANKING"
        @Suppress("UNCHECKED_CAST")
        val rankedUsers = (snapshot.get("rankedUsers") as? List<String>) ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val finalVotedUsers = (snapshot.get("finalVotedUsers") as? List<String>) ?: emptyList()
        val winningPlaceId = snapshot.get("winningPlaceId") as? String
        val winningPlaceName = snapshot.get("winningPlaceName") as? String

        // finalCandidates를 Map에서 FinalCandidateData로 변환
        @Suppress("UNCHECKED_CAST")
        val finalCandidatesData = (snapshot.get("finalCandidates") as? List<Map<String, Any>>)?.mapNotNull { map ->
            try {
                val placeId = map["placeId"] as? String ?: return@mapNotNull null
                val name = map["name"] as? String ?: return@mapNotNull null
                val latLng = when (val geo = map["latLng"]) {
                    is GeoPoint -> geo
                    is Map<*, *> -> {
                        val lat = (geo["lat"] as? Number)?.toDouble() ?: (geo["latitude"] as? Number)?.toDouble()
                        val lng = (geo["lng"] as? Number)?.toDouble() ?: (geo["longitude"] as? Number)?.toDouble()
                        if (lat != null && lng != null) GeoPoint(lat, lng) else return@mapNotNull null
                    }
                    else -> return@mapNotNull null
                }
                val totalScore = (map["totalScore"] as? Number)?.toInt() ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val categories = (map["categories"] as? List<String>) ?: emptyList()
                val rating = when (val ratingValue = map["rating"]) {
                    is Number -> ratingValue.toDouble()
                    is String -> if (ratingValue.isNotEmpty()) ratingValue.toDoubleOrNull() else null
                    else -> null
                }
                val address = map["address"] as? String

                FinalCandidateData(
                    placeId = placeId,
                    name = name,
                    latLng = latLng,
                    totalScore = totalScore,
                    categories = categories,
                    rating = rating,
                    address = address
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert finalCandidate data: ${e.message}", e)
                null
            }
        } ?: emptyList()

        return Vote(
            status = status,
            rankedUsers = rankedUsers,
            finalCandidates = finalCandidatesData,
            finalVotedUsers = finalVotedUsers,
            winningPlaceId = winningPlaceId,
            winningPlaceName = winningPlaceName
        )
    }

    /**
     * 투표 상태 실시간 리스너 (Flow로 반환)
     */
    fun listenToVoteStatus(groupId: String): Flow<Vote?> = callbackFlow {
        val voteRef = groupsCollection.document(groupId)
            .collection("vote")
            .document("vote")

        val listener = voteRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening to vote status: ${error.message}", error)
                trySend(null)
                return@addSnapshotListener
            }

            val vote = snapshot?.let { convertSnapshotToVote(it) }
            trySend(vote)
        }

        awaitClose { listener.remove() }
    }

    /**
     * 승리한 장소로 투표 상태 완료 처리
     */
    suspend fun setWinningPlace(groupId: String, placeId: String, placeName: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update(
                "winningPlaceId", placeId,
                "winningPlaceName", placeName,
                "status", "FINISHED"
            ).await()

            Log.d(TAG, "Winning place set: $placeName ($placeId) for group $groupId, status changed to FINISHED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set winning place: ${e.message}", e)
            throw e
        }
    }

    /**
     * 투표 상태를 RANKING으로 초기화 (이전 테스트 데이터 정리용)
     * ⚠️ 주의: 이 함수는 테스트 중에만 사용하거나, 새로운 투표 세션을 시작할 때 사용해야 합니다.
     */
    suspend fun resetVoteStatus(groupId: String) {
        try {
            val voteRef = groupsCollection.document(groupId)
                .collection("vote")
                .document("vote")

            voteRef.update(
                "status", "RANKING",
                "rankedUsers", emptyList<String>(),
                "finalCandidates", emptyList<Map<String, Any>>(),
                "finalVotedUsers", emptyList<String>(),
                "winningPlaceId", null,
                "winningPlaceName", null
            ).await()

            Log.d(TAG, "Vote status reset to RANKING for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset vote status: ${e.message}", e)
            throw e
        }
    }
}