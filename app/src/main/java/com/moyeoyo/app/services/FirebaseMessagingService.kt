package com.moyeoyo.app.services

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R // 리소스 ID 사용

// ⭐ NEW IMPORTS: Repository 및 코루틴 사용을 위해 추가
import com.moyeoyo.app.data.repository.NotificationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.SharedPreferences

class FirebaseMessagingService : FirebaseMessagingService() {

    private val TAG = "FCM_Service"

    // ⭐ NEW: NotificationRepository 인스턴스 생성 (기본 Firebase 인스턴스 사용)
    private val notificationRepository = NotificationRepository(
        db = FirebaseFirestore.getInstance(),
        auth = FirebaseAuth.getInstance()
    )

    /**
     * FCM 토큰이 갱신될 때 호출됩니다.
     * 여기서 갱신된 토큰을 Firestore에 저장합니다.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Refreshed token: $token")
        sendRegistrationToServer(token)
    }

    /**
     * FCM 메시지(푸시 알림)를 수신할 때 호출됩니다.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 푸시 알림 토글 확인
        val sharedPreferences = getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
        val isNotificationEnabled = sharedPreferences.getBoolean("push_notification_enabled", true)
        
        if (!isNotificationEnabled) {
            Log.d(TAG, "Push notifications are disabled by user, skipping notification")
            return
        }

        // 데이터 페이로드 가져오기
        val data = remoteMessage.data

        // 리마인더 알림인 경우 로컬 알림 스케줄링 (notification이 있든 없든 처리)
        val notificationType = data["type"]
        val groupId = data["groupId"]
        
        if (notificationType == "reminder" && groupId != null) {
            Log.d(TAG, "Reminder notification received (data only), scheduling local notification for group: $groupId")
            val scheduler = LocalNotificationScheduler(this)
            scheduler.scheduleReminderNotification(groupId)
            return // 리마인더는 즉시 표시하지 않음
        }

        // ⭐ 포그라운드에서 알림 표시 (notification이 있으면 사용, 없으면 data에서 추출)
        // 백그라운드일 때는 FCM이 자동으로 알림을 표시하므로 onMessageReceived가 호출되지 않음
        val title = remoteMessage.notification?.title ?: data["title"] ?: getString(R.string.app_name)
        val body = remoteMessage.notification?.body ?: data["message"] ?: data["body"]
        
        if (body != null) {
            Log.d(TAG, "포그라운드 알림 표시 - Title: $title, Body: $body")
            Log.d(TAG, "Message data payload: $data")
            
            // ⭐ 포그라운드 알림 표시 (기존 기능)
            sendNotification(title, body, data)
            
            // ⭐ 포그라운드일 때 추가로 다이얼로그도 표시 (헤드업 알림 대체)
            if (isAppInForeground()) {
                Log.d(TAG, "앱이 포그라운드에 있음 - 다이얼로그 추가 표시")
                showNotificationAsActivity(title, body, data)
            }
        } else {
            Log.d(TAG, "알림 본문이 없어 표시하지 않음")
        }
    }

    /**
     * 수신된 FCM 메시지를 Android Notification으로 표시합니다.
     */
    private fun sendNotification(title: String?, messageBody: String?, data: Map<String, String> = emptyMap()) {
        // 리마인더 푸시 알림인 경우 로컬 알림으로 스케줄링
        val notificationType = data["type"]
        val groupId = data["groupId"]
        
        if (notificationType == "reminder" && groupId != null) {
            Log.d(TAG, "Reminder notification received, scheduling local notification for group: $groupId")
            val scheduler = LocalNotificationScheduler(this)
            scheduler.scheduleReminderNotification(groupId)
            // 리마인더는 즉시 표시하지 않고 로컬 알림으로만 처리
            return
        }
        
        // 알림 클릭 시 메인 화면으로 이동하도록 Intent를 설정합니다.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            groupId?.let {
                putExtra("groupId", it)
                Log.d(TAG, "Notification groupId: $it")
            }
            notificationType?.let {
                putExtra("notificationType", it)
                Log.d(TAG, "Notification type: $it")
            }
        }

        // NotificationActivity로 이동하도록 수정할 수도 있습니다.
        // val intent = Intent(this, NotificationActivity::class.java)...

        // 알림 수신 시 MainActivity에 브로드캐스트 전송하여 배지 업데이트 트리거
        val broadcastIntent = Intent("com.moyeoyo.app.NOTIFICATION_RECEIVED")
        sendBroadcast(broadcastIntent)
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getString(R.string.default_notification_channel_id)
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android O 이상: 알림 채널 생성 (헤드업 알림을 위해 IMPORTANCE_HIGH 필수)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ⭐ 항상 채널을 재생성하여 중요도 보장 (사용자가 수동으로 변경했을 수 있음)
            val existingChannel = notificationManager.getNotificationChannel(channelId)
            
            // 기존 채널이 있으면 삭제 후 재생성
            if (existingChannel != null) {
                try {
                    notificationManager.deleteNotificationChannel(channelId)
                    Log.d(TAG, "기존 알림 채널 삭제 완료 (헤드업 알림을 위해 재생성)")
                } catch (e: Exception) {
                    Log.d(TAG, "기존 알림 채널 삭제 실패: ${e.message}")
                }
            }
            
            // ⭐ IMPORTANCE_HIGH로 채널 생성 (헤드업 알림 필수)
            val channel = NotificationChannel(
                channelId,
                getString(R.string.default_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH // ⭐ 높은 중요도로 헤드업 알림 표시
            ).apply {
                description = "모여요 알림 채널"
                enableVibration(true) // 진동 활성화
                enableLights(true) // LED 표시 활성화
                setShowBadge(true) // 배지 표시 활성화
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // 잠금 화면에서도 표시
                // ⭐ 헤드업 알림을 위한 소리 설정
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "알림 채널 생성 완료 (IMPORTANCE_HIGH - 헤드업 알림 활성화)")
        }

        // ⭐ 고유한 알림 ID 생성 (여러 알림이 표시되도록)
        val notificationId = System.currentTimeMillis().toInt()
        
        // ⭐ FullScreenIntent 생성 (헤드업 알림 강제 표시를 위해)
        val fullScreenIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_bell)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX) // ⭐ 최대 우선순위로 헤드업 알림 강제 표시 (Android 9 이하)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // ⭐ 소리, 진동 등 기본 설정 사용
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // ⭐ 잠금 화면에서도 표시
            .setCategory(NotificationCompat.CATEGORY_CALL) // ⭐ CALL 카테고리로 헤드업 알림 강제 표시 (MESSAGE보다 확실함)
            .setShowWhen(true) // ⭐ 시간 표시
            .setWhen(System.currentTimeMillis()) // ⭐ 현재 시간 설정
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageBody)) // ⭐ 큰 텍스트 스타일
            .setOngoing(false) // ⭐ 진행 중 알림이 아니므로 false
        
        // ⭐ Android 10 이상: FullScreenIntent 사용 (헤드업 알림 강제 표시)
        // Android 11 이상에서는 USE_FULL_SCREEN_INTENT 권한 필요 (매니페스트에 이미 선언됨)
        // 매니페스트에 권한이 있으면 자동으로 허용되므로 바로 사용 가능
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            notificationBuilder.setFullScreenIntent(fullScreenIntent, true)
            Log.d(TAG, "FullScreenIntent 설정 완료 (헤드업 알림 활성화)")
        }

        notificationManager.notify(notificationId, notificationBuilder.build())
        Log.d(TAG, "포그라운드 알림 표시 완료 - 헤드업 알림 활성화 (PRIORITY_MAX, IMPORTANCE_HIGH, FullScreenIntent)")
    }

    /**
     * 앱이 포그라운드에 있는지 확인
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses ?: return false
        
        val packageName = packageName
        for (processInfo in runningAppProcesses) {
            if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                processInfo.processName == packageName) {
                return true
            }
        }
        return false
    }
    
    /**
     * 포그라운드일 때 Activity를 직접 띄워서 헤드업 알림 효과
     */
    private fun showNotificationAsActivity(title: String?, messageBody: String?, data: Map<String, String>) {
        val notificationType = data["type"]
        val groupId = data["groupId"]
        
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("showNotificationDialog", true)
            putExtra("notificationTitle", title)
            putExtra("notificationMessage", messageBody)
            groupId?.let { putExtra("groupId", it) }
            notificationType?.let { putExtra("notificationType", it) }
        }
        
        startActivity(intent)
        Log.d(TAG, "포그라운드 알림 Activity 띄우기 완료")
    }

    /**
     * ⭐ MODIFIED: FCM 토큰을 Firestore 사용자 문서에 저장합니다.
     * Coroutine Scope를 사용하여 suspend 함수를 호출합니다.
     */
    private fun sendRegistrationToServer(token: String?) {
        token?.let {
            // Service 내에서 CoroutineScope를 생성하고 IO 디스패처로 Firestore 작업을 백그라운드에서 실행합니다.
            CoroutineScope(Dispatchers.IO).launch {
                val success = notificationRepository.updateFcmToken(it)
                if (success) {
                    Log.d(TAG, "FCM Token successfully saved to Firestore.")
                } else {
                    Log.e(TAG, "Failed to save FCM Token to Firestore.")
                }
            }
        }
    }
}