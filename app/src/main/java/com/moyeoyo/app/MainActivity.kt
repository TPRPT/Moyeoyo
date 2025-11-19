package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.main.MainFragment
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.core.deeplink.DeeplinkHandler   // ⭐ 딥링크 핸들러 추가

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var deeplinkHandler: DeeplinkHandler
    private val notificationRepository = NotificationRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_host)

        auth = FirebaseAuth.getInstance()

        // DeeplinkHandler 초기화
        deeplinkHandler = DeeplinkHandler(this, lifecycleScope)

        // 로그인 체크
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // FCM Token 저장
        saveFCMToken()

        // 메인 프래그먼트 로드
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }

        // 딥링크 처리 위임
        deeplinkHandler.handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deeplinkHandler.handle(intent)   // ⭐ 딥링크 재처리
    }

    // ======================================================
    // FCM Token 저장
    // ======================================================
    private fun saveFCMToken() {
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                notificationRepository.updateFcmToken(token)
                Log.d("FCM", "Token saved")
            } catch (e: Exception) {
                Log.e("FCM", "FCM Token 저장 실패 ${e.message}")
            }
        }
    }
}
