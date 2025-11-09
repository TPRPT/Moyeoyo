package com.moyeoyo.app.data.model

// users/{uid} 문서 모델
data class UserProfile(
    val uid: String = "",
    val nickname: String = "",
    val profileImageUrl: String? = null,
    val fcmToken: String? = null,
    val defaultLocation: UserDefaultLocation? = null
)

// users/{uid}.defaultLocation 서브 객체
data class UserDefaultLocation(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val address: String? = null
)


