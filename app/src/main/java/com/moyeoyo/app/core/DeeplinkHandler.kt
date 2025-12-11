package com.moyeoyo.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.navigation.NavController
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R
import kotlinx.coroutines.launch
import javax.inject.Inject

class DeeplinkHandler constructor(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val auth: FirebaseAuth,
    private val groupRepository: GroupRepository,
    private val friendRepository: FriendRepository,
    private val navController: NavController? = null,
) {
    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    fun handle(intent: Intent?) {
        if (intent == null) return

        // 위젯 딥링크 처리
        val fromWidget = intent.getBooleanExtra("from_widget", false)
        if (fromWidget) {
            handleWidgetDeepLink(intent)
            return
        }

        // 웹 딥링크 처리
        val user = auth.currentUser ?: return
        val data: Uri = intent.data ?: return
        if (data.host != HOSTING_DOMAIN) return

        when {
            data.path?.startsWith("/join") == true -> {
                val groupId = data.getQueryParameter("groupId")
                groupId?.let { handleGroupInvite(it) }
            }

            data.path?.startsWith("/friend") == true -> {
                val inviterUid = data.getQueryParameter("uid")
                inviterUid?.let { handleFriendInvite(it) }
            }
        }
    }

    // 위젯 딥링크 처리
    private fun handleWidgetDeepLink(intent: Intent) {
        val groupId = intent.getStringExtra("widget_group_id") ?: return
        val groupName = intent.getStringExtra("widget_group_name") ?: "모임"

        // Navigation을 사용하여 GroupDetailFragment로 이동
        navController?.let { nav ->
            val bundle = Bundle().apply {
                putString("groupId", groupId)
                putString("groupName", groupName)
            }
            // MainFragment로 먼저 이동 (백스택에 없을 수 있음)
            nav.navigate(R.id.mainFragment)
            // MainFragment에서 GroupDetailFragment로 이동하는 action 사용
            nav.navigate(R.id.action_mainFragment_to_groupDetailFragment, bundle)
        }
    }

    // 그룹 초대 처리
    private fun handleGroupInvite(groupId: String) {
        Toast.makeText(context, "그룹 초대 링크 확인 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val currentUid = auth.currentUser?.uid ?: return@launch
            val group = groupRepository.getGroupById(groupId)

            if (group == null) {
                Toast.makeText(context, "초대된 그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }

            // ⭐ 이미 그룹 멤버인 경우
            if (currentUid in group.memberUids) {
                // 약속 정보 링크(status가 FINALIZED)인 경우 메시지 없이 바로 이동
                if (group.status == "FINALIZED") {
                    // ⭐ FINALIZED 상태: 메시지 없이 바로 그룹 상세 페이지로 이동
                    navController?.let { nav ->
                        try {
                            // ⭐ MainFragment로 먼저 이동하지 않고 직접 GroupDetailFragment로 이동
                            nav.navigate(
                                com.moyeoyo.app.R.id.groupDetailFragment,
                                android.os.Bundle().apply {
                                    putString("groupId", groupId)
                                    putString("groupName", group.groupName)
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("DeeplinkHandler", "네비게이션 실패, Intent 사용: ${e.message}")
                            // fallback to Intent
                            val intent = Intent(context, MainActivity::class.java).apply {
                                putExtra("groupId", groupId)
                                putExtra("groupName", group.groupName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(intent)
                        }
                    } ?: run {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            putExtra("groupId", groupId)
                            putExtra("groupName", group.groupName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(intent)
                    }
                } else {
                    // 일반 그룹 초대 링크인 경우 메시지 표시
                    Toast.makeText(context, "이미 이 그룹의 멤버입니다.", Toast.LENGTH_SHORT).show()
                    
                    // 그룹 상세 페이지로 이동
                    navController?.let { nav ->
                        try {
                            nav.navigate(
                                com.moyeoyo.app.R.id.groupDetailFragment,
                                android.os.Bundle().apply {
                                    putString("groupId", groupId)
                                    putString("groupName", group.groupName)
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("DeeplinkHandler", "네비게이션 실패, Intent 사용: ${e.message}")
                            val intent = Intent(context, MainActivity::class.java).apply {
                                putExtra("groupId", groupId)
                                putExtra("groupName", group.groupName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(intent)
                        }
                    } ?: run {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            putExtra("groupId", groupId)
                            putExtra("groupName", group.groupName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(intent)
                    }
                }
                return@launch
            }

            // ⭐ NEW: 투표 시작 이후면 참여 불가
            if (group.status != "GROUP_CREATED") {
                AlertDialog.Builder(context)
                    .setTitle("참여할 수 없습니다")
                    .setMessage("이미 투표가 시작된 그룹입니다.\n방장에게 문의해주세요.")
                    .setPositiveButton("확인") { _, _ ->
                        val intent = Intent(context, MainActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    .setCancelable(false)
                    .show()
                return@launch
            }

            // ➜ 투표 시작 전이면 정상적으로 참여 가능
            AlertDialog.Builder(context)
                .setTitle("${group.groupName}에 참가할까요?")
                .setMessage("현재 멤버 ${group.memberUids.size}명")
                .setPositiveButton("참가하기") { _, _ ->
                    joinGroup(groupId)
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun joinGroup(groupId: String) {
        Toast.makeText(context, "그룹 참여 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = groupRepository.joinGroup(groupId)

            if (success) {
                Toast.makeText(context, "그룹 참여 성공!", Toast.LENGTH_LONG).show()

                // Navigation 사용
                navController?.let { nav ->
                    val group = groupRepository.getGroupById(groupId)
                    val groupName = group?.groupName ?: ""
                    val action = com.moyeoyo.app.ui.main.MainFragmentDirections.actionMainFragmentToGroupDetailFragment(
                        groupId = groupId,
                        groupName = groupName
                    )
                    nav.navigate(action)
                } ?: run {
                    // NavController가 없으면 기존 방식 사용 (fallback)
                    val intent = Intent(context, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            } else {
                Toast.makeText(context, "그룹 참여 실패", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 친구 초대 처리
    private fun handleFriendInvite(inviterUid: String) {
        lifecycleScope.launch {
            val myUid = auth.currentUser?.uid ?: return@launch

            if (myUid == inviterUid) {
                Toast.makeText(context, "자기 자신과 친구가 될 수 없습니다.", Toast.LENGTH_LONG).show()
                return@launch
            }

            val success = friendRepository.acceptFriendByInvite(inviterUid)

            if (success) {
                Toast.makeText(context, "친구가 추가되었습니다!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "이미 친구이거나 오류가 발생했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
