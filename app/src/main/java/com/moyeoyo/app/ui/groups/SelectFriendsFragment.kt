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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SelectFriendsFragment : Fragment() {

    @Inject
    lateinit var friendRepository: FriendRepository
    private val selectedUids = mutableSetOf<String>()

    companion object {
        const val RESULT_KEY = "select_friends_result"
        const val EXTRA_SELECTED_UIDS = "extra_selected_uids"
        
        fun newInstance(): SelectFriendsFragment {
            return SelectFriendsFragment()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_select_friends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.friends_checkbox_container)
        val btnComplete = view.findViewById<Button>(R.id.btn_complete_selection)

        loadFriends(container)

        btnComplete.setOnClickListener {
            // Navigation 결과로 선택한 UID 전달
            val result = Bundle().apply {
                putStringArrayList(EXTRA_SELECTED_UIDS, ArrayList(selectedUids))
            }
            parentFragmentManager.setFragmentResult(RESULT_KEY, result)
            
            // 이전 화면으로 돌아가기
            findNavController().popBackStack()
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
                val tvName = itemView.findViewById<TextView>(R.id.tvName)
                val tvUid = itemView.findViewById<TextView>(R.id.tvUid)
                val checkbox = itemView.findViewById<CheckBox>(R.id.checkboxSelect)

                tvInitial.text = (nickname ?: uid.take(1)).uppercase()
                tvName.text = nickname ?: uid.take(8)
                tvUid.text = uid.take(8)

                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedUids.add(uid)
                    else selectedUids.remove(uid)
                }

                container.addView(itemView)
            }
        }
    }
}
