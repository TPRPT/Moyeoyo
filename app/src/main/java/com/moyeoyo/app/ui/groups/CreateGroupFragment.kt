package com.moyeoyo.app.ui.groups

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.FragmentCreateGroupBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class CreateGroupFragment : Fragment() {

    private var _binding: FragmentCreateGroupBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var groupRepository: GroupRepository

    private var pendingGroupName: String = ""
    private var selectedFriendUids: List<String> = emptyList()

    // 🔥 중복 생성 방지 플래그 (필수)
    private var groupCreationInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔥 친구 선택 결과 리스너 설정
        setFragmentResultListener(SelectFriendsFragment.RESULT_KEY) { _, bundle ->
            val selected = bundle.getStringArrayList(SelectFriendsFragment.EXTRA_SELECTED_UIDS)
            selectedFriendUids = selected?.toList() ?: emptyList()
            
            // 최종 그룹 생성 실행
            createGroupFinal()
        }

        binding.backButton.setOnClickListener { 
            findNavController().popBackStack()
        }

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * 🔥 친구 선택 화면 이동
     */
    private fun navigateToSelectFriends(groupName: String) {
        pendingGroupName = groupName
        findNavController().navigate(R.id.selectFriendsFragment)
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
                groupName = groupName,
                friendUids = selectedFriendUids
            )

            groupCreationInProgress = false

            if (groupId != null) {
                Toast.makeText(
                    requireContext(),
                    "그룹이 생성되었습니다!",
                    Toast.LENGTH_SHORT
                ).show()

                // 그룹 상세 화면으로 이동
                val action = CreateGroupFragmentDirections.actionCreateGroupFragmentToGroupDetailFragment(
                    groupId = groupId,
                    groupName = groupName
                )
                findNavController().navigate(action)
            } else {
                Toast.makeText(
                    requireContext(),
                    "그룹 생성에 실패했습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

