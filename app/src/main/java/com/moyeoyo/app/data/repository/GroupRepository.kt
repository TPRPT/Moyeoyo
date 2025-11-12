// com.moyeoyo.app.data.repository/GroupRepository.kt

package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue // ⭐ 추가: groups 배열 관리를 위해 필요
import com.google.firebase.firestore.FieldPath // ⭐ 추가: Document ID로 whereIn 쿼리를 위해 필요
import com.moyeoyo.app.data.model.Group
import kotlinx.coroutines.tasks.await

/**
 * 모임(Group) 데이터의 CRUD를 처리하는 Repository.
 */
class GroupRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val groupsCollection = db.collection("groups")
    private val usersCollection = db.collection("users") // ⭐ 추가: users 컬렉션 참조

    /**
     * 새로운 모임 방을 생성하고 Firestore에 저장합니다.
     */
    suspend fun createGroup(groupName: String): String? {
        val hostUid = auth.currentUser?.uid ?: return null

        val newGroup = Group(
            groupName = groupName,
            hostUid = hostUid,
            memberUids = listOf(hostUid),
            status = "VOTING"
        )

        return try {
            val groupRef = groupsCollection.add(newGroup).await()
            val groupId = groupRef.id

            // 1. Host의 inputLocation 문서 생성 (기존 로직 유지)
            groupsCollection.document(groupId)
                .collection("inputLocations")
                .document(hostUid)
                .set(mapOf(
                    "latLng" to GeoPoint(0.0, 0.0),
                    "transportMode" to "UNKNOWN",
                    "timestamp" to Timestamp.now()
                )).await()

            // 2. ⭐ NEW: 호스트의 users 문서에 groupId를 groups 배열에 추가
            usersCollection.document(hostUid)
                .update("groups", FieldValue.arrayUnion(groupId))
                .await()

            Log.d("GroupRepository", "Group created successfully with ID: $groupId")
            groupId
        } catch (e: Exception) {
            Log.e("GroupRepository", "Group creation failed: ${e.message}", e)
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
        val userRef = usersCollection.document(uid) // ⭐ 추가: 참여 사용자 문서 참조

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
                    return@runTransaction null
                }

                // 1. 그룹 멤버 배열 업데이트 (groups/{groupId})
                val newMembers = memberUids + uid
                transaction.update(groupRef, "memberUids", newMembers)

                // 2. inputLocations 하위 컬렉션 문서 생성
                val locationRef = groupRef.collection("inputLocations").document(uid)
                transaction.set(locationRef, mapOf(
                    "latLng" to GeoPoint(0.0, 0.0),
                    "transportMode" to "UNKNOWN",
                    "timestamp" to Timestamp.now()
                ))

                // 3. ⭐ NEW: 참여하는 사용자의 users 문서에 groupId 추가
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
                    .whereIn(FieldPath.documentId(), chunk) // Document ID로 쿼리
                    .get()
                    .await()
                groups.addAll(snapshot.toObjects(Group::class.java))
            }

            // groups 배열 순서를 유지하고 싶다면 여기서 정렬 로직 추가
            // groups.sortedBy { groupIds.indexOf(it.id) }

            groups

        } catch (e: Exception) {
            Log.e("GroupRepository", "Error fetching user groups: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 특정 그룹의 상세 정보를 Group 객체로 조회합니다.
     * (변경 없음)
     */
    suspend fun getGroupDetail(groupId: String): Group? {
        return try {
            groupsCollection.document(groupId)
                .get()
                .await()
                .toObject(Group::class.java)
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error fetching group detail: ${e.message}")
            null
        }
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

            // 4. ⭐ NEW: 모든 멤버의 users 문서에서 groupId를 groups 배열에서 제거 (일괄 쓰기 사용)
            val batch = db.batch()
            memberUids.forEach { uid ->
                val userRef = usersCollection.document(uid)
                batch.update(userRef, "groups", FieldValue.arrayRemove(groupId))
            }
            batch.commit().await()

            Log.d("GroupRepository", "Group deleted successfully: $groupId")
            true
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to delete group $groupId: ${e.message}", e)
            false
        }
    }

    /**
     * Firestore 컬렉션의 모든 문서를 삭제하는 유틸리티 함수입니다.
     * (변경 없음)
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
}