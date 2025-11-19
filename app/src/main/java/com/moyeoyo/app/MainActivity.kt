package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ⭐ Hilt 로 주입받는 것들(정상)
    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository

    // ⭐ Hilt 로 받지 않는 것 → Activity 에서 직접 생성해야 하는 것
    private lateinit var deeplinkHandler: DeeplinkHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_host)

        // DeeplinkHandler 직접 생성 (중요!)
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

        // FCM 저장
        saveFCMToken()

        // 메인 프래그먼트 로드
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }

        // 딥링크 처리
        deeplinkHandler.handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deeplinkHandler.handle(intent)
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
