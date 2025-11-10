// com.moyeoyo.app.data.repository/GroupRepository.kt

package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.moyeoyo.app.data.model.Group // ⭐ Group 모델 클래스 필요
import kotlinx.coroutines.tasks.await

/**
 * 모임(Group) 데이터의 CRUD를 처리하는 Repository.
 */
class GroupRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val groupsCollection = db.collection("groups")

    /**
     * 새로운 모임 방을 생성하고 Firestore에 저장합니다.
     * @param groupName 모임 이름 (String)
     * @return 성공 시 생성된 그룹 ID (String), 실패 시 null
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

            groupsCollection.document(groupId)
                .collection("inputLocations")
                .document(hostUid)
                .set(mapOf(
                    "latLng" to GeoPoint(0.0, 0.0),
                    "transportMode" to "UNKNOWN",
                    "timestamp" to Timestamp.now()
                )).await()

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

        return try {
            db.runTransaction { transaction ->
                val groupSnapshot = transaction.get(groupRef)

                if (!groupSnapshot.exists()) {
                    throw Exception("Group document does not exist.")
                }

                @Suppress("UNCHECKED_CAST")
                val memberUids = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()
                if (!memberUids.contains(uid)) {
                    val newMembers = memberUids + uid
                    transaction.update(groupRef, "memberUids", newMembers)
                }

                val locationRef = groupRef.collection("inputLocations").document(uid)
                if (!transaction.get(locationRef).exists()) {
                    transaction.set(locationRef, mapOf(
                        "latLng" to GeoPoint(0.0, 0.0),
                        "transportMode" to "UNKNOWN",
                        "timestamp" to Timestamp.now()
                    ))
                }
                null // 트랜잭션 성공
            }.await()
            true
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to join group $groupId: ${e.message}", e)
            false
        }
    }

    /**
     * 현재 로그인된 사용자가 참여 중인 모든 그룹의 목록을 조회합니다.
     * @return Group 객체 리스트 (List<Group>)
     */
    suspend fun getGroupsForUser(): List<Group> {
        val uid = auth.currentUser?.uid ?: return emptyList()

        return try {
            val snapshot = groupsCollection
                .whereArrayContains("memberUids", uid)
                .get()
                .await()

            // Group 모델 클래스에 ID가 포함되어 있지 않다면, .id를 수동으로 매핑하는 로직이 필요합니다.
            snapshot.toObjects(Group::class.java)
        } catch (e: Exception) {
            Log.e("GroupRepository", "Error fetching user groups: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * ⭐ 추가됨: 특정 그룹의 상세 정보를 Group 객체로 조회합니다.
     * @param groupId 조회할 그룹 ID
     * @return Group 객체 또는 null
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
     * @param groupId 삭제할 그룹 ID
     * @return 성공 여부
     */
    suspend fun deleteGroup(groupId: String): Boolean {
        val groupRef = groupsCollection.document(groupId)

        return try {
            // 1. 하위 컬렉션 문서 삭제 (Firestore의 제약으로 인해 수동 삭제 필요)
            deleteCollection(groupRef.collection("inputLocations"))
            deleteCollection(groupRef.collection("placeCandidates"))
            deleteCollection(groupRef.collection("timeCandidates"))

            // 2. 그룹 문서 자체 삭제
            groupRef.delete().await()

            Log.d("GroupRepository", "Group deleted successfully: $groupId")
            true
        } catch (e: Exception) {
            Log.e("GroupRepository", "Failed to delete group $groupId: ${e.message}", e)
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
}