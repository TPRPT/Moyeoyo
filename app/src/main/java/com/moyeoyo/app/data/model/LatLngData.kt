package com.moyeoyo.app.data.model

// 위도, 경도 데이터를 표현하기 위한 간단한 데이터 클래스
// - double 정밀도의 WGS84 좌표
data class LatLngData(
    val lat: Double = 0.0,
    val lng: Double = 0.0
)