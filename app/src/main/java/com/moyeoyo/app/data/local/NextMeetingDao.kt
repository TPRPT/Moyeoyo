package com.moyeoyo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NextMeetingDao {

    @Query("""
        SELECT * FROM next_meeting
        WHERE finalMeetingAt > :now
        ORDER BY finalMeetingAt ASC
        LIMIT 1
    """)
    suspend fun getNextUpcomingMeeting(now: Long): NextMeetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meeting: NextMeetingEntity)
}
