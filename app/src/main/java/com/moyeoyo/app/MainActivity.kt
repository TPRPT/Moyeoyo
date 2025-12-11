package com.moyeoyo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.NavController
import com.google.android.libraries.places.api.Places
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
                // ⭐ 알림 배지 업데이트는 MainFragment의 실시간 리스너(observeNotificationBadge)에서 자동으로 처리됨
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
        
        // Google Places SDK 초기화 (앱 시작 시 1회)
        if (!Places.isInitialized()) {
            Places.initialize(this, getString(R.string.google_maps_key))
        }
        
        // 상태바 설정: 앱 배경색과 동일하게 설정
        window.statusBarColor = getColor(R.color.white)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = flags
        }
        
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
        
        // 푸시 알림 클릭 처리
        handleNotificationIntent(intent)
        
        // ⭐ 포그라운드 알림 다이얼로그 표시
        if (intent.getBooleanExtra("showNotificationDialog", false)) {
            val title = intent.getStringExtra("notificationTitle")
            val message = intent.getStringExtra("notificationMessage")
            val groupId = intent.getStringExtra("groupId")
            val notificationType = intent.getStringExtra("notificationType")
            
            if (title != null && message != null) {
                showNotificationDialog(title, message, groupId, notificationType)
            }
        }
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
        // ⭐ 알림 배지 업데이트는 MainFragment의 실시간 리스너(observeNotificationBadge)에서 자동으로 처리됨
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deeplinkHandler.handle(intent)
        
        // 푸시 알림 클릭 처리
        handleNotificationIntent(intent)
        
        // ⭐ 포그라운드 알림 다이얼로그 표시
        if (intent.getBooleanExtra("showNotificationDialog", false)) {
            val title = intent.getStringExtra("notificationTitle")
            val message = intent.getStringExtra("notificationMessage")
            val groupId = intent.getStringExtra("groupId")
            val notificationType = intent.getStringExtra("notificationType")
            
            if (title != null && message != null) {
                showNotificationDialog(title, message, groupId, notificationType)
            }
        }
        
        // ⭐ 알림 배지 업데이트는 MainFragment의 실시간 리스너(observeNotificationBadge)에서 자동으로 처리됨
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
                        
                        // 그룹 정보 가져오기
                        val group = groupRepository.getGroupById(groupId)
                        
                        // ⭐ 그룹이 존재하지 않으면 메시지만 표시하고 네비게이션하지 않음
                        if (group == null) {
                            Log.w("MainActivity", "그룹이 존재하지 않습니다. groupId: $groupId")
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                "존재하지 않는 그룹입니다.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                        
                        // 알림 자동 읽음 처리
                        notificationRepository.markNotificationAsReadByTypeAndGroup(notificationType, groupId)
                        
                        val groupName = group.groupName
                        
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

    /**
     * 포그라운드 알림 다이얼로그 표시 (헤드업 알림 대체)
     */
    private fun showNotificationDialog(title: String, message: String, groupId: String?, notificationType: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                // 알림 클릭 처리
                if (groupId != null && notificationType != null) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("groupId", groupId)
                        putExtra("notificationType", notificationType)
                    }
                    handleNotificationIntent(intent)
                }
            }
            .setCancelable(true)
            .setOnCancelListener {
                // 다이얼로그가 취소되면 그냥 닫기
            }
            .show()
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
