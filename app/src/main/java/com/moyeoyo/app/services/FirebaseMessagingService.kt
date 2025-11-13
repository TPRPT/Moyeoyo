package com.moyeoyo.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.moyeoyo.app.MainActivity // 알림 클릭 시 이동할 Activity
import com.moyeoyo.app.R // 리소스 ID 사용

// ⭐ NEW IMPORTS: Repository 및 코루틴 사용을 위해 추가
import com.moyeoyo.app.data.repository.NotificationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // 💡 알림 페이로드가 있는 경우 (앱이 백그라운드일 때 기본 처리됨)
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            // 포그라운드에서 수신 시 알림을 직접 띄웁니다.
            sendNotification(it.title, it.body)
        }

        // 💡 데이터 페이로드가 있는 경우 (친구 요청 UID 등)
        remoteMessage.data.isNotEmpty().let {
            Log.d(TAG, "Message data payload: " + remoteMessage.data)
            // 여기에서 알림 데이터를 처리하여 특정 화면으로 이동시킬 수 있습니다.
        }
    }

    /**
     * 수신된 FCM 메시지를 Android Notification으로 표시합니다.
     */
    private fun sendNotification(title: String?, messageBody: String?) {
        // 알림 클릭 시 메인 화면으로 이동하도록 Intent를 설정합니다.
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // NotificationActivity로 이동하도록 수정할 수도 있습니다.
        // val intent = Intent(this, NotificationActivity::class.java)...

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = getString(R.string.default_notification_channel_id)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification_bell) // ⭐ 아이콘 리소스 확인 필요
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(messageBody)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Android O 이상: 알림 채널 생성
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.default_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(0, notificationBuilder.build())
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