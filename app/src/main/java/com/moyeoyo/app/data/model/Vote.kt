package com.moyeoyo.app.data.model

import com.google.firebase.firestore.GeoPoint

/**
 * 투표 상태를 관리하는 모델 (groups/{groupId}/placeVote/placeVote 문서)
 * Single Source of Truth - 모든 사용자가 이 문서를 보고 투표 상태를 확인
 */
data class Vote(
    val status: String = "RANKING", // "RANKING", "FINAL_VOTING", "FINISHED"
    val rankedUsers: List<String> = emptyList(), // 1차 순위 투표를 완료한 사용자 UID 목록
    val finalCandidates: List<FinalCandidateData> = emptyList(), // 최종 후보 3개
    val finalVotedUsers: List<String> = emptyList(), // 최종 투표를 완료한 사용자 UID 목록
    val winningPlaceId: String? = null, // 승리한 장소의 placeId (FINISHED 상태일 때)
    val winningPlaceName: String? = null // 승리한 장소의 이름 (FINISHED 상태일 때)
)

/**
 * Firestore에 저장될 최종 후보 데이터 (FinalCandidate를 직렬화한 형태)
 */
data class FinalCandidateData(
    val placeId: String,
    val name: String,
    val latLng: GeoPoint,
    val totalScore: Int,
    val categories: List<String> = emptyList(),
    val rating: Double? = null,
    val address: String? = null
)

