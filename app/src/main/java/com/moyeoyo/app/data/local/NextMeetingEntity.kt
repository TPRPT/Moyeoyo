package com.moyeoyo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "next_meeting")
data class NextMeetingEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val finalMeetingAt: Long,
    val finalMeetingPlace: String
)
