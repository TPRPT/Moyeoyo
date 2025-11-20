package com.moyeoyo.app.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.Timestamp

data class Group(
    @DocumentId
    val id: String = "",
    val groupName: String = "",
    val hostUid: String = "",
    val memberUids: List<String> = emptyList(),

    // 최종 확정된 장소 & 시간
    val confirmedPlace: Map<String, Any>? = null,
    val confirmedTime: Timestamp? = null,

    // ⭐ 전체 그룹 상태 (이벤트 기반)
    // "GROUP_CREATED" → "TIME_VOTE_REQUIRED" → "TIME_FINALIZING" →
    // "LOCATION_INPUT_REQUIRED" → "LOCATION_DONE" →
    // "PLACE_RANKING" → "FINAL_PLACE_VOTE" → "FINALIZED" → "REMINDER_SCHEDULED"
    val status: String = "GROUP_CREATED",

    // 중간 위치 계산 결과
    val midPoint: GeoPoint? = null
)
