package com.moyeoyo.app.ui.groups

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityCreateGroupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding

    @Inject
    lateinit var groupRepository: GroupRepository

    private var pendingGroupName: String = ""
    private var selectedFriendUids: List<String> = emptyList()

    // 🔥 중복 생성 방지 플래그 (필수)
    private var groupCreationInProgress = false

    // 🔥 친구 선택 ActivityResultLauncher
    private val selectFriendsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {

            val selected =
                result.data?.getStringArrayListExtra(SelectFriendsActivity.EXTRA_SELECTED_UIDS)

            selectedFriendUids = selected?.toList() ?: emptyList()

            // 최종 그룹 생성 실행
            createGroupFinal()

        } else {
            Toast.makeText(
                this,
                "친구 선택이 취소되었습니다. 다시 시도해주세요.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.groupNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.createGroupButton.isEnabled = s?.isNotBlank() == true
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.createGroupButton.setOnClickListener {
            val groupName = binding.groupNameEditText.text.toString().trim()
            if (groupName.isNotEmpty()) {
                navigateToSelectFriends(groupName)
            }
        }
    }

    /**
     * 🔥 친구 선택 화면 이동
     */
    private fun navigateToSelectFriends(groupName: String) {
        pendingGroupName = groupName
        val intent = Intent(this, SelectFriendsActivity::class.java)
        selectFriendsLauncher.launch(intent)
    }

    /**
     * 🔥 최종 그룹 생성 (중복 실행 방지 포함)
     */
    private fun createGroupFinal() {

        if (groupCreationInProgress) return  // 중복 방지
        groupCreationInProgress = true

        val groupName = pendingGroupName

        lifecycleScope.launch {

            val groupId = groupRepository.createGroupWithMembers(
                groupName,
                selectedFriendUids
            )

            if (groupId != null) {

                Toast.makeText(
                    this@CreateGroupActivity,
                    "'$groupName' 그룹이 생성되었습니다!",
                    Toast.LENGTH_LONG
                ).show()

                // 🔥 바로 그룹 디테일 화면으로 이동
                val intent = Intent(this@CreateGroupActivity, GroupDetailActivity::class.java).apply {
                    putExtra("GROUP_ID", groupId)
                    putExtra("GROUP_NAME", groupName)
                }
                startActivity(intent)
                finish()

            } else {
                Toast.makeText(
                    this@CreateGroupActivity,
                    "그룹 생성에 실패했습니다.",
                    Toast.LENGTH_LONG
                ).show()

                // 실패 시 다시 생성 가능
                groupCreationInProgress = false
            }
        }
    }
}
