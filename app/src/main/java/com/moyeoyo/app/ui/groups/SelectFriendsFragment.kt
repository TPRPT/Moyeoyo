package com.moyeoyo.app.ui.groups

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 멤버 추가용 친구 선택 Fragment
 * MemberTabFragment에서만 사용됨
 */
@AndroidEntryPoint
class SelectFriendsFragment : Fragment() {

    @Inject
    lateinit var friendRepository: FriendRepository
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private val selectedUids = mutableSetOf<String>()
    
    private val groupId: String by lazy {
        arguments?.getString("groupId") ?: throw IllegalStateException("groupId is required for SelectFriendsFragment")
    }

    companion object {
        const val RESULT_KEY = "select_friends_for_add_members"
        const val EXTRA_SELECTED_UIDS = "extra_selected_uids"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_select_friends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ⭐ 선택 상태 초기화 (이전 값 제거)
        selectedUids.clear()

        val container = view.findViewById<LinearLayout>(R.id.friends_checkbox_container)
        val btnComplete = view.findViewById<Button>(R.id.btn_complete_selection)

        loadFriends(container)

        btnComplete.setOnClickListener {
            // ⭐ 선택 완료 버튼 클릭 시에만 결과 전달
            android.util.Log.d("SelectFriendsFragment", "선택 완료 클릭: ${selectedUids.size}명 선택됨, groupId=$groupId")
            
            // Navigation 결과로 선택한 UID 전달 (Activity의 FragmentManager 사용)
            val result = Bundle().apply {
                putStringArrayList(EXTRA_SELECTED_UIDS, ArrayList(selectedUids))
            }
            
            android.util.Log.d("SelectFriendsFragment", "결과 전달 시작: selectedUids=${selectedUids.size}명")
            requireActivity().supportFragmentManager.setFragmentResult(RESULT_KEY, result)
            android.util.Log.d("SelectFriendsFragment", "결과 전달 완료")
            
            // 선택 상태 초기화
            selectedUids.clear()
            
            // ⭐ 멤버 추가용: 즉시 돌아가기
            view?.post {
                android.util.Log.d("SelectFriendsFragment", "popBackStack 호출 (멤버 추가용)")
                findNavController().popBackStack()
            }
        }
    }

    private fun loadFriends(container: LinearLayout) {
        container.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            // 멤버 추가용이므로 그룹의 현재 멤버 목록 가져오기
            val group = groupRepository.getGroupById(groupId)
            val existingMemberUids = group?.memberUids?.toSet() ?: emptySet()

            val friendUids = friendRepository.getFriendUids()
            
            // 이미 멤버인 친구 제외
            val availableFriendUids = friendUids.filter { it !in existingMemberUids }

            if (availableFriendUids.isEmpty()) {
                val textView = TextView(requireContext()).apply {
                    text = if (friendUids.isEmpty()) {
                        "친구 목록이 비어 있습니다. 먼저 친구를 추가해주세요."
                    } else {
                        "추가할 수 있는 친구가 없습니다. 모든 친구가 이미 그룹 멤버입니다."
                    }
                    setPadding(16, 16, 16, 16)
                }
                container.addView(textView)
                return@launch
            }

            val inflater = LayoutInflater.from(requireContext())

            availableFriendUids.forEach { uid ->
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
}
