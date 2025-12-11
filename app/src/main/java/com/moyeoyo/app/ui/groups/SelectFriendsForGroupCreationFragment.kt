package com.moyeoyo.app.ui.groups

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 그룹 생성용 친구 선택 Fragment
 * CreateGroupFragment에서만 사용됨
 */
@AndroidEntryPoint
class SelectFriendsForGroupCreationFragment : Fragment() {

    @Inject
    lateinit var friendRepository: FriendRepository
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private val selectedUids = mutableSetOf<String>()
    
    private val groupName: String by lazy {
        arguments?.getString("groupName") ?: throw IllegalStateException("groupName is required")
    }
    
    private var groupCreationInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_select_friends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedUids.clear()

        val container = view.findViewById<LinearLayout>(R.id.friends_checkbox_container)
        val btnComplete = view.findViewById<Button>(R.id.btn_complete_selection)

        loadFriends(container)

        btnComplete.setOnClickListener {
            if (groupCreationInProgress) {
                return@setOnClickListener
            }
            
            val selectedFriendUids = selectedUids.toList()
            selectedUids.clear()
            createGroup(groupName, selectedFriendUids)
        }
    }

    private fun loadFriends(container: LinearLayout) {
        container.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val friendUids = friendRepository.getFriendUids()

            if (friendUids.isEmpty()) {
                val textView = TextView(requireContext()).apply {
                    text = "친구 목록이 비어 있습니다. 먼저 친구를 추가해주세요."
                    setPadding(16, 16, 16, 16)
                }
                container.addView(textView)
                return@launch
            }

            val inflater = LayoutInflater.from(requireContext())

            friendUids.forEach { uid ->
                val nickname = friendRepository.getUserNickname(uid)
                val itemView = inflater.inflate(R.layout.item_friend_card, container, false)

                val tvInitial = itemView.findViewById<TextView>(R.id.tvInitial)
                val imgProfile = itemView.findViewById<ImageView>(R.id.imgProfile)
                val tvName = itemView.findViewById<TextView>(R.id.tvName)
                val tvUid = itemView.findViewById<TextView>(R.id.tvUid)
                val checkbox = itemView.findViewById<CheckBox>(R.id.checkboxSelect)

                // 프로필 사진 및 이메일 로드
                viewLifecycleOwner.lifecycleScope.launch {
                    val photoUrl = friendRepository.getUserPhotoUrl(uid)
                    val email = friendRepository.getUserEmail(uid)
                    
                    if (!photoUrl.isNullOrEmpty()) {
                        tvInitial.visibility = View.GONE
                        imgProfile.visibility = View.VISIBLE
                        com.bumptech.glide.Glide.with(requireContext())
                            .load(photoUrl)
                            .circleCrop()
                            .placeholder(com.moyeoyo.app.R.drawable.ic_user_placeholder)
                            .into(imgProfile)
                    } else {
                        tvInitial.visibility = View.VISIBLE
                        imgProfile.visibility = View.GONE
                        tvInitial.text = (nickname ?: uid.take(1)).uppercase()
                    }
                    
                    tvName.text = nickname ?: uid.take(8)
                    tvUid.text = email ?: uid.take(8)
                }

                // 체크박스 상태 변경 리스너
                checkbox.setOnCheckedChangeListener(null) // 기존 리스너 제거
                checkbox.isChecked = selectedUids.contains(uid)
                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedUids.add(uid)
                    else selectedUids.remove(uid)
                }

                // 카드 전체 클릭 시 체크박스 토글
                itemView.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }

                container.addView(itemView)
            }
        }
    }
    
    private fun createGroup(groupName: String, friendUids: List<String>) {
        if (groupCreationInProgress) return
        
        groupCreationInProgress = true
        
        // 로딩 표시
        val loadingDialog = android.app.ProgressDialog(requireContext()).apply {
            setMessage("그룹 생성 중...")
            setCancelable(false)
            show()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val groupId = groupRepository.createGroupWithMembers(
                    groupName = groupName,
                    friendUids = friendUids
                )
                
                loadingDialog.dismiss()
                groupCreationInProgress = false
                
                if (groupId != null) {
                    Toast.makeText(requireContext(), "그룹이 생성되었습니다!", Toast.LENGTH_SHORT).show()
                    
                    // Navigation 스택 정리 후 그룹 상세 화면으로 이동
                    findNavController().popBackStack(R.id.mainFragment, false)
                    findNavController().navigate(
                        R.id.action_mainFragment_to_groupDetailFragment,
                        Bundle().apply {
                            putString("groupId", groupId)
                            putString("groupName", groupName)
                        }
                    )
                } else {
                    Toast.makeText(requireContext(), "그룹 생성에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                loadingDialog.dismiss()
                groupCreationInProgress = false
                Toast.makeText(requireContext(), "그룹 생성 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

