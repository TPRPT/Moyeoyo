package com.moyeoyo.app.data.model

import com.google.firebase.firestore.GeoPoint

// 추천/투표 대상 장소 후보 모델 (groups/{groupId}/placeCandidates/{autoId})
// - 문서 ID는 자동 생성, 실제 Google Place ID는 placeId 필드에 저장
data class PlaceCandidate(
    val id: String = "",
    val placeId: String = "",
    val name: String = "",
    val latLng: GeoPoint? = null,
    val voterUids: List<String> = emptyList()
)