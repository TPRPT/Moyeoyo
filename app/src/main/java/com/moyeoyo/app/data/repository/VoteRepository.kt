package com.moyeoyo.app.data.repository

import android.util.Log
import com.google.firebase.firestore.*
import com.moyeoyo.app.data.model.FinalCandidateData
import com.moyeoyo.app.data.model.Vote
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * 투표(Vote) 데이터의 CRUD를 처리하는 Repository.
 */
class VoteRepository(
    private val db: FirebaseFirestore
) {
    private val groupsCollection = db.collection("groups")
    private val TAG = "VoteRepository"

    /**
     * vote 문서 참조를 가져오는 헬퍼 함수
     */
    private fun getVoteRef(groupId: String) = groupsCollection.document(groupId)
        .collection("vote")
        .document("vote")

    /**
     * 투표 문서 초기화 (그룹 생성 시 호출)
     */
    suspend fun initializeVoteDocument(groupId: String) {
        try {
            getVoteRef(groupId).set(
                Vote(
                    status = "RANKING",
                    rankedUsers = emptyList(),
                    finalCandidates = emptyList(),
                    finalVotedUsers = emptyList()
                )
            ).await()
            Log.d(TAG, "Vote document initialized for group: $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize vote document: ${e.message}", e)
            throw e
        }
    }

    /**
     * 1차 순위 투표 완료한 사용자를 rankedUsers 배열에 추가
     */
    suspend fun addUserToRankedList(groupId: String, uid: String) {
        try {
            getVoteRef(groupId).update("rankedUsers", FieldValue.arrayUnion(uid)).await()
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
    suspend fun checkAllUsersRanked(groupId: String, memberUids: List<String>): Boolean {
        return try {
            val totalMembers = memberUids.size

            val voteSnapshot = getVoteRef(groupId).get().await()
            
            @Suppress("UNCHECKED_CAST")
            val rankedUsers = voteSnapshot.get("rankedUsers") as? List<String> ?: emptyList()
            
            val allRanked = rankedUsers.size == totalMembers && 
                           memberUids.all { it in rankedUsers }
            
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
            getVoteRef(groupId).update("status", status).await()
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

            getVoteRef(groupId).update(
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
            getVoteRef(groupId).update("finalVotedUsers", FieldValue.arrayUnion(uid)).await()
            Log.d(TAG, "User $uid added to finalVotedUsers for group $groupId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add user to finalVotedUsers: ${e.message}", e)
            throw e
        }
    }

    /**
     * 모든 사용자가 최종 투표를 완료했는지 확인
     */
    suspend fun checkAllUsersFinalVoted(groupId: String, memberUids: List<String>): Boolean {
        return try {
            val totalMembers = memberUids.size

            // ⚠️ Source.SERVER 추가하여 서버 데이터만 확인 (캐시 문제 방지)
            val voteSnapshot = getVoteRef(groupId).get(Source.SERVER).await()
            
            @Suppress("UNCHECKED_CAST")
            val finalVotedUsers = voteSnapshot.get("finalVotedUsers") as? List<String> ?: emptyList()
            
            val allVoted = finalVotedUsers.size == totalMembers && 
                          memberUids.all { it in finalVotedUsers }
            
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
            val snapshot = getVoteRef(groupId).get().await()
            
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
    private fun convertSnapshotToVote(snapshot: DocumentSnapshot): Vote {
        val status = snapshot.getString("status") ?: "RANKING"
        @Suppress("UNCHECKED_CAST")
        val rankedUsers = snapshot.get("rankedUsers") as? List<String> ?: emptyList()
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
        val listener = getVoteRef(groupId).addSnapshotListener { snapshot, error ->
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
            getVoteRef(groupId).update(
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
            getVoteRef(groupId).update(
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

