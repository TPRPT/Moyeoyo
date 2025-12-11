package com.moyeoyo.app.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.moyeoyo.app.data.local.AppDatabase
import com.moyeoyo.app.data.local.NextMeetingEntity
import com.moyeoyo.app.data.model.Group
import kotlinx.coroutines.delay
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
    private val voteRepository: VoteRepository,
    private val timeVoteRepository: com.moyeoyo.app.data.repository.TimeVoteRepository,
    private val mapRepository: com.moyeoyo.app.data.repository.MapRepository
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

            // ⭐ 2. timeVote 컬렉션 초기화 (빈 더미 문서 생성하여 컬렉션 구조 확보)
            // Firestore는 빈 컬렉션을 만들 수 없으므로, 초기 문서를 생성하여 컬렉션 구조를 확보
            try {
                val timeVoteRef = groupsCollection.document(groupId)
                    .collection("timeVote")
                    .document("_initialized")
                
                // 이미 존재하는지 확인 후 생성
                val timeVoteSnapshot = timeVoteRef.get().await()
                if (!timeVoteSnapshot.exists()) {
                    timeVoteRef.set(mapOf(
                        "initialized" to true,
                        "createdAt" to Timestamp.now()
                    )).await()
                    Log.d(TAG, "✅ timeVote 컬렉션 초기화 완료: groupId=$groupId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ timeVote 컬렉션 초기화 중 오류 (이미 존재할 수 있음): ${e.message}")
            }

            // ⭐ 3. placeVote 컬렉션 초기화 (빈 더미 문서 생성하여 컬렉션 구조 확보)
            // 투표 시작 시 실제 placeVote 문서가 생성되지만, 그룹 생성 시점에 구조를 확보
            try {
                val placeVoteRef = groupsCollection.document(groupId)
                    .collection("placeVote")
                    .document("_initialized")
                
                // 이미 존재하는지 확인 후 생성
                val placeVoteSnapshot = placeVoteRef.get().await()
                if (!placeVoteSnapshot.exists()) {
                    placeVoteRef.set(mapOf(
                        "initialized" to true,
                        "createdAt" to Timestamp.now()
                    )).await()
                    Log.d(TAG, "✅ placeVote 컬렉션 초기화 완료: groupId=$groupId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ placeVote 컬렉션 초기화 중 오류 (이미 존재할 수 있음): ${e.message}")
            }

            // 4. 모든 멤버의 users 문서에 groupId를 groups 배열에 추가 (일괄 쓰기 사용)
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
     * 그룹에 여러 멤버를 한 번에 추가합니다.
     * @param groupId 그룹 ID
     * @param friendUids 추가할 친구들의 UID 목록
     * @return 성공 여부
     */
    suspend fun addMembersToGroup(groupId: String, friendUids: List<String>): Boolean {
        if (friendUids.isEmpty()) return true
        
        val groupRef = groupsCollection.document(groupId)
        var newMemberUids: List<String> = emptyList()
        
        return try {
            // 1. 트랜잭션으로 그룹 멤버 추가 및 inputLocations 문서 생성
            db.runTransaction { transaction ->
                val groupSnapshot = transaction.get(groupRef)
                
                if (!groupSnapshot.exists()) {
                    throw IllegalStateException("Group document does not exist.")
                }
                
                @Suppress("UNCHECKED_CAST")
                val existingMemberUids = groupSnapshot.get("memberUids") as List<String>? ?: emptyList()
                
                // ⭐ 기존 hostUid 보존 (방장 변경 방지)
                val existingHostUid = groupSnapshot.get("hostUid") as? String
                
                // 이미 멤버인 친구는 제외
                newMemberUids = friendUids.filter { it !in existingMemberUids }
                
                if (newMemberUids.isEmpty()) {
                    return@runTransaction null // 이미 모두 멤버이므로 성공으로 간주
                }
                
                // 그룹 멤버 배열 업데이트 (hostUid는 변경하지 않음)
                val updatedMembers = existingMemberUids + newMemberUids
                transaction.update(groupRef, mapOf(
                    "memberUids" to updatedMembers,
                    "hostUid" to existingHostUid // ⭐ hostUid 명시적으로 보존
                ))
                
                // 각 새 멤버의 inputLocations 문서 생성
                newMemberUids.forEach { uid ->
                    val locationRef = groupRef.collection("inputLocations").document(uid)
                    transaction.set(locationRef, mapOf(
                        "latLng" to GeoPoint(0.0, 0.0),
                        "transportMode" to "UNKNOWN",
                        "timestamp" to Timestamp.now()
                    ))
                }
                
                null
            }.await()
            
            // 2. 각 새 멤버의 users 문서에 groupId 추가 (트랜잭션 외부에서 배치 처리)
            if (newMemberUids.isNotEmpty()) {
                val batch = db.batch()
                newMemberUids.forEach { uid ->
                    val userRef = usersCollection.document(uid)
                    batch.update(userRef, "groups", FieldValue.arrayUnion(groupId))
                }
                batch.commit().await()
            }
            
            Log.d(TAG, "Members added successfully to group $groupId: ${newMemberUids.size} members")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addMembersToGroup error for group $groupId: ${e.message}", e)
            // ⭐ 예외 상세 정보 로깅
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            if (e is IllegalStateException) {
                Log.e(TAG, "IllegalStateException: ${e.message}")
            }
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

            // ⭐ 투표 시작 시 placeVote 문서 생성 (여러 사용자 동기화를 위해)
            try {
                val placeVoteRef = groupRef.collection("placeVote").document("placeVote")
                val placeVoteSnapshot = placeVoteRef.get().await()
                if (!placeVoteSnapshot.exists()) {
                    placeVoteRef.set(
                        mapOf(
                            "status" to "RANKING",
                            "rankedUsers" to emptyList<String>(),
                            "finalCandidates" to emptyList<Map<String, Any>>(),
                            "finalVotedUsers" to emptyList<String>()
                        )
                    ).await()
                    Log.d(TAG, "✅ placeVote 문서 생성 완료: groupId=$groupId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ placeVote 문서 생성 중 오류 (이미 존재할 수 있음): ${e.message}")
            }

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

            // ⭐ 현재 상태 확인
            val currentGroup = groupsCollection.document(groupId).get().await()
            val currentStatus = currentGroup.getString("status")
            val memberUids = currentGroup.get("memberUids") as? List<String> ?: emptyList()
            
            // ⭐ 모든 멤버가 시간 투표를 완료했는지 확인 (중복 알림 방지)
            val allMembersVoted = if (memberUids.isNotEmpty()) {
                // 현재 주의 모든 날짜 확인
                val now = java.util.Calendar.getInstance()
                val monday = java.util.Calendar.getInstance()
                monday.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                monday.set(java.util.Calendar.HOUR_OF_DAY, 0)
                monday.set(java.util.Calendar.MINUTE, 0)
                monday.set(java.util.Calendar.SECOND, 0)
                
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                var allVoted = false
                
                for (i in 0..6) {
                    val date = java.util.Calendar.getInstance()
                    date.timeInMillis = monday.timeInMillis + (i * 24 * 60 * 60 * 1000)
                    val dateStr = dateFormat.format(date.time)
                    
                    val voted = timeVoteRepository.checkAllMembersVoted(groupId, dateStr, memberUids)
                    if (voted) {
                        allVoted = true
                        break
                    }
                }
                allVoted
            } else {
                false
            }
            
            // ⭐ 1단계: TIME_FINALIZING 상태로 변경 (푸시 알림 전송) - 모든 멤버가 완료했을 때만 변경
            if (currentStatus != "TIME_FINALIZING" && allMembersVoted) {
                groupsCollection.document(groupId)
                    .update("status", "TIME_FINALIZING")
                    .await()
                
                Log.d(TAG, "✅ TIME_FINALIZING 상태로 변경: groupId=$groupId (모든 멤버 시간 투표 완료)")
                
                // ⭐ 푸시 알림 전송 시간 확보 (2초 대기)
                kotlinx.coroutines.delay(2000)
            } else {
                if (currentStatus == "TIME_FINALIZING") {
                    Log.d(TAG, "ℹ️ 이미 TIME_FINALIZING 상태이므로 변경하지 않음: groupId=$groupId")
                } else {
                    Log.d(TAG, "ℹ️ 아직 모든 멤버가 시간 투표를 완료하지 않음: groupId=$groupId")
                    return Result.failure(IllegalStateException("아직 모든 멤버가 시간 투표를 완료하지 않았습니다."))
                }
            }
            
            // ⭐ 2단계: 모든 멤버가 최종 시간 투표를 완료했는지 확인
            val allMembersFinalVoted = if (memberUids.isNotEmpty()) {
                val now = java.util.Calendar.getInstance()
                val monday = java.util.Calendar.getInstance()
                monday.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
                monday.set(java.util.Calendar.HOUR_OF_DAY, 0)
                monday.set(java.util.Calendar.MINUTE, 0)
                monday.set(java.util.Calendar.SECOND, 0)
                
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                var allFinalVoted = false
                
                for (i in 0..6) {
                    val date = java.util.Calendar.getInstance()
                    date.timeInMillis = monday.timeInMillis + (i * 24 * 60 * 60 * 1000)
                    val dateStr = dateFormat.format(date.time)
                    
                    val finalVoted = timeVoteRepository.checkAllMembersFinalVoted(groupId, dateStr, memberUids)
                    if (finalVoted) {
                        allFinalVoted = true
                        break
                    }
                }
                allFinalVoted
            } else {
                false
            }
            
            // ⭐ 모든 멤버가 최종 시간 투표를 완료했을 때만 LOCATION_INPUT_REQUIRED로 변경
            if (!allMembersFinalVoted) {
                Log.d(TAG, "ℹ️ 아직 모든 멤버가 최종 시간 투표를 완료하지 않음: groupId=$groupId")
                return Result.failure(IllegalStateException("아직 모든 멤버가 최종 시간 투표를 완료하지 않았습니다."))
            }
            
            // ⭐ 3단계: 트랜잭션으로 최종 시간 확정 및 LOCATION_INPUT_REQUIRED 상태로 변경
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
     * ⭐ TIME_FINALIZING 상태를 거쳐서 푸시 알림이 전송되도록 함
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

            // ⭐ 현재 상태 확인
            val currentGroup = groupsCollection.document(groupId).get().await()
            val currentStatus = currentGroup.getString("status")
            
            // ⭐ 1단계: TIME_FINALIZING 상태로 변경 (푸시 알림 전송) - 이미 TIME_FINALIZING이 아닌 경우에만 변경
            if (currentStatus != "TIME_FINALIZING") {
                groupsCollection.document(groupId)
                    .update("status", "TIME_FINALIZING")
                    .await()
                
                Log.d(TAG, "✅ TIME_FINALIZING 상태로 변경: groupId=$groupId")
                
                // ⭐ 푸시 알림 전송 시간 확보 (2초 대기)
                kotlinx.coroutines.delay(2000)
            } else {
                Log.d(TAG, "ℹ️ 이미 TIME_FINALIZING 상태이므로 변경하지 않음: groupId=$groupId")
            }
            
            // ⭐ 2단계: 최종 시간 확정 및 LOCATION_INPUT_REQUIRED 상태로 변경
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
     * ⭐ FINAL_PLACE_VOTE 상태를 거쳐서 푸시 알림이 전송되도록 함
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
            // ⭐ 현재 상태 확인
            val currentGroup = groupRef.get().await()
            val currentStatus = currentGroup.getString("status")
            val memberUids = currentGroup.get("memberUids") as? List<String> ?: emptyList()
            
            // ⭐ 모든 멤버가 장소 순위 투표를 완료했는지 확인 (중복 알림 방지)
            val allMembersRanked = if (memberUids.isNotEmpty()) {
                voteRepository.checkAllUsersRanked(groupId, memberUids)
            } else {
                false
            }
            
            // ⭐ 모든 멤버가 장소 순위 투표를 완료했을 때만 FINAL_PLACE_VOTE로 변경
            if (currentStatus != "FINAL_PLACE_VOTE" && allMembersRanked) {
                // ⭐ 1단계: FINAL_PLACE_VOTE 상태로 변경 (푸시 알림 전송)
                groupRef.update("status", "FINAL_PLACE_VOTE").await()
                Log.d(TAG, "✅ FINAL_PLACE_VOTE 상태로 변경: groupId=$groupId (모든 멤버 장소 순위 투표 완료)")
                
                // ⭐ 푸시 알림 전송 시간 확보 (2초 대기)
                kotlinx.coroutines.delay(2000)
            } else {
                if (currentStatus == "FINAL_PLACE_VOTE") {
                    Log.d(TAG, "ℹ️ 이미 FINAL_PLACE_VOTE 상태이므로 변경하지 않음: groupId=$groupId")
                } else {
                    Log.d(TAG, "ℹ️ 아직 모든 멤버가 장소 순위 투표를 완료하지 않음: groupId=$groupId")
                    return false
                }
            }
            
            // ⭐ 모든 멤버가 최종 장소 투표를 완료했는지 확인
            val allMembersFinalVoted = if (memberUids.isNotEmpty()) {
                voteRepository.checkAllUsersFinalVoted(groupId, memberUids)
            } else {
                false
            }
            
            // ⭐ 모든 멤버가 최종 장소 투표를 완료했을 때만 FINALIZED로 변경
            if (!allMembersFinalVoted) {
                Log.d(TAG, "ℹ️ 아직 모든 멤버가 최종 장소 투표를 완료하지 않음: groupId=$groupId")
                return false
            }
            
            // ⭐ 2단계: 최종 확정 정보 저장 및 FINALIZED 상태로 변경
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
                
                // ⭐ 기존 hostUid 보존 (방장 변경 방지)
                val existingHostUid = groupSnapshot.get("hostUid") as? String

                if (!currentMembers.contains(memberUidToRemove)) {
                    Log.w(TAG, "Member to remove is not in the group.")
                    return@runTransaction null
                }

                // 1. 그룹 멤버 배열 업데이트 (groups/{groupId}) - hostUid는 변경하지 않음
                val newMembers = currentMembers.filter { it != memberUidToRemove }
                transaction.update(groupRef, mapOf(
                    "memberUids" to newMembers,
                    "hostUid" to existingHostUid // ⭐ hostUid 명시적으로 보존
                ))

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
     * ⭐ NEW: 확정된 시간만 수정 (장소는 변경하지 않음)
     */
    suspend fun updateConfirmedTime(
        context: Context,
        groupId: String,
        confirmedTime: Timestamp
    ): Boolean {
        return try {
            // Firestore 업데이트 (시간만 업데이트)
            groupsCollection.document(groupId)
                .update("confirmedTime", confirmedTime)
                .await()

            Log.d(TAG, "updateConfirmedTime SUCCESS - 시간만 업데이트됨")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateConfirmedTime FAILED: ${e.message}")
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
            deleteCollection(groupRef.collection("placeVote"))
            deleteCollection(groupRef.collection("timeVote"))

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
    /**
     * 컬렉션의 모든 문서를 재귀적으로 삭제합니다.
     * 하위 컬렉션도 함께 삭제합니다.
     */
    private suspend fun deleteCollection(collectionRef: CollectionReference, batchSize: Int = 100) {
        while (true) {
            val snapshot = collectionRef.limit(batchSize.toLong()).get().await()
            if (snapshot.isEmpty) {
                break
            }

            val batch = db.batch()
            snapshot.documents.forEach { document ->
                // 하위 컬렉션 목록 가져오기 (Firestore는 하위 컬렉션 목록을 직접 제공하지 않으므로
                // 문서의 하위 컬렉션을 확인하기 위해 문서를 읽어야 함)
                // 하지만 성능상의 이유로, 알려진 하위 컬렉션만 삭제하는 방식으로 처리
                batch.delete(document.reference)
            }
            batch.commit().await()
        }
    }
    
    /**
     * 문서와 모든 하위 컬렉션을 재귀적으로 삭제합니다.
     */
    private suspend fun deleteDocumentRecursively(docRef: DocumentReference) {
        try {
            // 알려진 하위 컬렉션 삭제
            val knownSubcollections = listOf("times", "voters")
            knownSubcollections.forEach { subcollectionName ->
                try {
                    val subcollectionRef = docRef.collection(subcollectionName)
                    deleteCollection(subcollectionRef)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 하위 컬렉션 삭제 중 오류 (없을 수 있음): $subcollectionName, ${e.message}")
                }
            }
            
            // 문서 삭제
            docRef.delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 문서 재귀 삭제 실패: ${docRef.path}, ${e.message}", e)
            throw e
        }
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

    /**
     * 전체 투표 초기화: 모든 투표 결과를 초기화하고 그룹을 초기 상태로 되돌립니다.
     * ⭐ 중요: 문서나 컬렉션은 삭제하지 않고 필드만 초기화합니다.
     * - 그룹 상태를 "GROUP_CREATED"로 변경
     * - 확정된 시간/장소 필드 삭제
     * - 모든 투표 데이터 필드 초기화 (시간 투표, 장소 투표, 순위 투표)
     * - 모든 멤버의 후보군 필드 초기화 (userRankings, placeCandidates 등)
     * - 입력된 위치 정보 필드 초기화 (inputLocations)
     * - 중간 지점 정보 필드 삭제 (midPoint)
     */
    suspend fun resetAllVotes(context: Context, groupId: String): Boolean {
        return try {
            val groupRef = groupsCollection.document(groupId)

            // 1. 그룹 상태를 GROUP_CREATED로 변경하고 확정된 시간/장소, 중간 지점 삭제
            groupRef.update(
                mapOf(
                    "status" to "GROUP_CREATED",
                    "confirmedTime" to FieldValue.delete(),
                    "confirmedPlace" to FieldValue.delete(),
                    "midPoint" to FieldValue.delete() // ⭐ 중간 지점 정보 삭제
                )
            ).await()

            // 2. ⭐ placeVote 문서 필드만 초기화 (문서는 유지)
            // placeVote 구조: groups/{groupId}/placeVote/placeVote (단일 문서)
            val placeVoteRef = groupRef.collection("placeVote")
            try {
                val placeVoteDocRef = placeVoteRef.document("placeVote")
                val placeVoteSnapshot = placeVoteDocRef.get().await()
                
                if (placeVoteSnapshot.exists()) {
                    // 문서가 있으면 필드만 초기화
                    placeVoteDocRef.update(
                        mapOf(
                            "status" to "RANKING",
                            "rankedUsers" to emptyList<String>(),
                            "finalCandidates" to emptyList<Map<String, Any>>(),
                            "finalVotedUsers" to emptyList<String>(),
                            "winningPlaceId" to FieldValue.delete(),
                            "winningPlaceName" to FieldValue.delete()
                        )
                    ).await()
                    Log.d(TAG, "✅ placeVote 문서 필드 초기화 완료")
                } else {
                    // 문서가 없으면 초기 상태로 생성
                    placeVoteDocRef.set(
                        mapOf(
                            "status" to "RANKING",
                            "rankedUsers" to emptyList<String>(),
                            "finalCandidates" to emptyList<Map<String, Any>>(),
                            "finalVotedUsers" to emptyList<String>()
                        )
                    ).await()
                    Log.d(TAG, "✅ placeVote 문서 생성 완료")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ placeVote 초기화 중 오류: ${e.message}")
            }

            // 3. ⭐ timeVote 컬렉션 완전히 비우기 (_initialized 문서 제외)
            // timeVote 구조: groups/{groupId}/timeVote/{date}/times/{time} (voters는 배열 필드)
            val timeVoteRef = groupRef.collection("timeVote")
            
            try {
                // ⭐ 모든 날짜 문서를 가져와서 완전히 삭제 (재시도 로직 포함)
                var retryCount = 0
                val maxRetries = 10
                
                while (retryCount < maxRetries) {
                    val dateSnapshot = timeVoteRef.limit(100).get().await()
                    
                    if (dateSnapshot.isEmpty) {
                        Log.d(TAG, "✅ timeVote 컬렉션이 완전히 비어있음 (시도 ${retryCount + 1})")
                        break
                    }
                    
                    Log.d(TAG, "🔄 timeVote 완전 삭제 시작: ${dateSnapshot.size()}개 날짜 문서 (시도 ${retryCount + 1})")
                    
                    // 각 날짜 문서와 하위 컬렉션 완전히 삭제
                    dateSnapshot.documents.forEach { dateDoc ->
                        try {
                            // ⭐ _initialized 문서는 건너뛰기
                            if (dateDoc.id == "_initialized") {
                                return@forEach
                            }
                            
                            // 1. 각 날짜 문서의 하위 컬렉션(times)의 모든 문서 삭제
                            val timesRef = dateDoc.reference.collection("times")
                            
                            // ⭐ times 컬렉션의 모든 문서를 삭제 (재시도 로직 포함)
                            var timesRetry = 0
                            var hasMoreTimes = true
                            while (timesRetry < 5 && hasMoreTimes) {
                                val timesSnapshot = timesRef.limit(100).get().await()
                                
                                if (timesSnapshot.isEmpty) {
                                    hasMoreTimes = false
                                    break
                                }
                                
                                // 모든 시간 문서 삭제
                                timesSnapshot.documents.forEach { timeDoc ->
                                    try {
                                        timeDoc.reference.delete().await()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "⚠️ 시간 문서 삭제 중 오류: ${timeDoc.id}, ${e.message}")
                                    }
                                }
                                
                                timesRetry++
                                if (timesRetry < 5 && timesSnapshot.size() >= 100) {
                                    // 더 많은 문서가 있을 수 있으므로 잠시 대기 후 재시도
                                    kotlinx.coroutines.delay(200)
                                }
                            }
                            
                            // 2. ⭐ 하위 컬렉션 삭제 완료 후 날짜 문서도 완전히 삭제
                            kotlinx.coroutines.delay(300)
                            
                            try {
                                dateDoc.reference.delete().await()
                                Log.d(TAG, "✅ 날짜 문서 완전 삭제 완료: ${dateDoc.id}")
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ 날짜 문서 삭제 중 오류: ${dateDoc.id}, ${e.message}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ 날짜 문서 삭제 중 오류: ${dateDoc.id}, ${e.message}")
                        }
                    }
                    
                    retryCount++
                    
                    // 잠시 대기 후 다시 확인 (Firestore 동기화 시간 확보)
                    if (retryCount < maxRetries) {
                        kotlinx.coroutines.delay(500)
                    }
                }
                
                Log.d(TAG, "✅ timeVote 컬렉션 완전히 비움 완료 (_initialized 문서 제외)")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ timeVote 초기화 중 오류: ${e.message}")
            }

            // 4. ⭐ 장소 후보 컬렉션 필드만 초기화 (문서는 유지)
            // placeCandidates는 문서 자체가 후보이므로 삭제가 맞지만, 사용자 요청에 따라 필드만 초기화
            try {
                val placeCandidatesRef = groupRef.collection("placeCandidates")
                val placeCandidatesSnapshot = placeCandidatesRef.limit(100).get().await()
                
                placeCandidatesSnapshot.documents.forEach { doc ->
                    try {
                        // voterUids 필드만 초기화
                        doc.reference.update(
                            mapOf("voterUids" to emptyList<String>())
                        ).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ placeCandidate 필드 초기화 중 오류: ${doc.id}, ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ placeCandidates 필드 초기화 완료")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ placeCandidates 초기화 중 오류: ${e.message}")
            }

            // 5. ⭐ 시간 후보 컬렉션 필드만 초기화 (문서는 유지)
            try {
                val timeCandidatesRef = groupRef.collection("timeCandidates")
                val timeCandidatesSnapshot = timeCandidatesRef.limit(100).get().await()
                
                timeCandidatesSnapshot.documents.forEach { doc ->
                    try {
                        // voterUids 필드만 초기화
                        doc.reference.update(
                            mapOf("voterUids" to emptyList<String>())
                        ).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ timeCandidate 필드 초기화 중 오류: ${doc.id}, ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ timeCandidates 필드 초기화 완료")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ timeCandidates 초기화 중 오류: ${e.message}")
            }

            // 6. ⭐ 모든 멤버의 장소 순위 지정(userRankings) 필드만 초기화 (문서는 유지)
            try {
                val userRankingsRef = groupRef.collection("userRankings")
                val userRankingsSnapshot = userRankingsRef.limit(100).get().await()
                
                userRankingsSnapshot.documents.forEach { doc ->
                    try {
                        // rankedPlaces 필드만 초기화
                        doc.reference.update(
                            mapOf("rankedPlaces" to emptyList<Map<String, Any>>())
                        ).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ userRanking 필드 초기화 중 오류: ${doc.id}, ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ userRankings 필드 초기화 완료")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ userRankings 초기화 중 오류: ${e.message}")
            }

            // 7. ⭐ 모든 멤버의 입력 위치(inputLocations) 필드만 초기화 (문서는 유지)
            // 호스트의 inputLocation은 그룹 생성 시 생성되므로 유지
            try {
                val inputLocationsRef = groupRef.collection("inputLocations")
                val inputLocationsSnapshot = inputLocationsRef.limit(100).get().await()
                
                inputLocationsSnapshot.documents.forEach { doc ->
                    try {
                        // 위치 정보만 초기화 (문서는 유지)
                        doc.reference.update(
                            mapOf(
                                "latLng" to GeoPoint(0.0, 0.0),
                                "transportMode" to "UNKNOWN",
                                "timestamp" to Timestamp.now()
                            )
                        ).await()
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ inputLocation 필드 초기화 중 오류: ${doc.id}, ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ inputLocations 필드 초기화 완료")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ inputLocations 초기화 중 오류: ${e.message}")
            }

            // 8. Room DB에서도 삭제
            deleteMeetingFromLocal(context, groupId)
            
            // 9. ⭐ 추가 지연을 두어 Firestore 동기화 완료 보장
            kotlinx.coroutines.delay(1000)

            Log.d(TAG, "✅ 모든 투표 초기화 완료: groupId=$groupId (모든 필드 초기화 완료, 문서/컬렉션 구조 유지)")
            true
        } catch (e: Exception) {
            // ⭐ 상세한 에러 로깅
            Log.e(TAG, "❌ 투표 초기화 실패: groupId=$groupId", e)
            Log.e(TAG, "❌ 에러 타입: ${e.javaClass.simpleName}, 메시지: ${e.message}")
            e.printStackTrace()
            false
        }
    }


}