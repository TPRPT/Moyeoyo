package com.moyeoyo.app.ui.groups

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.groups.adapter.MemberListAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MemberTabFragment : Fragment() {

    private val groupId: String by lazy {
        arguments?.getString("groupId") ?: ""
    }

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository

    private lateinit var recyclerMemberList: RecyclerView
    private lateinit var btnInviteMember: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.view_member_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerMemberList = view.findViewById(R.id.recyclerMemberList)
        recyclerMemberList.layoutManager = LinearLayoutManager(requireContext())

        btnInviteMember = view.findViewById(R.id.btnInviteMember)
        btnInviteMember.setOnClickListener {
            navigateToInviteScreen()
        }

        loadMemberList()
    }

    private fun loadMemberList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            if (group != null) {
                val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val isHost = group.hostUid == currentUid

                val nicknames = group.memberUids.map { uid ->
                    async { uid to (friendRepository.getUserNickname(uid) ?: uid.take(8)) }
                }.awaitAll()

                recyclerMemberList.adapter = MemberListAdapter(
                    nicknames,
                    group.hostUid,
                    currentUid,
                    isHost
                ) { uid, name ->
                    showKickConfirmationDialog(uid, name)
                }
            }
        }
    }

    private fun showKickConfirmationDialog(uid: String, nickname: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("강퇴")
            .setMessage("${nickname} 님을 강퇴할까요?")
            .setPositiveButton("강퇴") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (groupRepository.removeMember(groupId, uid)) {
                        android.widget.Toast.makeText(requireContext(), "강퇴 완료", android.widget.Toast.LENGTH_SHORT).show()
                        loadMemberList()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun navigateToInviteScreen() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val groupName = group?.groupName ?: ""
            findNavController().navigate(
                R.id.groupInviteFragment,
                Bundle().apply {
                    putString("groupId", groupId)
                    putString("groupName", groupName)
                }
            )
        }
    }
}

