package com.example.moyeoyo.data.model

// Distance Matrix 결과: 각 사용자(origin) -> 목적지까지의 시간/거리
// - originUid: 멤버 식별자 (입력 순서 유지)
// - durationSec: 예상 소요 시간(초)
// - distanceMeter: 예상 이동 거리(미터)
data class DistanceResult(
    val originUid: String,      // 사용자 UID
    val durationSec: Int,       // 소요 시간(초)
    val distanceMeter: Int      // 거리(m)
)