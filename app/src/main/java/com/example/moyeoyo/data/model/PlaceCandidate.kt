package com.example.moyeoyo.data.model

// 추천/투표 대상 장소 후보 모델 (후속 확장용)
// - rating, voterUids 등 투표/평가 기능과 연동 가능
data class PlaceCandidate(
    val id: String,
    val placeId: String,
    val name: String,
    val latLng: LatLngData,
    val rating: Double? = null,
    val voterUids: List<String> = emptyList()
)