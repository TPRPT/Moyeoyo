// app/data/model/User.kt

package com.moyeoyo.app.data.model

import com.google.firebase.firestore.GeoPoint

data class User(
    val uid: String = "",
    val nickname: String = "",
    val profileImageUrl: String? = null,
    val fcmToken: String? = null,
    val defaultLocation: Map<String, Any>? = null, // GeoPoint 및 주소 포함
)

data class LocationData(
    val name: String = "",
    val latLng: GeoPoint? = null,
    val address: String? = null
)