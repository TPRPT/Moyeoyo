package com.moyeoyo.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.ui.main.MainFragment
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// NEW
import com.moyeoyo.app.ui.notification.NotificationActivity
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import com.moyeoyo.app.data.repository.GroupRepository
import com.google.firebase.firestore.FirebaseFirestore

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"
    private val notificationRepository = NotificationRepository()

    // ⬇⬇ 수정 완료된 부분 (필수) ⬇⬇
    private val groupRepository = GroupRepository(
        db = FirebaseFirestore.getInstance(),
        auth = FirebaseAuth.getInstance()
    )
    // ⬆⬆ 여기로 GroupRepository 초기화 끝 ⬆⬆

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fragment를 담을 레이아웃
        setContentView(R.layout.activity_main_host)

        auth = FirebaseAuth.getInstance()

<<<<<<< HEAD
        // === 1. 로그인 체크 ===
=======

>>>>>>> 212a9e87 (Chore: develop 머지 및 충돌 해결)
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // === 2. FCM 토큰 저장 ===
        saveFCMToken()

        // === 3. MainFragment 로드 ===
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
        }

        // === 4. 딥링크 처리 ===
        handleIntent(intent)
    }

    /**
     * FCM 토큰 저장
     */
    private fun saveFCMToken() {
        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (token != null) {
                    val success = notificationRepository.updateFcmToken(token)
                    if (success) {
                        Log.d("FCM_TOKEN", "FCM Token saved successfully.")
                    } else {
                        Log.e("FCM_TOKEN", "Failed to save FCM Token to Firestore.")
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM_TOKEN", "Error fetching or saving FCM token: ${e.message}", e)
            }
        }
    }

    // =====================================================================
    // 딥링크 처리 영역
    // =====================================================================

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val currentUser = auth.currentUser ?: return

        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return

            if (uri.host == HOSTING_DOMAIN && uri.path?.startsWith("/join") == true) {
                val groupId = uri.getQueryParameter("groupId")

                if (groupId != null) {
                    showJoinConfirmation(groupId)
                } else {
                    Log.e("MAIN", "Deep link missing groupId parameter.")
                }
            }
        }
    }

    /**
     * 그룹 참가 확인 다이얼로그
     */
    private fun showJoinConfirmation(groupId: String) {
        android.widget.Toast.makeText(this, "그룹 초대 링크 확인 중...", android.widget.Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("${group.groupName}에 참가할까요?")
                    .setMessage("그룹 '${group.groupName}'에 참여하여 모임 활동을 시작할 수 있습니다. (현재 멤버 ${group.memberUids.size}명)")
                    .setPositiveButton("참가하기") { _, _ ->
                        joinGroup(groupId)
                    }
                    .setNegativeButton("취소하기", null)
                    .show()
            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "초대된 그룹 정보를 찾을 수 없거나 삭제된 그룹입니다.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 그룹 가입 처리
     */
    private fun joinGroup(groupId: String) {
        android.widget.Toast.makeText(this, "그룹 참여 중...", android.widget.Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = groupRepository.joinGroup(groupId)

            if (success) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "참여 성공! 그룹 상세 페이지로 이동합니다.",
                    android.widget.Toast.LENGTH_LONG
                ).show()

                val intent = Intent(this@MainActivity, GroupDetailActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                }
                startActivity(intent)

            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "그룹 참여 실패 (이미 참여했거나 오류 발생)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
