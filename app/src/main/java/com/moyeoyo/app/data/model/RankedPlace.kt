package com.moyeoyo.app.data.model

/**
 * 사용자가 순위를 정한 장소 모델
 */
data class RankedPlace(
    val place: NearbyPlace,
    val rank: Int, // 1, 2, 3 순위
    val score: Int // 1순위: 3점, 2순위: 2점, 3순위: 1점
) {
    fun toPlaceCandidate(firstRoundScore: Int = 0): PlaceCandidate {
        return PlaceCandidate(
            placeId = place.placeId,
            name = place.name,
            latLng = com.google.firebase.firestore.GeoPoint(place.latLng.lat, place.latLng.lng),
            firstRoundScore = firstRoundScore
        )
    }
}

/**
 * 카테고리 필터
 */
enum class PlaceCategory(val displayName: String, val apiTypes: List<String>) {
    ALL("전체", emptyList()),
    CAFE("카페", listOf("cafe", "bakery")),
    RESTAURANT("식당", listOf("restaurant", "meal_takeaway", "food")),
    BAR("술집", listOf("bar", "night_club")),
    DESSERT("디저트", listOf("cafe", "bakery", "dessert"))
}

