package com.moyeoyo.app.data.model

// 자동완성/추천 항목 모델
// - label: UI에 보여질 대표 텍스트(장소명)
// - address: 부가설명(주소/카테고리 등), 없을 수 있음
data class PlaceSuggestion(
    val placeId: String,
    val label: String,       // 화면에 표시할 장소명
    val address: String? = null  // 부가 설명 (주소나 카테고리)
)