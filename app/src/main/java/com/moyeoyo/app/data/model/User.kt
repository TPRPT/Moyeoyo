package com.moyeoyo.app.data.model

data class User(
    val uid: String = "",
    val nickname: String = "",
    val email: String = "",
    val profileImageUrl: String? = null,
    val fcmToken: String? = null,
    val homeLocation: Map<String, Any>? = null, // GeoPoint 및 주소 포함
    val workLocation: Map<String, Any>? = null, // GeoPoint 및 주소 포함
    val groups: List<String> = emptyList(), // 참여 그룹 목록
    val friends: List<String> = emptyList() // 친구 목록
)

