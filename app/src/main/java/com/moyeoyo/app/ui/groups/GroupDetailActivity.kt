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
import com.google.firebase.Timestamp
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.ui.vote.ConfirmActivity
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.ui.location.LocationInputActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@AndroidEntryPoint
class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    @Inject
    lateinit var friendRepository: FriendRepository
    
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

        // 친구 초대
        binding.btnInviteFriends.setOnClickListener {
            navigateToInviteScreen()
        }

        // 그룹 삭제 (방장)
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteConfirmationDialog()
        }

        // 그룹 나가기 (멤버)
        binding.btnLeaveGroup.setOnClickListener {
            showLeaveConfirmationDialog()
        }

        // 시간 투표 버튼 리스너
        binding.btnTimeVote.setOnClickListener {
            val intent = Intent(this, com.moyeoyo.app.ui.time.TimeVoteActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
        }

        // 장소 투표 버튼 리스너
        binding.btnPlaceVote.setOnClickListener {
            // LocationInputActivity로 이동하여 위치 선택 화면 표시
            val intent = Intent(this, LocationInputActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
        }

        // 일정 확정
        binding.btnConfirmScheduleNow.setOnClickListener {
            val intent = Intent(this, ConfirmActivity::class.java)
            intent.putExtra("GROUP_ID", groupId)
            intent.putExtra("GROUP_NAME", groupName)
            startActivity(intent)
        }

        // ⭐ NEW: 투표 시작 버튼 클릭
        binding.btnStartVote.setOnClickListener {
            startVoting()
        }
    }

    override fun onResume() {
        super.onResume()
        loadGroupData()
    }

    /**
     * 그룹 상세 데이터 로드
     */
    private fun loadGroupData() {
        binding.memberCountText.text = "로딩 중..."
        binding.memberListContainer.removeAllViews()
        binding.confirmedScheduleSection.visibility = View.GONE

        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                val isHost = (group.hostUid == currentUid)
                val memberCount = group.memberUids.size
                binding.memberCountText.text = "$memberCount 명"

                // 확정 일정 표시
                displayConfirmedSchedule(group)

                // 삭제/나가기 버튼
                binding.btnDeleteGroup.visibility = if (isHost) View.VISIBLE else View.GONE
                binding.btnLeaveGroup.visibility = if (!isHost) View.VISIBLE else View.GONE

                // 팀원 표시
                // 4. 상태에 따른 버튼 활성화 제어
                updateButtonsByStatus(group.status)

                // 5. 팀원 목록 표시
                displayMemberList(group.memberUids, group.hostUid, isHost)

                // ⭐ NEW: 투표 시작 버튼 표시 여부
                updateStartVoteButton(group, isHost)

                // ⭐ NEW: 초대 버튼 표시 여부
                updateInviteButton(group, isHost)

            } else {
                binding.memberCountText.text = "오류 발생"
                Toast.makeText(this@GroupDetailActivity, "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * ⭐ NEW: 투표 시작 버튼 표시/숨기기
     */
    private fun updateStartVoteButton(group: Group, isHost: Boolean) {
        // 방장 + GROUP_CREATED 상태에서만 표시
        if (isHost && group.status == "GROUP_CREATED") {
            binding.btnStartVote.visibility = View.VISIBLE
        } else {
            binding.btnStartVote.visibility = View.GONE
        }
    }

    /**
     * ⭐ NEW: 초대 버튼 표시/숨기기
     * 투표 시작 후에는 멤버 추가 불가 → 버튼 숨김
     */
    private fun updateInviteButton(group: Group, isHost: Boolean) {
        if (group.status == "GROUP_CREATED") {
            binding.btnInviteFriends.visibility = View.VISIBLE
        } else {
            binding.btnInviteFriends.visibility = View.GONE
        }
    }

    /**
     * ⭐ NEW: 투표 시작 처리
     */
    private fun startVoting() {
        lifecycleScope.launch {
            val success = groupRepository.startVoting(groupId)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "투표를 시작했습니다!", Toast.LENGTH_SHORT).show()
                loadGroupData() // UI 갱신
            } else {
                Toast.makeText(this@GroupDetailActivity, "투표 시작에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 확정 일정 표시
     */
    private fun displayConfirmedSchedule(group: Group) {
        val confirmedTime = group.confirmedTime
        val confirmedPlace = group.confirmedPlace

        if (confirmedTime != null) {
            // 시간이 확정된 경우: 섹션을 표시하고 텍스트를 업데이트
            val formattedTime = formatTimestamp(confirmedTime)

            binding.confirmedScheduleSection.visibility = View.VISIBLE
            binding.textConfirmedTime.text = "일시: $formattedTime"

            // 장소 정보가 있으면 표시
            if (confirmedPlace != null) {
                val placeName = confirmedPlace["name"] as? String ?: confirmedPlace["address"] as? String ?: "장소 정보 없음"
                binding.textConfirmedPlace.text = "장소: $placeName"
                binding.textConfirmedPlace.visibility = View.VISIBLE
            } else {
                binding.textConfirmedPlace.visibility = View.GONE
            }
        } else {
            binding.confirmedScheduleSection.visibility = View.GONE
        }
    }

    private fun formatTimestamp(timestamp: Timestamp): String {
        val date = timestamp.toDate()
        val sdf = SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.getDefault())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }

    /**
     * 그룹 상태에 따라 버튼 활성화 제어
     */
    private fun updateButtonsByStatus(status: String?) {
        android.util.Log.d("GroupDetailActivity", "📊 그룹 상태: $status")

        when (status) {
            "GROUP_CREATED",
            "TIME_VOTE_REQUIRED" -> {
                // 시간 투표 단계
                android.util.Log.d("GroupDetailActivity", "✅ 시간 투표 단계 - 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            "TIME_FINALIZING" -> {
                // 시간 확정 단계 - 시간 투표만 활성화 (최종 시간 투표 진행 중)
                android.util.Log.d("GroupDetailActivity", "⏳ 시간 확정 단계 - 시간 투표만 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            "LOCATION_INPUT_REQUIRED",
            "LOCATION_DONE",
            "PLACE_RANKING",
            "FINAL_PLACE_VOTE",
            "FINALIZED" -> {
                // 장소 투표 단계 (시간 확정 완료)
                android.util.Log.d("GroupDetailActivity", "✅ 장소 투표 단계 - 장소 투표 활성화")
                binding.btnTimeVote.isEnabled = false
                binding.btnPlaceVote.isEnabled = true
            }
            null,
            "" -> {
                // 상태가 없거나 빈 문자열인 경우 - 기본적으로 시간 투표 활성화
                android.util.Log.w("GroupDetailActivity", "⚠️ 그룹 상태가 없음 - 기본값으로 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            else -> {
                // 예상치 못한 상태 - 기본값으로 시간 투표 활성화
                android.util.Log.w("GroupDetailActivity", "⚠️ 예상치 못한 그룹 상태: $status - 기본값으로 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
        }

        android.util.Log.d("GroupDetailActivity", "버튼 상태 - 시간 투표: ${binding.btnTimeVote.isEnabled}, 장소 투표: ${binding.btnPlaceVote.isEnabled}")
    }

    /**
     * 팀원 목록을 표시하고 방장에게 강퇴 버튼을 제공합니다.
     */
    private fun displayMemberList(memberUids: List<String>, hostUid: String, isHost: Boolean) {
        val container = binding.memberListContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        lifecycleScope.launch {
            val memberDetails = memberUids.map { uid ->
                async {
                    Pair(uid, friendRepository.getUserNickname(uid) ?: uid.take(8))
                }
            }.awaitAll()

            memberDetails.forEach { (uid, nickname) ->
                val isCurrentMemberHost = (uid == hostUid)
                val memberView = inflater.inflate(com.moyeoyo.app.R.layout.item_member_list, container, false)

                val nameText = memberView.findViewById<TextView>(com.moyeoyo.app.R.id.member_name)
                val statusText = memberView.findViewById<TextView>(com.moyeoyo.app.R.id.member_status)
                val kickButton = memberView.findViewById<Button>(com.moyeoyo.app.R.id.btn_kick_member)

                nameText.text = nickname
                statusText.text = if (isCurrentMemberHost) "(방장)" else ""

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

    private fun navigateToInviteScreen() {
        val intent = Intent(this, com.moyeoyo.app.ui.groups.GroupInviteActivity::class.java)
        intent.putExtra("GROUP_ID", groupId)
        intent.putExtra("GROUP_NAME", groupName)
        startActivity(intent)
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 삭제 확인")
            .setMessage("정말로 그룹 '$groupName'을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteGroup() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteGroup() {
        lifecycleScope.launch {
            val success = groupRepository.deleteGroup(groupId)
            if (success) {
                Toast.makeText(this@GroupDetailActivity, "'$groupName' 그룹이 삭제되었습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showKickConfirmationDialog(memberUid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("멤버 강퇴 확인")
            .setMessage("${nickname} 님을 그룹에서 강퇴하시겠습니까?")
            .setPositiveButton("강퇴") { _, _ -> kickMember(memberUid, nickname) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun kickMember(memberUid: String, nickname: String) {
        lifecycleScope.launch {
            val success = groupRepository.removeMember(groupId, memberUid)
            if (success) {
                Toast.makeText(this@GroupDetailActivity, "${nickname} 님을 강퇴했습니다.", Toast.LENGTH_LONG).show()
                loadGroupData()
            } else {
                Toast.makeText(this@GroupDetailActivity, "강퇴에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLeaveConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 나가기 확인")
            .setMessage("정말로 그룹 '$groupName'을 나가시겠습니까?")
            .setPositiveButton("나가기") { _, _ -> leaveGroup() }
            .setNegativeButton("취소", null)
            .show()
    }

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