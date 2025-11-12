package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.FragmentCreateGroupBinding
import kotlinx.coroutines.launch

/**
 * 새 그룹 만들기 화면 (Fragment 버전)
 */
class CreateGroupFragment : Fragment() {

    private var _binding: FragmentCreateGroupBinding? = null
    private val binding get() = _binding!!

    private val groupRepository = GroupRepository()

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

        /** -------------------------------
         * ✅ 1. 뒤로가기 버튼
         * ------------------------------- */
        binding.backButton.setOnClickListener {
            findNavController().navigateUp() // NavController의 이전 화면으로 이동
        }

        /** -------------------------------
         * ✅ 2. 그룹 이름 입력 리스너
         * ------------------------------- */
        binding.groupNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.createGroupButton.isEnabled = !s.isNullOrBlank()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        /** -------------------------------
         * ✅ 3. 그룹 생성 버튼 클릭
         * ------------------------------- */
        binding.createGroupButton.setOnClickListener {
            val groupName = binding.groupNameEditText.text.toString().trim()
            if (groupName.isNotEmpty()) {
                createGroup(groupName)
            } else {
                Toast.makeText(requireContext(), "그룹 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** -------------------------------
     * ✅ Firestore 그룹 생성 로직
     * ------------------------------- */
    private fun createGroup(groupName: String) {
        lifecycleScope.launch {
            val groupId = groupRepository.createGroup(groupName)

            if (groupId != null) {
                // 그룹 생성 성공 → 초대 화면으로 이동
                val action = CreateGroupFragmentDirections
                    .actionCreateGroupFragmentToInviteGroupFragment(groupId, groupName)
                findNavController().navigate(action)
            } else {
                Toast.makeText(requireContext(), "그룹 생성에 실패했습니다. 다시 시도해주세요.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
