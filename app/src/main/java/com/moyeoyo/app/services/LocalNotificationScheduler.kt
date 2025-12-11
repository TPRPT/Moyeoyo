package com.moyeoyo.app.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

class LocalNotificationScheduler(private val context: Context) {

    private val TAG = "LocalNotificationScheduler"
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * 리마인더 푸시 알림을 받았을 때, Firebase에서 약속 시간을 확인하고
     * 약속 시간 1시간 전에 로컬 알림을 스케줄링합니다.
     * Firebase 조회 실패 시 RoomDB를 fallback으로 사용합니다.
     */
    fun scheduleReminderNotification(groupId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1차: Firebase에서 직접 조회 (최신 데이터)
                val meetingInfo = getMeetingInfoFromFirebase(groupId)
                
                if (meetingInfo != null) {
                    Log.d(TAG, "Successfully retrieved meeting info from Firebase for group: $groupId")
                    scheduleNotificationForMeeting(
                        groupId,
                        meetingInfo.first,  // groupName
                        meetingInfo.second, // meetingTime
                        meetingInfo.third   // placeName
                    )
                    return@launch
                }
                
                // 2차: Firebase 조회 실패 시 RoomDB에서 조회 (오프라인 대응)
                Log.d(TAG, "Firebase query failed, trying RoomDB fallback for group: $groupId")
                val db = AppDatabase.getInstance(context)
                val dao = db.nextMeetingDao()
                
                val meeting = dao.getByGroupId(groupId)
                
                if (meeting != null) {
                    Log.d(TAG, "Successfully retrieved meeting info from RoomDB for group: $groupId")
                    scheduleNotificationForMeeting(
                        meeting.groupId,
                        meeting.groupName,
                        meeting.finalMeetingAt,
                        meeting.finalMeetingPlace
                    )
                } else {
                    Log.w(TAG, "Group $groupId not found in both Firebase and RoomDB")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling reminder notification: ${e.message}", e)
            }
        }
    }
    
    /**
     * Firebase에서 그룹 정보를 조회하여 약속 정보를 반환합니다.
     * @return Triple(groupName, meetingTime, placeName) 또는 null
     */
    private suspend fun getMeetingInfoFromFirebase(groupId: String): Triple<String, Long, String>? {
        return try {
            val doc = firestore.collection("groups").document(groupId).get().await()
            
            if (!doc.exists()) {
                Log.w(TAG, "Group document does not exist: $groupId")
                return null
            }
            
            val groupName = doc.getString("groupName") ?: "모임"
            val confirmedTime = doc.get("confirmedTime") as? Timestamp
            val confirmedPlace = doc.get("confirmedPlace") as? Map<*, *>
            
            if (confirmedTime == null) {
                Log.w(TAG, "Confirmed time is null for group: $groupId")
                return null
            }
            
            val meetingTime = confirmedTime.toDate().time
            val placeName = when {
                confirmedPlace == null -> "장소 미정"
                confirmedPlace["name"] is String -> confirmedPlace["name"] as String
                else -> "장소 미정"
            }
            
            Triple(groupName, meetingTime, placeName)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get meeting info from Firebase: ${e.message}")
            null
        }
    }
    
    /**
     * 약속 정보를 직접 받아서 알림을 스케줄링합니다.
     */
    fun scheduleNotificationForMeeting(
        groupId: String,
        groupName: String,
        meetingTime: Long,
        placeName: String
    ) {
        val now = System.currentTimeMillis()
        
        // 약속 시간 전날 같은 시간으로 계산 (24시간 전)
        val reminderTime = meetingTime - (24 * 60 * 60 * 1000) // 24시간 = 24 * 60분 * 60초 * 1000ms
        
        // 이미 지난 시간이면 스케줄링하지 않음
        if (reminderTime <= now) {
            Log.w(TAG, "Reminder time has already passed for group: $groupId")
            return
        }
        
        // Intent 생성
        val intent = Intent(context, ReminderNotificationReceiver::class.java).apply {
            putExtra("groupId", groupId)
            putExtra("groupName", groupName)
            putExtra("meetingTime", meetingTime)
            putExtra("placeName", placeName)
        }
        
        // PendingIntent 생성 (groupId를 requestCode로 사용하여 고유성 보장)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // AlarmManager로 알림 스케줄링
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminderTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    reminderTime,
                    pendingIntent
                )
            }
            Log.d(TAG, "Reminder notification scheduled for group: $groupName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm: ${e.message}", e)
        }
    }
    
    /**
     * 예약된 알림을 취소합니다.
     */
    fun cancelReminderNotification(groupId: String) {
        val intent = Intent(context, ReminderNotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            groupId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled reminder notification for group: $groupId")
    }
}

