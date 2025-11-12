package com.moyeoyo.app.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentInviteGroupBinding

/**
 * 그룹 초대 화면 (Fragment 버전)
 */
class InviteGroupFragment : Fragment() {

    private var _binding: FragmentInviteGroupBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<InviteGroupFragmentArgs>()

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

        // 초대 링크 생성
        invitationUrl = "https://$HOSTING_DOMAIN/join?groupId=$groupId"

        // UI 세팅
        binding.createdGroupNameText.text = groupName
        binding.inviteLinkEditText.setText(invitationUrl)

        /** -------------------------------
         * ✅ 1. 뒤로가기 버튼 → 메인으로
         * ------------------------------- */
        binding.backButtonInvite.setOnClickListener {
            findNavController().navigate(R.id.action_inviteGroupFragment_to_mainFragment)
        }

        /** -------------------------------
         * ✅ 2. 그룹 상세로 이동 버튼
         * ------------------------------- */
        binding.goToGroupButton.setOnClickListener {
            val action = InviteGroupFragmentDirections
                .actionInviteGroupFragmentToGroupDetailFragment(groupId, groupName, 0)
            findNavController().navigate(action)
        }

        /** -------------------------------
         * ✅ 3. 링크 복사 버튼
         * ------------------------------- */
        binding.copyLinkButtonIcon.setOnClickListener {
            copyLinkToClipboard()
        }

        binding.copyLinkButtonFull.setOnClickListener {
            copyLinkToClipboard()
        }

        /** -------------------------------
         * ✅ 4. 카카오톡 공유 (기본 공유 시트)
         * ------------------------------- */
        binding.shareKakaoButton.setOnClickListener {
            shareLinkViaIntent(groupName, invitationUrl)
        }
    }

    private fun copyLinkToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
