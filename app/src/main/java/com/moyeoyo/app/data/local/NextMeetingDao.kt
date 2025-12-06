package com.moyeoyo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NextMeetingDao {

    @Query("SELECT * FROM next_meeting ORDER BY finalMeetingAt LIMIT 1")
    suspend fun getNextMeeting(): NextMeetingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meeting: NextMeetingEntity)
}
