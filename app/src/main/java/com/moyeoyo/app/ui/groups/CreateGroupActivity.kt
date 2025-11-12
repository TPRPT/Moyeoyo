// com.moyeoyo.app.ui.groups/CreateGroupActivity.kt (수정)

package com.moyeoyo.app.ui.groups

import android.app.Activity // Activity import
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts // ⭐ 런처 import
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityCreateGroupBinding
import kotlinx.coroutines.launch

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private val groupRepository = GroupRepository()

    // 친구 선택 화면에서 돌아왔을 때 그룹 이름을 저장할 임시 변수
    private var pendingGroupName: String = ""

    // 선택된 친구 UID를 임시 저장할 변수
    private var selectedFriendUids: List<String> = emptyList()

    // SelectFriendsActivity 결과를 받을 런처
    private val selectFriendsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // SelectFriendsActivity에서 반환된 선택된 UID 목록을 받습니다.
            val selected = result.data?.getStringArrayListExtra(SelectFriendsActivity.EXTRA_SELECTED_UIDS)
            selectedFriendUids = selected?.toList() ?: emptyList()

            // 친구 선택 완료 후, 최종 그룹 생성 함수 호출
            createGroupFinal()
        } else {
            Toast.makeText(this, "친구 선택이 취소되었습니다. 그룹 생성을 다시 시도하세요.", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 뒤로가기 버튼 설정
        binding.backButton.setOnClickListener {
            finish()
        }

        // 2. 입력 감지 리스너 (그룹 이름 입력 시 버튼 활성화)
        binding.groupNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                binding.createGroupButton.isEnabled = s.isNotBlank()
            }
            override fun afterTextChanged(s: Editable) {}
        })

        // 3. 그룹 만들기 버튼 리스너 (⭐ 수정: 친구 선택 화면으로 이동)
        binding.createGroupButton.setOnClickListener {
            val groupName = binding.groupNameEditText.text.toString().trim()
            if (groupName.isNotEmpty()) {
                navigateToSelectFriends(groupName)
            }
        }
    }

    /**
     * ⭐ NEW: 친구 선택 화면으로 이동합니다.
     */
    private fun navigateToSelectFriends(groupName: String) {
        pendingGroupName = groupName // 그룹 이름을 임시 저장

        val intent = Intent(this, SelectFriendsActivity::class.java)
        selectFriendsLauncher.launch(intent)
    }

    /**
     * ⭐ NEW: 선택된 친구 목록을 포함하여 그룹을 최종 생성합니다.
     */
    private fun createGroupFinal() {
        val groupName = pendingGroupName

        // 로딩 표시 (ProgressDialog 사용 권장)

        lifecycleScope.launch {
            // GroupRepository의 새로운 함수 호출: 선택된 친구 목록을 포함
            val groupId = groupRepository.createGroupWithMembers(groupName, selectedFriendUids)

            // 로딩 종료

            if (groupId != null) {
                Toast.makeText(this@CreateGroupActivity, "'$groupName' 그룹이 생성되었습니다!", Toast.LENGTH_LONG).show()

                // 그룹 생성 성공: 초대 화면으로 이동 (MainActivity로 복귀 대신 초대 화면으로 이동)
                val intent = Intent(this@CreateGroupActivity, GroupInviteActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                    putExtra("GROUP_NAME", groupName)
                }
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this@CreateGroupActivity, "그룹 생성에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 기존 private fun createGroup(groupName: String) 함수는 삭제하거나 사용하지 않음
}