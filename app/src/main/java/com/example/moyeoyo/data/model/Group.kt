package com.example.moyeoyo.data.model

import com.google.firebase.Timestamp

// groups/{groupId} 문서 모델
data class Group(
    val groupId: String = "",
    val groupName: String = "",
    val hostUid: String = "",
    val memberUids: List<String> = emptyList(),
    val confirmedPlace: PlaceCandidate? = null,
    val confirmedTime: Timestamp? = null,
    val status: String = "VOTING",
    val createdAt: Timestamp? = null
)


