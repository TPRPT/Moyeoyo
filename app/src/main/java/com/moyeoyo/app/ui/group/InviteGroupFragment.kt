package com.moyeoyo.app.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentInviteGroupBinding

/**
 * 그룹 초대 화면 (Friend 자동 초대 + 링크 공유)
 */
class InviteGroupFragment : Fragment() {

    private var _binding: FragmentInviteGroupBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<InviteGroupFragmentArgs>()

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val HOSTING_DOMAIN = "moyeoyo-57ae0.web.app"
    private lateinit var invitationUrl: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInviteGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = args.groupId
        val groupName = args.groupName
        val invitedFriendUids = args.invitedFriendUids ?: emptyArray()

        // 초대 링크 생성
        invitationUrl = "https://$HOSTING_DOMAIN/join?groupId=$groupId"

        // UI 표시
        binding.createdGroupNameText.text = groupName
        binding.inviteLinkEditText.setText(invitationUrl)

        // ▣ 친구 초대 UI 표시
        showInvitedFriends(invitedFriendUids.toList())

        // ▣ Firestore에 초대 처리
        sendInvitationsToFirestore(invitedFriendUids.toList(), groupId, groupName)

        /** -------------------------
         * 뒤로가기 → 메인으로 이동
         * ------------------------- */
        binding.backButtonInvite.setOnClickListener {
            findNavController().navigate(R.id.action_inviteGroupFragment_to_mainFragment)
        }

        /** -------------------------
         * 그룹 상세로 이동
         * ------------------------- */
        binding.goToGroupButton.setOnClickListener {
            val action = InviteGroupFragmentDirections
                .actionInviteGroupFragmentToGroupDetailFragment(
                    groupId,
                    groupName,
                    0
                )
            findNavController().navigate(action)
        }

        /** -------------------------
         * 링크 복사
         * ------------------------- */
        binding.copyLinkButtonIcon.setOnClickListener { copyLinkToClipboard() }
        binding.copyLinkButtonFull.setOnClickListener { copyLinkToClipboard() }

        /** -------------------------
         * 공유하기
         * ------------------------- */
        binding.shareKakaoButton.setOnClickListener {
            shareLinkViaIntent(groupName, invitationUrl)
        }
    }

    /**
     * 🔥 CreateGroupFragment에서 선택한 친구들 목록을 UI로 보여줌
     */
    private fun showInvitedFriends(invitedUids: List<String>) {
        if (invitedUids.isEmpty()) return

        binding.invitedFriendsContainer.visibility = View.VISIBLE
        binding.invitedFriendsList.removeAllViews()

        for (uid in invitedUids) {
            val itemView = layoutInflater.inflate(
                R.layout.item_invited_friend,
                binding.invitedFriendsList,
                false
            ) as LinearLayout

            val nameView = itemView.findViewById<TextView>(R.id.invitedFriendName)

            // Firestore에서 친구 정보 불러오기
            firestore.collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener { doc ->
                    val nickname = doc.getString("nickname") ?: "알 수 없음"
                    val email = doc.getString("email") ?: ""

                    nameView.text = "$nickname ($email)"
                }

            binding.invitedFriendsList.addView(itemView)
        }
    }

    /**
     * 🔥 Firestore에 친구 초대 문서 생성
     */
    private fun sendInvitationsToFirestore(
        invitedUids: List<String>,
        groupId: String,
        groupName: String
    ) {
        val currentUser = auth.currentUser ?: return

        for (friendUid in invitedUids) {

            val inviteData = mapOf(
                "groupId" to groupId,
                "groupName" to groupName,
                "invitedBy" to currentUser.uid,
                "status" to "pending",
                "timestamp" to System.currentTimeMillis()
            )

            // 그룹 초대 문서 → 각 친구의 user 컬렉션에 저장
            firestore.collection("users")
                .document(friendUid)
                .collection("groupInvitations")
                .document(groupId)
                .set(inviteData)
        }
    }

    private fun copyLinkToClipboard() {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Moyeoyo Invitation Link", invitationUrl)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "초대 링크가 복사되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun shareLinkViaIntent(name: String, url: String) {
        val message = "그룹 [$name]에 초대합니다!\n$url"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, "초대 링크 공유"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
