package com.moyeoyo.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ReminderNotificationReceiver : BroadcastReceiver() {

    private val TAG = "ReminderNotificationReceiver"
    private val CHANNEL_ID = "reminder_notification_channel"
    private val CHANNEL_NAME = "약속 리마인더"

    override fun onReceive(context: Context, intent: Intent) {
        val groupId = intent.getStringExtra("groupId") ?: run {
            Log.e(TAG, "groupId is null")
            return
        }
        val groupName = intent.getStringExtra("groupName") ?: "모임"
        val meetingTime = intent.getLongExtra("meetingTime", 0L)
        val placeName = intent.getStringExtra("placeName") ?: "장소 미정"
        
        if (meetingTime == 0L) {
            Log.e(TAG, "Invalid meeting time")
            return
        }
        
        // 약속 시간 포맷팅
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeText = timeFormat.format(Date(meetingTime))
        
        // 알림 메시지 생성
        val title = "🔔 내일 약속이 있어요!"
        val message = "'$groupName' 약속이 내일 있어요!\n$placeName / $timeText"
        
        // 알림 채널 생성 (Android O 이상)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "약속 전날 알림"
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // 알림 클릭 시 그룹 상세 화면으로 이동
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("groupId", groupId)
            putExtra("notificationType", "reminder")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            groupId.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 알림 빌더
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        // 알림 표시
        notificationManager.notify(groupId.hashCode(), notification)
        
        // Firestore에 알림 문서 생성 (인앱 알림창 표시용)
        val notificationRepository = NotificationRepository()
        CoroutineScope(Dispatchers.IO).launch {
            notificationRepository.createLocalNotification(
                title = title,
                message = message,
                type = "reminder",
                groupId = groupId
            )
        }
        
        Log.d(TAG, "Reminder notification displayed for group: $groupName")
    }
}

