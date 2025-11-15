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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.FragmentCreateGroupBinding
import com.moyeoyo.app.model.Friend
import kotlinx.coroutines.launch

/**
 * 새 그룹 만들기 화면 (Fragment 버전)
 */
class CreateGroupFragment : Fragment() {

    private var _binding: FragmentCreateGroupBinding? = null
    private val binding get() = _binding!!

    private val groupRepository = GroupRepository()

    // 친구 선택 목록
    private val selectedFriends = mutableSetOf<String>()

    // 친구 초대 어댑터
    private lateinit var inviteAdapter: InviteFriendAdapter

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

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
         * 1. 뒤로가기 버튼
         * ------------------------------- */
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        /** -------------------------------
         * 2. 그룹 이름 입력 리스너
         * ------------------------------- */
        binding.groupNameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.createGroupButton.isEnabled = !s.isNullOrBlank()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        /** -------------------------------
         * ❗ 친구 목록 불러오기
         * ------------------------------- */
        loadMyFriends()

        /** -------------------------------
         * 3. 그룹 만들기 버튼
         * ------------------------------- */
        binding.createGroupButton.setOnClickListener {
            val groupName = binding.groupNameEditText.text.toString().trim()

            if (groupName.isEmpty()) {
                Toast.makeText(requireContext(), "그룹 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            createGroup(groupName)
        }
    }

    /** -------------------------------
     * Firestore 그룹 생성
     * ------------------------------- */
    private fun createGroup(groupName: String) {
        lifecycleScope.launch {
            val groupId = groupRepository.createGroup(groupName)

            if (groupId != null) {
                // 선택된 친구 목록 배열 형태로 전달
                val action = CreateGroupFragmentDirections
                    .actionCreateGroupFragmentToInviteGroupFragment(
                        groupId = groupId,
                        groupName = groupName,
                        invitedFriendUids = selectedFriends.toTypedArray()
                    )

                findNavController().navigate(action)

            } else {
                Toast.makeText(requireContext(), "그룹 생성에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** -------------------------------
     * 🔥 Firestore에서 내 친구 목록 불러오기
     * ------------------------------- */
    private fun loadMyFriends() {
        val currentUser = auth.currentUser ?: return

        firestore.collection("users")
            .document(currentUser.uid)
            .collection("friends")
            .get()
            .addOnSuccessListener { result ->

                val friends = result.documents.map { doc ->
                    Friend(
                        uid = doc.getString("uid") ?: "",
                        name = doc.getString("name") ?: "",
                        email = doc.getString("email") ?: ""
                    )
                }

                setupInviteFriendList(friends)
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "친구 목록을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /** -------------------------------
     * 🔥 RecyclerView + 체크 기능 설정
     * ------------------------------- */
    private fun setupInviteFriendList(friendList: List<Friend>) {
        inviteAdapter = InviteFriendAdapter(friendList, selectedFriends) {
            updateSelectedCount()
        }

        binding.friendInviteRecyclerView.apply {
            adapter = inviteAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    /** -------------------------------
     * 🔥 체크된 친구 수 업데이트
     * ------------------------------- */
    private fun updateSelectedCount() {
        val count = selectedFriends.size
        binding.tvSelectedCount.text = "${count}명 선택됨"
        binding.createGroupButton.text = "그룹 만들기 (${count}명 초대)"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
