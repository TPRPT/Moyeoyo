package com.moyeoyo.app.data.model

/**
 * Google Places Nearby Search 결과를 담는 도메인 모델
 */
data class NearbyPlace(
    val placeId: String,
    val name: String,
    val address: String?,
    val latLng: LatLngData,
    val categories: List<String>,
    val rating: Double?,
    val distanceMeters: Double = 0.0
)

