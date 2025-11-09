package com.moyeoyo.app.data.model

// 이동 수단 - 가중중심 계산 시 가중치 계산에 사용
// 가중치 순서: DRIVE(운전) > SUBWAY(지하철) > BUS(버스) > WALK(도보)
enum class TransportMode { DRIVE, SUBWAY, BUS, WALK }