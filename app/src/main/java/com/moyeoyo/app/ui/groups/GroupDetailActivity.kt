// com.moyeoyo.app.ui.groups/GroupDetailActivity.kt

package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    private val groupRepository = GroupRepository()
    private val friendRepository = FriendRepository()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var currentUid: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⭐ View Binding 초기화
        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 그룹 ID와 이름 가져오기 및 필수 체크
        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Unknown Group"
        currentUid = auth.currentUser?.uid ?: return finish() // 현재 사용자 UID 초기화

        binding.groupNameText.text = groupName

        // 1. 친구 초대 버튼 리스너
        binding.btnInviteFriends.setOnClickListener {
            navigateToInviteScreen()
        }

        // 2. 그룹 삭제 버튼 리스너 (방장 권한)
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        // 3. 그룹 나가기 버튼 리스너 (일반 멤버 권한)
        binding.btnLeaveGroup.setOnClickListener {
            showLeaveConfirmationDialog()
        }
    }

    // Activity가 재개될 때마다 목록을 새로고침 (데이터 동기화)
    override fun onResume() {
        super.onResume()
        loadGroupData()
    }

    /**
     * 그룹 상세 데이터를 로드하고 UI를 업데이트하며 권한에 따라 버튼을 표시합니다.
     */
    private fun loadGroupData() {
        binding.memberCountText.text = "로딩 중..."
        binding.memberListContainer.removeAllViews() // 멤버 목록 컨테이너 초기화

        lifecycleScope.launch {
            val group = groupRepository.getGroupDetail(groupId)

            if (group != null) {
                val memberCount = group.memberUids.size
                binding.memberCountText.text = "$memberCount 명"
                val isHost = group.hostUid == currentUid

                // 1. 버튼 가시성 설정 (권한 분기)
                binding.btnDeleteGroup.visibility = if (isHost) View.VISIBLE else View.GONE
                binding.btnLeaveGroup.visibility = if (!isHost) View.VISIBLE else View.GONE

                // 2. 팀원 목록 표시
                displayMemberList(group.memberUids, group.hostUid, isHost)

            } else {
                binding.memberCountText.text = "오류 발생"
                Toast.makeText(this@GroupDetailActivity, "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * 팀원 목록을 표시하고 방장에게 강퇴 버튼을 제공합니다.
     */
    private fun displayMemberList(memberUids: List<String>, hostUid: String, isHost: Boolean) {
        val container = binding.memberListContainer
        val inflater = LayoutInflater.from(this)

        lifecycleScope.launch {
            // 모든 멤버 UID에 대해 닉네임을 비동기로 조회
            val memberDetails = memberUids.map { uid ->
                async {
                    Pair(uid, friendRepository.getUserNickname(uid) ?: uid.take(8))
                }
            }.awaitAll()

            // 목록 컨테이너에 뷰 추가
            memberDetails.forEach { (uid, nickname) ->
                val isCurrentMemberHost = uid == hostUid

                // item_member_list 레이아웃이 있다고 가정하고 인플레이트
                // (이 레이아웃은 member_name, member_status, btn_kick_member를 포함해야 합니다.)
                val memberView = inflater.inflate(com.moyeoyo.app.R.layout.item_member_list, container, false)

                val nameText = memberView.findViewById<TextView>(com.moyeoyo.app.R.id.member_name)
                val statusText = memberView.findViewById<TextView>(com.moyeoyo.app.R.id.member_status)
                val kickButton = memberView.findViewById<Button>(com.moyeoyo.app.R.id.btn_kick_member)

                nameText.text = nickname
                statusText.text = if (isCurrentMemberHost) "(방장)" else ""

                // 강퇴 버튼 로직 (방장이며, 자기 자신이 아닐 때만 버튼 표시)
                if (isHost && uid != currentUid) {
                    kickButton.visibility = View.VISIBLE
                    kickButton.setOnClickListener {
                        showKickConfirmationDialog(uid, nickname)
                    }
                } else {
                    kickButton.visibility = View.GONE
                }

                container.addView(memberView)
            }
        }
    }

    /**
     * GroupInviteActivity로 이동하여 초대 링크를 다시 생성/공유합니다.
     */
    private fun navigateToInviteScreen() {
        val intent = Intent(this, GroupInviteActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("GROUP_NAME", groupName)
        }
        startActivity(intent)
    }

    /**
     * 그룹 삭제 전에 사용자에게 최종 확인을 요청하는 대화상자를 표시합니다.
     */
    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 삭제 확인")
            .setMessage("정말로 그룹 '$groupName'을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * GroupRepository를 통해 그룹 삭제를 요청하고 메인 화면으로 복귀합니다.
     */
    private fun deleteGroup() {
        lifecycleScope.launch {
            val success = groupRepository.deleteGroup(groupId)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "'$groupName' 그룹이 삭제되었습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 삭제에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 멤버 강퇴 확인 다이얼로그
     */
    private fun showKickConfirmationDialog(memberUid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("멤버 강퇴 확인")
            .setMessage("${nickname} 님을 그룹에서 강퇴하시겠습니까?")
            .setPositiveButton("강퇴") { _, _ ->
                kickMember(memberUid, nickname)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 멤버 강퇴 실행
     */
    private fun kickMember(memberUid: String, nickname: String) {
        lifecycleScope.launch {
            val success = groupRepository.removeMember(groupId, memberUid)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "${nickname} 님을 강퇴했습니다.", Toast.LENGTH_LONG).show()
                loadGroupData() // 목록 갱신
            } else {
                Toast.makeText(this@GroupDetailActivity, "강퇴에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 그룹 나가기 확인 다이얼로그
     */
    private fun showLeaveConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 나가기 확인")
            .setMessage("정말로 그룹 '$groupName'을 나가시겠습니까? 다시 참여하려면 초대가 필요합니다.")
            .setPositiveButton("나가기") { _, _ ->
                leaveGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 그룹 나가기 실행 (GroupRepository의 leaveGroup 사용)
     */
    private fun leaveGroup() {
        lifecycleScope.launch {
            val success = groupRepository.leaveGroup(groupId)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "'$groupName' 그룹을 나왔습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 나가기에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }
}