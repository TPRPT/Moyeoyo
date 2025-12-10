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
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.moyeoyo.app.core.DeeplinkHandler
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.ui.main.MainFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.MeetingRepository
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var auth: FirebaseAuth
    @Inject lateinit var notificationRepository: NotificationRepository
    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository

    private lateinit var deeplinkHandler: DeeplinkHandler
    private var navController: NavController? = null
    
    // 알림 수신 브로드캐스트 리시버
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.moyeoyo.app.NOTIFICATION_RECEIVED") {
                updateNotificationBadge()
            }
        }
    }

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

        // Navigation 설정 (먼저 설정하여 로그인 체크 시 Navigation 사용 가능)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        navController = navHostFragment?.navController

        // 알림 수신 브로드캐스트 리시버 등록 (로그인 상태와 관계없이 등록)
        try {
            registerReceiver(notificationReceiver, IntentFilter("com.moyeoyo.app.NOTIFICATION_RECEIVED"))
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to register receiver: ${e.message}")
        }

        // 로그인 체크
        if (auth.currentUser == null) {
            // LoginFragment로 이동
            navController?.navigate(R.id.loginFragment)
            return
        }

        // Firestore → Room 동기화
        val userId = auth.currentUser!!.uid
        val meetingRepo = MeetingRepository(this)

        meetingRepo.syncFromFirestore(userId)       // 앱 켤 때 1회 실행
        meetingRepo.observeGroupsRealtime(userId)   // 앱 살아 있는 동안 실시간 반영

        // FCM 토큰 저장
        saveFCMToken()

        // DeeplinkHandler에 NavController 전달
        deeplinkHandler = DeeplinkHandler(
            context = this,
            lifecycleScope = lifecycleScope,
            auth = auth,
            groupRepository = groupRepository,
            friendRepository = friendRepository,
            navController = navController
        )

        // 딥링크 처리
        deeplinkHandler.handle(intent)
        DeepLinkHandler(navController).handle(intent)
        
        // 푸시 알림 클릭 처리
        handleNotificationIntent(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: Exception) {
            // 리시버가 등록되지 않았을 수 있음
        }
    }

    override fun onResume() {
        super.onResume()
        // MainFragment가 활성화되어 있으면 알림 배지 업데이트
        // Fragment가 준비될 때까지 약간의 지연을 두고 호출
        if (auth.currentUser != null) {
            window.decorView.post {
                updateNotificationBadge()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deeplinkHandler.handle(intent)
        DeepLinkHandler(navController).handle(intent)
        
        // 푸시 알림 클릭 처리
        handleNotificationIntent(intent)
        
        // 알림 배지 업데이트
        updateNotificationBadge()
    }
    
    /**
     * MainFragment의 알림 배지 업데이트
     */
    private fun updateNotificationBadge() {
        try {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
            
            if (currentFragment is MainFragment && currentFragment.isAdded) {
                // MainFragment의 updateNotificationBadge를 직접 호출
                currentFragment.updateNotificationBadge()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to update notification badge: ${e.message}")
        }
    }
    
    /**
     * 푸시 알림 클릭 시 처리
     */
    private fun handleNotificationIntent(intent: Intent) {
        val notificationType = intent.getStringExtra("notificationType") 
            ?: intent.getStringExtra("notification_type")
        val groupId = intent.getStringExtra("groupId")
        
        lifecycleScope.launch {
            try {
                when {
                    // 친구 요청 알림 → 인앱 알림창으로 이동
                    notificationType == "friend_request" -> {
                        Log.d("MainActivity", "Friend request notification clicked: navigating to notification fragment")
                        
                        // MainFragment로 먼저 이동
                        navController?.navigate(R.id.mainFragment)
                        
                        // NotificationFragment로 이동
                        navController?.navigate(R.id.notificationFragment)
                    }
                    
                    // 그룹 관련 알림 → 그룹 상세 화면으로 이동 + 자동 읽음 처리
                    groupId != null && notificationType != null && notificationType in listOf("time_vote", "location_input", "final_vote", "finalized", "ranking", "reminder") -> {
                        Log.d("MainActivity", "Group notification clicked: navigating to group detail - groupId: $groupId, type: $notificationType")
                        
                        // 알림 자동 읽음 처리
                        notificationRepository.markNotificationAsReadByTypeAndGroup(notificationType, groupId)
                        
                        // 그룹 정보 가져오기
                        val group = groupRepository.getGroupById(groupId)
                        val groupName = group?.groupName ?: "모임"
                        
                        // MainFragment로 먼저 이동 (백스택에 없을 수 있음)
                        navController?.navigate(R.id.mainFragment)
                        
                        // GroupDetailFragment로 이동
                        navController?.navigate(
                            R.id.action_mainFragment_to_groupDetailFragment,
                            Bundle().apply {
                                putString("groupId", groupId)
                                putString("groupName", groupName)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to handle notification intent: ${e.message}", e)
            }
        }
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
