package com.moyeoyo.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.Timestamp

data class Group(
    @DocumentId
    val id: String = "",
    val groupName: String = "",
    val hostUid: String = "",
    val memberUids: List<String> = emptyList(),
    val confirmedPlace: Map<String, Any>? = null,
    val confirmedTime: Timestamp? = null,
    val status: String = "VOTING"
)


