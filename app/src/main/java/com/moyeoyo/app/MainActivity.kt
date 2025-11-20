package com.moyeoyo.app

import android.Manifest
import com.moyeoyo.app.DeepLinkHandler
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.moyeoyo.app.core.DeeplinkHandler
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.main.MainFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.moyeoyo.app.R
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository

    private lateinit var deeplinkHandler: DeeplinkHandler

    // ---- 🔔 알림 권한 요청 Launcher ----
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("NOTIFICATION", "알림 권한 허용됨")
            } else {
                Log.d("NOTIFICATION", "알림 권한 거부됨")
            }
        }

    // ---- 🔔 알림 권한 요청 함수 ----
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(permission)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_host)

        // 🔔 알림 권한 요청
        askNotificationPermission()

        // DeeplinkHandler
        deeplinkHandler = DeeplinkHandler(
            context = this,
            lifecycleScope = lifecycleScope,
            auth = auth,
            groupRepository = groupRepository,
            friendRepository = friendRepository
        )

        // 로그인 체크
        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // FCM 토큰 저장
        saveFCMToken()

        // 메인 프래그먼트 로드
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }

        // 딥링크 처리
        deeplinkHandler.handle(intent)
        DeepLinkHandler(this).handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deeplinkHandler.handle(intent)
        DeepLinkHandler(this).handle(intent)
    }

    private fun saveFCMToken() {
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                notificationRepository.updateFcmToken(token)
                Log.d("FCM", "Token saved")
            } catch (e: Exception) {
                Log.e("FCM", "Token save fail: ${e.message}")
            }
        }
    }
}
