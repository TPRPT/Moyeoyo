package com.moyeoyo.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import kotlinx.coroutines.launch
import javax.inject.Inject

class DeeplinkHandler constructor(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val auth: FirebaseAuth,
    private val groupRepository: GroupRepository,
    private val friendRepository: FriendRepository,
) {
    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    fun handle(intent: Intent?) {
        val user = auth.currentUser ?: return
        val data: Uri = intent?.data ?: return
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

    // 그룹 초대 처리
    private fun handleGroupInvite(groupId: String) {
        Toast.makeText(context, "그룹 초대 링크 확인 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                AlertDialog.Builder(context)
                    .setTitle("${group.groupName}에 참가할까요?")
                    .setMessage("현재 멤버 ${group.memberUids.size}명")
                    .setPositiveButton("참가하기") { _, _ ->
                        joinGroup(groupId)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            } else {
                Toast.makeText(context, "초대된 그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun joinGroup(groupId: String) {
        Toast.makeText(context, "그룹 참여 중...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val success = groupRepository.joinGroup(groupId)

            if (success) {
                Toast.makeText(context, "그룹 참여 성공!", Toast.LENGTH_LONG).show()

                val intent = Intent(context, GroupDetailActivity::class.java)
                intent.putExtra("GROUP_ID", groupId)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
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