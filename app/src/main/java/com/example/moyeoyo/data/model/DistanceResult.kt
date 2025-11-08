package com.example.moyeoyo.data.model

// Distance Matrix 결과: 각 사용자(origin) -> 목적지까지의 시간/거리
// - originUid: 멤버 식별자 (입력 순서 유지)
// - durationSec: 예상 소요 시간(초)
// - distanceMeter: 예상 이동 거리(미터)

data class DistanceResult(
    val uid: String,             // 어느 유저의 결과인지 식별하기 위한 ID
    val durationSeconds: Int,   // 소요 시간 (초 단위)
    val distanceMeters: Int     // 거리 (미터 단위)
)
