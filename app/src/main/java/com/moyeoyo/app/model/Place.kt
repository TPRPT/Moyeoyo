package com.moyeoyo.app.model

data class Place(
    val name: String,
    val category: String,
    val rating: Double,
    val distanceKm: Double,
    val walkingTime: String,
    val transitTime: String,
    val drivingTime: String,
    var likeCount: Int,
    var isLiked: Boolean = false,
    var memberCount: Int = 8
)
