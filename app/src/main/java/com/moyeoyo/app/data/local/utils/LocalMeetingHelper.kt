package com.moyeoyo.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐ Firestore 일정 확정 시 Room에 저장
 */
suspend fun saveMeetingToLocal(
    context: Context,
    groupId: String,
    groupName: String,
    meetingAt: Long,
    placeName: String
) {
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).nextMeetingDao()
        dao.upsert(
            NextMeetingEntity(
                groupId = groupId,
                groupName = groupName,
                finalMeetingAt = meetingAt,
                finalMeetingPlace = placeName
            )
        )
    }
}

/**
 * ⭐ 그룹 삭제 시 Room 일정 삭제
 */
suspend fun deleteMeetingFromLocal(
    context: Context,
    groupId: String
) {
    withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).nextMeetingDao()
        dao.deleteByGroupId(groupId)
    }
}
