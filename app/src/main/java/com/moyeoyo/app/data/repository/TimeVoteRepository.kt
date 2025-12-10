package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 시간 투표 데이터를 관리하는 Repository
 * Firestore 구조: groups/{groupId}/timeVotes/{date}/times/{time}/voters: [uid1, uid2, ...]
 */
@Singleton
class TimeVoteRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    private val TAG = "TimeVoteRepository"

    /**
     * 특정 날짜의 시간에 투표
     * @param groupId 그룹 ID
     * @param date 날짜 (yyyy-MM-dd 형식)
     * @param time 시간 (HH:00 형식, 예: "14:00")
     * @param uid 투표한 사용자 UID
     */
    suspend fun voteTime(groupId: String, date: String, time: String, uid: String) {
        try {
            val timeVoteRef = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .collection("times")
                .document(time)

            timeVoteRef.set(
                mapOf("voters" to FieldValue.arrayUnion(uid)),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()

            Log.d(TAG, "✅ 시간 투표 저장: groupId=$groupId, date=$date, time=$time, uid=$uid")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 시간 투표 저장 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 특정 날짜의 시간 투표 취소
     */
    suspend fun unvoteTime(groupId: String, date: String, time: String, uid: String) {
        try {
            val timeVoteRef = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .collection("times")
                .document(time)

            timeVoteRef.update("voters", FieldValue.arrayRemove(uid)).await()
            Log.d(TAG, "✅ 시간 투표 취소: groupId=$groupId, date=$date, time=$time, uid=$uid")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 시간 투표 취소 실패: ${e.message}", e)
            throw e
        }
    }

    /**
     * 특정 날짜의 모든 시간 투표를 실시간으로 관찰
     * @return Map<String, List<String>> - 시간(키)과 투표한 사용자 UID 리스트(값)
     */
    fun observeVotes(groupId: String, date: String): Flow<Map<String, List<String>>> = callbackFlow {
        val timeVotesRef = db.collection("groups")
            .document(groupId)
            .collection("timeVotes")
            .document(date)
            .collection("times")

        val listenerRegistration = timeVotesRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "시간 투표 리스너 오류: ${error.message}", error)
                close(error)
                return@addSnapshotListener
            }

            if (snapshot == null) {
                trySend(emptyMap())
                return@addSnapshotListener
            }

            val votesMap = mutableMapOf<String, List<String>>()
            snapshot.documents.forEach { doc ->
                val time = doc.id
                @Suppress("UNCHECKED_CAST")
                val voters = doc.get("voters") as? List<String> ?: emptyList()
                votesMap[time] = voters
            }

            Log.d(TAG, "📊 시간 투표 업데이트: $votesMap")
            trySend(votesMap)
        }

        awaitClose { listenerRegistration.remove() }
    }

    /**
     * 특정 날짜의 모든 시간 투표 조회
     */
    suspend fun getVotes(groupId: String, date: String): Map<String, List<String>> {
        return try {
            val snapshot = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .collection("times")
                .get()
                .await()

            val votesMap = mutableMapOf<String, List<String>>()
            snapshot.documents.forEach { doc ->
                val time = doc.id
                @Suppress("UNCHECKED_CAST")
                val voters = doc.get("voters") as? List<String> ?: emptyList()
                votesMap[time] = voters
            }

            Log.d(TAG, "📥 시간 투표 조회 완료: $votesMap")
            votesMap
        } catch (e: Exception) {
            Log.e(TAG, "❌ 시간 투표 조회 실패: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * 모든 멤버의 투표를 기반으로 겹치는 시간 찾기
     * @param groupId 그룹 ID
     * @param date 날짜
     * @param memberUids 모든 멤버 UID 리스트
     * @return 겹치는 시간과 투표한 멤버 수 Map
     */
    suspend fun getOverlappingTimes(
        groupId: String,
        date: String,
        memberUids: List<String>
    ): Map<String, Int> {
        val votes = getVotes(groupId, date)
        val overlappingTimes = mutableMapOf<String, Int>()

        votes.forEach { (time, voters) ->
            val voteCount = voters.size
            // 모든 멤버가 투표한 시간만 겹치는 시간으로 간주
            if (voteCount == memberUids.size) {
                overlappingTimes[time] = voteCount
            }
        }

        Log.d(TAG, "🔍 겹치는 시간: $overlappingTimes")
        return overlappingTimes
    }

    /**
     * 최종 시간 투표 (겹치는 시간 중에서 선택)
     * @param groupId 그룹 ID
     * @param date 날짜
     * @param time 선택한 시간
     * @param uid 투표한 사용자 UID
     */
    suspend fun voteFinalTime(groupId: String, date: String, time: String, uid: String) {
        try {
            val finalVoteRef = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)

            // 기존 finalVotes 맵을 가져오기 (없으면 빈 맵)
            val existingDoc = finalVoteRef.get().await()
            @Suppress("UNCHECKED_CAST")
            val existingFinalVotes = existingDoc.get("finalVotes") as? Map<String, String> ?: emptyMap()
            
            // 새로운 finalVotes 맵 생성 (기존 값 유지 + 새 값 추가)
            val updatedFinalVotes = existingFinalVotes.toMutableMap().apply {
                put(uid, time)
            }

            // ⭐ update() 대신 set(..., SetOptions.merge()) 사용
            // 문서가 없으면 새로 만들고, 있으면 finalVotes와 finalVotedUsers 필드만 업데이트 (병합)
            finalVoteRef.set(
                mapOf(
                    "finalVotes" to updatedFinalVotes,
                    "finalVotedUsers" to FieldValue.arrayUnion(uid),
                    "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()

            Log.d(TAG, "✅ 최종 시간 투표 저장: groupId=$groupId, date=$date, time=$time, uid=$uid")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 투표 저장 실패: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * 최종 시간 투표를 제출하고 만장일치 여부를 확인
     * @param groupId 그룹 ID
     * @param date 날짜 (yyyy-MM-dd 형식)
     * @param time 시간 (HH:mm 형식)
     * @param memberUids 모든 멤버 UID 리스트
     * @return 만장일치 여부 (true: 만장일치, false: 아직 만장일치 아님)
     */
    suspend fun submitAndCheckFinalVote(
        groupId: String,
        date: String,
        time: String,
        memberUids: List<String>
    ): Boolean {
        return try {
            val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                ?: return false
            
            // 1. 투표 저장
            voteFinalTime(groupId, date, time, uid)
            
            // 2. 만장일치 여부 확인
            val finalVotes = getFinalVotes(groupId, date)
            val voteCounts = finalVotes.groupingBy { it }.eachCount()
            val totalMembers = memberUids.size
            
            // 만장일치: 특정 시간에 모든 멤버가 투표한 경우
            val isUnanimous = voteCounts.entries.any { it.value == totalMembers }
            
            Log.d(TAG, "📊 최종 투표 제출 및 만장일치 확인: isUnanimous=$isUnanimous, voteCounts=$voteCounts, totalMembers=$totalMembers")
            isUnanimous
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 투표 제출 및 만장일치 확인 실패: ${e.message}", e)
            false
        }
    }
    
    /**
     * 최종 시간 투표에서 각 사용자가 선택한 시간 목록 조회
     * @return List<String> - 각 사용자가 선택한 시간 리스트
     */
    suspend fun getFinalVotes(groupId: String, date: String): List<String> {
        return try {
            val doc = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .get()
                .await()

            @Suppress("UNCHECKED_CAST")
            val finalVotes = doc.get("finalVotes") as? Map<String, String> ?: emptyMap()
            val times = finalVotes.values.toList()
            Log.d(TAG, "📥 최종 시간 투표 목록: $times")
            times
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 투표 목록 조회 실패: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 최종 시간 투표 결과 조회
     */
    suspend fun getFinalTimeVote(groupId: String, date: String): String? {
        return try {
            val doc = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .get()
                .await()

            val finalTime = doc.getString("finalTime")
            Log.d(TAG, "📥 최종 시간 조회: $finalTime")
            finalTime
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 조회 실패: ${e.message}", e)
            null
        }
    }

    /**
     * 최종 시간 투표한 사용자 목록 조회
     */
    suspend fun getFinalVotedUsers(groupId: String, date: String): List<String> {
        return try {
            val doc = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)
                .get()
                .await()

            @Suppress("UNCHECKED_CAST")
            val users = doc.get("finalVotedUsers") as? List<String> ?: emptyList()
            Log.d(TAG, "📥 최종 시간 투표한 사용자: $users")
            users
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 투표 사용자 조회 실패: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 모든 멤버가 시간 투표를 완료했는지 확인
     * @param groupId 그룹 ID
     * @param date 날짜
     * @param memberUids 모든 멤버 UID 리스트
     * @return 모든 멤버가 투표했으면 true
     */
    suspend fun checkAllMembersVoted(groupId: String, date: String, memberUids: List<String>): Boolean {
        if (memberUids.isEmpty()) return false
        
        return try {
            val votes = getVotes(groupId, date)
            val votedMembers = mutableSetOf<String>()
            
            votes.values.forEach { voters ->
                votedMembers.addAll(voters)
            }
            
            val allVoted = memberUids.all { it in votedMembers }
            Log.d(TAG, "🔍 모든 멤버 투표 확인 - 전체: $memberUids, 투표한 멤버: $votedMembers, 모두 투표: $allVoted")
            allVoted
        } catch (e: Exception) {
            Log.e(TAG, "❌ 모든 멤버 투표 확인 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 모든 멤버가 최종 시간 투표를 완료했는지 확인
     * @param groupId 그룹 ID
     * @param date 날짜
     * @param memberUids 모든 멤버 UID 리스트
     * @return 모든 멤버가 최종 투표했으면 true
     */
    suspend fun checkAllMembersFinalVoted(groupId: String, date: String, memberUids: List<String>): Boolean {
        if (memberUids.isEmpty()) return false
        
        return try {
            val finalVotedUsers = getFinalVotedUsers(groupId, date)
            val allVoted = memberUids.all { it in finalVotedUsers }
            Log.d(TAG, "🔍 모든 멤버 최종 투표 확인 - 전체: $memberUids, 최종 투표한 멤버: $finalVotedUsers, 모두 투표: $allVoted")
            allVoted
        } catch (e: Exception) {
            Log.e(TAG, "❌ 모든 멤버 최종 투표 확인 실패: ${e.message}", e)
            false
        }
    }

    /**
     * 최종 시간 투표를 실시간으로 관찰
     * @return Flow<List<String>> - 최종 투표한 사용자 UID 리스트
     */
    fun observeFinalVotes(groupId: String, date: String): Flow<List<String>> = callbackFlow {
        val finalVoteRef = db.collection("groups")
            .document(groupId)
            .collection("timeVotes")
            .document(date)

        val listenerRegistration = finalVoteRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "최종 시간 투표 리스너 오류: ${error.message}", error)
                close(error)
                return@addSnapshotListener
            }

            if (snapshot == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }

            @Suppress("UNCHECKED_CAST")
            val users = snapshot.get("finalVotedUsers") as? List<String> ?: emptyList()
            Log.d(TAG, "📊 최종 시간 투표 업데이트: $users")
            trySend(users)
        }

        awaitClose { listenerRegistration.remove() }
    }

    /**
     * 최종 시간 투표 데이터 초기화 (만장일치 실패 시 사용)
     * @param groupId 그룹 ID
     * @param date 날짜
     */
    suspend fun clearFinalVotes(groupId: String, date: String) {
        try {
            val finalVoteRef = db.collection("groups")
                .document(groupId)
                .collection("timeVotes")
                .document(date)

            // finalVotes와 finalVotedUsers 필드 삭제
            finalVoteRef.update(
                mapOf(
                    "finalVotes" to FieldValue.delete(),
                    "finalVotedUsers" to FieldValue.delete()
                )
            ).await()

            Log.d(TAG, "✅ 최종 시간 투표 데이터 초기화 완료: groupId=$groupId, date=$date")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 최종 시간 투표 데이터 초기화 실패: ${e.message}", e)
            throw e
        }
    }
}

