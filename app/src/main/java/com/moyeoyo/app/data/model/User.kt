// app/data/model/User.kt

package com.moyeoyo.app.data.model

data class User(
    val uid: String = "",
    val nickname: String = "",
    val profileImageUrl: String? = null,
    val fcmToken: String? = null,
    val homeLocation: Map<String, Any>? = null,
    val workLocation: Map<String, Any>? = null,
    val groups: List<String> = emptyList()
)