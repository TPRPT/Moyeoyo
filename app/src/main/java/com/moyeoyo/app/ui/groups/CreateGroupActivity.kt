// com.moyeoyo.app.ui.groups/CreateGroupActivity.kt

package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityCreateGroupBinding // ⭐ View Binding import
import kotlinx.coroutines.launch

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private val groupRepository = GroupRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. 뒤로가기 버튼 설정
        // ⭐ 수정: binding.headerLayout.backButton -> binding.backButton
        binding.backButton.setOnClickListener {
            finish()
        }

        // 2. 입력 감지 리스너 (그룹 이름 입력 시 버튼 활성화)
        binding.groupNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                // ⭐ 수정: binding.bottomFixedLayout.createGroupButton -> binding.createGroupButton
                binding.createGroupButton.isEnabled = s.isNotBlank()
            }
            override fun afterTextChanged(s: Editable) {}
        })

        // 3. 그룹 만들기 버튼 리스너
        // ⭐ 수정: binding.bottomFixedLayout.createGroupButton -> binding.createGroupButton
        binding.createGroupButton.setOnClickListener {
            val groupName = binding.groupNameEditText.text.toString().trim()
            if (groupName.isNotEmpty()) {
                createGroup(groupName)
            }
        }
    }

    private fun createGroup(groupName: String) {
        lifecycleScope.launch {
            // 그룹 생성 비동기 호출
            val groupId = groupRepository.createGroup(groupName)

            if (groupId != null) {
                // 그룹 생성 성공: 초대 화면으로 그룹 이름과 ID 전달
                val intent = Intent(this@CreateGroupActivity, GroupInviteActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                    putExtra("GROUP_NAME", groupName)
                }
                startActivity(intent)
                finish() // 이 Activity는 이제 종료 (뒤로가기 시 메인으로)
            } else {
                Toast.makeText(this@CreateGroupActivity, "그룹 생성에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }
}