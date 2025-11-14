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
import com.google.firebase.Timestamp // ⭐ NEW: Timestamp import
import com.moyeoyo.app.data.model.Group // ⭐ NEW: Group data model import
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.ui.vote.ConfirmActivity
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.map.MidpointActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat // ⭐ NEW: 시간 포맷팅을 위한 import
import java.util.Locale
import java.util.TimeZone

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

        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Unknown Group"
        currentUid = auth.currentUser?.uid ?: return finish()

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

        // 중간값 계산 버튼 리스너
        binding.btnCalculateMidpoint.setOnClickListener {
            // MidpointActivity로 이동하여 중간값 계산 화면 표시
            val intent = Intent(this, MidpointActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
        }

        // 일정 확정 버튼 리스너
        binding.btnConfirmScheduleNow.setOnClickListener {
            // ConfirmActivity로 이동하여 확정 처리를 위임
            val intent = Intent(this, ConfirmActivity::class.java).apply {
                putExtra("GROUP_ID", groupId)
                putExtra("GROUP_NAME", groupName)
            }
            startActivity(intent)
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
        binding.memberListContainer.removeAllViews()
        // ⭐ 추가: 데이터 로딩 전에 확정 일정 섹션을 숨김 (깜빡임 방지)
        binding.confirmedScheduleSection.visibility = View.GONE

        lifecycleScope.launch {
            // GroupRepository에서 Group 객체를 가져옵니다.
            val group = groupRepository.getGroupDetail(groupId)

            if (group != null) {
                val memberCount = group.memberUids.size
                binding.memberCountText.text = "$memberCount 명"
                val isHost = group.hostUid == currentUid

                // 1. 확정 일정 표시 ⭐ NEW
                displayConfirmedSchedule(group)

                // 2. 일정 확정 버튼 가시성 제어 (현재는 테스트용이므로 항상 표시)
                binding.btnConfirmScheduleNow.visibility = View.VISIBLE

                // 3. 버튼 가시성 설정 (권한 분기)
                binding.btnDeleteGroup.visibility = if (isHost) View.VISIBLE else View.GONE
                binding.btnLeaveGroup.visibility = if (!isHost) View.VISIBLE else View.GONE

                // 4. 팀원 목록 표시
                displayMemberList(group.memberUids, group.hostUid, isHost)

            } else {
                binding.memberCountText.text = "오류 발생"
                Toast.makeText(this@GroupDetailActivity, "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * ⭐ NEW: 확정된 장소와 일정을 UI에 표시합니다.
     * Group 데이터 모델에 confirmedTime: Timestamp?와 confirmedPlace: Map<String, Any>? 필드가 있다고 가정합니다.
     */
    private fun displayConfirmedSchedule(group: Group) {
        val confirmedTime = group.confirmedTime
        val confirmedPlace = group.confirmedPlace

        if (confirmedTime != null && confirmedPlace != null) {
            // 데이터가 있는 경우: 섹션을 표시하고 텍스트를 업데이트
            val placeName = confirmedPlace["name"] as? String ?: confirmedPlace["address"] as? String ?: "장소 정보 없음"
            val formattedTime = formatTimestamp(confirmedTime)

            binding.confirmedScheduleSection.visibility = View.VISIBLE
            binding.textConfirmedPlace.text = "장소: $placeName"
            binding.textConfirmedTime.text = "일시: $formattedTime"
        } else {
            // 데이터가 없는 경우: 섹션을 숨김
            binding.confirmedScheduleSection.visibility = View.GONE
        }
    }

    /**
     * ⭐ NEW: Firebase Timestamp를 읽기 쉬운 문자열로 포맷합니다.
     */
    private fun formatTimestamp(timestamp: Timestamp): String {
        val date = timestamp.toDate()
        // 예: 2025년 11월 13일 (목) 오전 10:30
        val sdf = SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        return sdf.format(date)
    }

    /**
     * 팀원 목록을 표시하고 방장에게 강퇴 버튼을 제공합니다.
     */
    private fun displayMemberList(memberUids: List<String>, hostUid: String, isHost: Boolean) {
        val container = binding.memberListContainer
        container.removeAllViews() // 데이터 로딩 시 목록을 항상 초기화
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

                // item_member_list 레이아웃을 인플레이트
                val memberView = inflater.inflate(com.moyeoyo.app.R.layout.item_member_list, container, false)

                // View ID는 item_member_list.xml에 정의되어 있어야 함
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
        // GroupInviteActivity는 제공되지 않았으므로 임의로 GroupDetailActivity에 대한 인텐트를 사용합니다.
        // 실제로는 GroupInviteActivity로 이동해야 합니다.
        val intent = Intent(this, com.moyeoyo.app.ui.groups.GroupInviteActivity::class.java).apply {
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