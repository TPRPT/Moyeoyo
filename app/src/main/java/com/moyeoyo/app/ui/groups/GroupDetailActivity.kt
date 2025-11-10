// com.moyeoyo.app.ui.groups/GroupDetailActivity.kt

package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth // Auth import
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.MainActivity
import kotlinx.coroutines.launch

class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    private val groupRepository = GroupRepository()
    private val auth = FirebaseAuth.getInstance() // Auth 인스턴스 추가

    private lateinit var groupId: String
    private lateinit var groupName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 그룹 ID와 이름 가져오기
        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Unknown Group"

        // UI에 정보 표시 (로딩 상태)
        binding.groupNameText.text = groupName

        // 데이터 로딩 시작 (onResume에서도 호출됨)
        loadGroupData()

        // 1. 친구 초대 버튼 리스너
        binding.btnInviteFriends.setOnClickListener {
            navigateToInviteScreen()
        }

        // 2. 그룹 삭제 버튼 리스너
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    // Activity가 재개될 때마다 목록을 새로고침 (데이터 동기화)
    override fun onResume() {
        super.onResume()
        loadGroupData()
    }

    /**
     * 그룹 상세 데이터를 로드하고 UI를 업데이트하며 삭제 권한을 확인합니다.
     */
    private fun loadGroupData() {
        binding.memberCountText.text = "로딩 중..."

        lifecycleScope.launch {
            val group = groupRepository.getGroupDetail(groupId)

            if (group != null) {
                // 1. 멤버 수 표시
                val memberCount = group.memberUids.size
                binding.memberCountText.text = "$memberCount 명"

                // 2. 삭제 버튼 활성화/비활성화 (권한 확인)
                val isHost = group.hostUid == auth.currentUser?.uid
                binding.btnDeleteGroup.isEnabled = isHost

                // 호스트가 아니면 투명도를 낮춰 비활성화된 것처럼 표시
                binding.btnDeleteGroup.alpha = if (isHost) 1.0f else 0.5f

            } else {
                binding.memberCountText.text = "오류 발생"
                Toast.makeText(this@GroupDetailActivity, "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
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
        // 버튼 상태가 비활성화되어 있으면 경고 토스트를 띄웁니다.
        if (!binding.btnDeleteGroup.isEnabled) {
            Toast.makeText(this, "그룹을 삭제할 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

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
                // 삭제 성공 후 메인 화면으로 복귀 (MainActivity의 onResume이 목록을 갱신함)
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 삭제에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }
}