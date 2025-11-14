package com.moyeoyo.app.data.model

import com.google.firebase.Timestamp

// groups/{groupId}/timeCandidates/{docId}
data class TimeCandidate(
    val id: String = "",
    val time: Timestamp? = null,
    val voterUids: List<String> = emptyList()
)


