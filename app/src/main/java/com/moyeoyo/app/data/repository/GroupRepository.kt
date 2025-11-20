package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.moyeoyo.app.data.model.Group
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
            status = "GROUP_CREATED" // 초기 상태: 그룹 생성 완료
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

            // 2. 모든 멤버의 users 문서에 groupId를 groups 배열에 추가 (일괄 쓰기 사용)
            val batch = db.batch()
            allMemberUids.forEach { uid ->
                val userRef = usersCollection.document(uid)
                batch.update(userRef, "groups", FieldValue.arrayUnion(groupId))
            }
            batch.commit().await()

            Log.d("GroupRepository", "Group created successfully with ID: $groupId, Members: ${allMemberUids.size}")
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
            deleteCollection(groupRef.collection("vote"))

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

    /**
     * 그룹 상태 업데이트
     * @param groupId 그룹 ID
     * @param status 새로운 상태 (GROUP_CREATED, TIME_VOTE_REQUIRED, TIME_FINALIZING, LOCATION_INPUT_REQUIRED, LOCATION_DONE, PLACE_RANKING, FINAL_PLACE_VOTE, FINALIZED, REMINDER_SCHEDULED)
     */
    suspend fun updateGroupStatus(groupId: String, status: String): Boolean {
        return try {
            groupsCollection.document(groupId)
                .update("status", status)
                .await()
            
            Log.d(TAG, "✅ 그룹 상태 업데이트: groupId=$groupId, status=$status")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 그룹 상태 업데이트 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 최종 시간 확정
     * @param groupId 그룹 ID
     * @param date 날짜 (yyyy-MM-dd)
     * @param time 시간 (HH:00)
     */
    suspend fun setFinalTime(groupId: String, date: String, time: String): Boolean {
        return try {
            // Timestamp 생성 (날짜 + 시간)
            val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .parse("$date $time")
            
            val timestamp = if (dateTime != null) {
                Timestamp(dateTime)
            } else {
                null
            }

            groupsCollection.document(groupId)
                .update(
                    "confirmedTime", timestamp,
                    "status", "LOCATION_INPUT_REQUIRED"
                )
                .await()
            
            Log.d(TAG, "✅ 최종 시간 확정: groupId=$groupId, date=$date, time=$time")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 확정 실패: ${e.message}", e)
            false
        }
    }
}