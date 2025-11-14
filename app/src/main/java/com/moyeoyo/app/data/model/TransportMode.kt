package com.moyeoyo.app.data.model

// 이동 수단 - 가중중심 계산 시 가중치 계산에 사용
// 가중치 순서: WALK(도보) > TRANSIT(대중교통) > DRIVE(자동차)
enum class TransportMode { WALK, TRANSIT, DRIVE }