package com.moyeoyo.app.data.model

// 그룹 멤버의 입력 위치 (Firestore inputLocations 문서 구조와 매핑)
// - uid: 멤버 식별자
// - latLng: 위경도 좌표
// - transportMode: 가중중심 계산 시 가중치 반영에 사용
data class InputLocation(
    val uid: String,
    val latLng: LatLngData,
    val transportMode: TransportMode = TransportMode.TRANSIT,
    val nickname: String? = null, // 사용자 닉네임
    val label: String? = null // "집", "회사", "검색결과" 등 표시용
)