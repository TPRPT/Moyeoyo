package com.moyeoyo.app.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.moyeoyo.app.data.local.AppDatabase
import com.moyeoyo.app.data.local.NextMeetingEntity
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
    private val auth: FirebaseAuth,
    private val voteRepository: VoteRepository
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
            status = "GROUP_CREATED"
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
            voteRepository.initializeVoteDocument(groupId)

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
     * 투표 시작 (호스트만 가능)
     * 그룹 상태를 TIME_VOTE_REQUIRED로 변경
     */
    suspend fun startVoting(groupId: String): Boolean {
        val uid = auth.currentUser?.uid ?: return false
        val groupRef = groupsCollection.document(groupId)

        return try {
            db.runTransaction { tx ->
                val snapshot = tx.get(groupRef)
                if (!snapshot.exists()) throw IllegalStateException("Group not found")

                val hostUid = snapshot.getString("hostUid")
                if (hostUid != uid) {
                    throw IllegalStateException("Only host can start voting")
                }

                tx.update(groupRef, "status", "TIME_VOTE_REQUIRED")
                null
            }.await()

            Log.d(TAG, "Voting STARTED for group: $groupId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startVoting error: ${e.message}", e)
            false
        }
    }

    /**
     * 그룹 상태 업데이트
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
     * 최종 시간 확정 및 그룹 상태 업데이트를 원자적으로 수행
     * @return Result<Unit> - 성공 시 Result.success(Unit), 실패 시 Result.failure(Exception)
     */
    suspend fun confirmFinalTimeAndState(groupId: String, date: String, time: String): Result<Unit> {
        return try {
            // Timestamp 생성 (날짜 + 시간)
            // time 형식: "HH:mm" 또는 "HH시"
            val normalizedTime = time.replace("시", ":00").replace("분", "")
            val dateTimeStr = "$date $normalizedTime"
            
            val dateTime = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .parse(dateTimeStr)
            } catch (e: Exception) {
                // 다른 형식 시도
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .parse(dateTimeStr)
            }
            
            val timestamp = if (dateTime != null) {
                Timestamp(dateTime)
            } else {
                Log.e(TAG, "날짜 파싱 실패: date=$date, time=$time, normalizedTime=$normalizedTime")
                return Result.failure(IllegalArgumentException("날짜 파싱 실패"))
            }

            // ⭐ 트랜잭션으로 원자적 연산 보장
            db.runTransaction { tx ->
                val groupRef = groupsCollection.document(groupId)
                val snapshot = tx.get(groupRef)
                
                if (!snapshot.exists()) {
                    throw IllegalStateException("Group not found")
                }
                
                // 최종 시간과 그룹 상태를 동시에 업데이트
                tx.update(
                    groupRef,
                    "confirmedTime", timestamp,
                    "status", "LOCATION_INPUT_REQUIRED"
                )
                
                null
            }.await()
            
            Log.d(TAG, "✅ 최종 시간 확정 및 상태 업데이트 완료: groupId=$groupId, date=$date, time=$time")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 확정 및 상태 업데이트 실패: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 최종 시간 확정
     */
    suspend fun setFinalTime(groupId: String, date: String, time: String): Boolean {
        return try {
            // Timestamp 생성 (날짜 + 시간)
            // time 형식: "HH:mm" 또는 "HH시"
            val normalizedTime = time.replace("시", ":00").replace("분", "")
            val dateTimeStr = "$date $normalizedTime"
            
            val dateTime = try {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .parse(dateTimeStr)
            } catch (e: Exception) {
                // 다른 형식 시도
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .parse(dateTimeStr)
            }
            
            val timestamp = if (dateTime != null) {
                Timestamp(dateTime)
            } else {
                Log.e(TAG, "날짜 파싱 실패: date=$date, time=$time, normalizedTime=$normalizedTime")
                null
            }

            if (timestamp == null) {
                return false
            }

            groupsCollection.document(groupId)
                .update(
                    "confirmedTime", timestamp,
                    "status", "LOCATION_INPUT_REQUIRED"
                )
                .await()
            
            Log.d(TAG, "✅ 최종 시간 확정: groupId=$groupId, date=$date, time=$time, timestamp=$timestamp")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 확정 실패: ${e.message}", e)
            false
        }
    }

    /**
     * ⭐ NEW: 확정된 일정 정보(장소/시간)를 Firestore에 저장하고 그룹 상태를 변경합니다.
     */
    suspend fun confirmGroupSchedule(
        context: Context,
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
                "status" to "FINALIZED",
                "groupName" to newTitle
            )

            groupRef.update(updates).await()

            // ⭐ 추가 위치 여기!!
            saveMeetingToLocal(
                context,
                groupId,
                newTitle,
                confirmedTime.toDate().time,
                confirmedPlace["name"] as? String ?: ""
            )

            // ⭐ 로컬 알림 스케줄링 (약속 전날 같은 시간)
            val scheduler = com.moyeoyo.app.services.LocalNotificationScheduler(context)
            scheduler.scheduleNotificationForMeeting(
                groupId,
                newTitle,
                confirmedTime.toDate().time,
                confirmedPlace["name"] as? String ?: "장소 미정"
            )

            Log.d(TAG, "confirmGroupSchedule SUCCESS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "confirmGroupSchedule FAILED: ${e.message}")
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
     * ⭐ NEW: 그룹 이름 수정
     */
    suspend fun updateGroupName(groupId: String, newName: String): Boolean {
        return try {
            groupsCollection.document(groupId)
                .update("groupName", newName)
                .await()
            Log.d(TAG, "updateGroupName SUCCESS: $newName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateGroupName FAILED: ${e.message}")
            false
        }
    }

    /**
     * ⭐ NEW: 확정된 일정 수정
     */
    suspend fun updateConfirmedSchedule(
        context: Context,
        groupId: String,
        confirmedTime: Timestamp,
        confirmedPlace: Map<String, Any>
    ): Boolean {
        return try {
            // Firestore 업데이트
            val groupDoc = groupsCollection.document(groupId).get().await()
            val groupName = groupDoc.getString("groupName") ?: "모임"
            
            groupsCollection.document(groupId)
                .update(
                    mapOf(
                        "confirmedTime" to confirmedTime,
                        "confirmedPlace" to confirmedPlace
                    )
                ).await()

            // ⭐ Firestore 성공 후 Room에도 저장 (groupName 올바르게 전달)
            saveMeetingToLocal(
                context,
                groupId,
                groupName,  // ⚠️ 수정: groupId 대신 groupName 사용
                confirmedTime.toDate().time,
                confirmedPlace["name"] as? String ?: ""
            )

            // ⭐ 기존 알림 취소 후 새로 스케줄링
            val scheduler = com.moyeoyo.app.services.LocalNotificationScheduler(context)
            scheduler.cancelReminderNotification(groupId)  // 기존 알림 취소
            scheduler.scheduleNotificationForMeeting(       // 새 알림 스케줄링
                groupId,
                groupName,
                confirmedTime.toDate().time,
                confirmedPlace["name"] as? String ?: "장소 미정"
            )

            Log.d(TAG, "updateConfirmedSchedule SUCCESS - RoomDB updated and notification rescheduled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateConfirmedSchedule FAILED: ${e.message}")
            false
        }
    }


    /**
     * 그룹과 그 하위 컬렉션의 모든 데이터를 삭제합니다.
     */
    suspend fun deleteGroup(context: Context, groupId: String): Boolean {
        val groupRef = groupsCollection.document(groupId)

        return try {
            val groupSnapshot = groupRef.get().await()
            @Suppress("UNCHECKED_CAST")
            val memberUids = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()

            deleteCollection(groupRef.collection("inputLocations"))
            deleteCollection(groupRef.collection("placeCandidates"))
            deleteCollection(groupRef.collection("timeCandidates"))
            deleteCollection(groupRef.collection("vote"))

            groupRef.delete().await()

            val batch = db.batch()
            memberUids.forEach { uid ->
                val userRef = usersCollection.document(uid)
                batch.update(userRef, "groups", FieldValue.arrayRemove(groupId))
            }
            batch.commit().await()

            // ⭐⭐ 여기 추가해야 Room DB에서도 삭제됨!!
            deleteMeetingFromLocal(context, groupId)

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

    private suspend fun saveMeetingToLocal(
        context: Context,
        groupId: String,
        groupName: String,
        confirmedTime: Long,
        confirmedPlace: String
    ) {
        val dao = AppDatabase.getInstance(context).nextMeetingDao()

        val entity = NextMeetingEntity(
            groupId = groupId,
            groupName = groupName,
            finalMeetingAt = confirmedTime,
            finalMeetingPlace = confirmedPlace
        )
        dao.upsert(entity)
    }
    private suspend fun deleteMeetingFromLocal(context: Context, groupId: String) {
        val dao = AppDatabase.getInstance(context).nextMeetingDao()
        dao.deleteByGroupId(groupId)
    }


}