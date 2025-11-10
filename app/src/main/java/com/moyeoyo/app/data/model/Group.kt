package com.moyeoyo.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.Timestamp

data class Group(
    // ⭐ Firestore 문서 ID를 자동으로 담는 필드 추가 (필수)
    @DocumentId
    val id: String = "",

    val groupName: String = "",
    val hostUid: String = "",
    val memberUids: List<String> = emptyList(),
    // DB 구조 가이드의 필드들을 포함
    val confirmedPlace: Map<String, Any>? = null,
    val confirmedTime: Timestamp? = null,
    val status: String = "VOTING" // 초기 상태
)