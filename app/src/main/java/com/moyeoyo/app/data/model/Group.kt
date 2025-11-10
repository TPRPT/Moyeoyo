package com.moyeoyo.app.data.model

data class Group(
    // Document ID: Firestore가 자동으로 생성 (필드에 포함 X)
    val groupName: String = "",
    val hostUid: String = "",
    val memberUids: List<String> = emptyList(),
    // DB 구조 가이드의 필드들을 포함
    val confirmedPlace: Map<String, Any>? = null,
    val confirmedTime: com.google.firebase.Timestamp? = null,
    val status: String = "VOTING" // 초기 상태
)