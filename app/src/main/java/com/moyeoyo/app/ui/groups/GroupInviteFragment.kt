package com.moyeoyo.app.ui.groups

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
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.ActivityGroupInviteBinding

class GroupInviteFragment : Fragment() {

    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    private var _binding: ActivityGroupInviteBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var inviteUrl: String

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityGroupInviteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigation arguments에서 데이터 수신
        groupId = arguments?.getString("groupId") ?: run {
            findNavController().popBackStack()
            return
        }
        groupName = arguments?.getString("groupName") ?: "새 그룹"

        // 초대 링크 생성
        inviteUrl = "https://$HOSTING_DOMAIN/join?groupId=$groupId"

        // UI 구성
        binding.createdGroupNameText.text = groupName
        binding.inviteLinkEditText.setText(inviteUrl)

        // === 버튼 리스너 ===

        // 뒤로가기 → 메인 화면 (MaterialToolbar의 NavigationIcon 사용)
        binding.toolbarInvite.setNavigationOnClickListener {
            findNavController().popBackStack(R.id.mainFragment, false)
        }

        // 링크 복사
        binding.copyLinkButtonFull.setOnClickListener { copyToClipboard() }

        // 공유하기
        binding.shareKakaoButton.setOnClickListener { shareLink() }

        // 그룹으로 이동
        binding.goToGroupButton.setOnClickListener {
            findNavController().navigate(
                R.id.groupDetailFragment,
                Bundle().apply {
                    putString("groupId", groupId)
                    putString("groupName", groupName)
                }
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun copyToClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Moyeoyo Group Invitation", inviteUrl)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(requireContext(), "초대 링크가 복사되었습니다!", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val msg = "그룹 [$groupName]에 참여해보세요!\n$inviteUrl"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, msg)
        }
        startActivity(Intent.createChooser(intent, "링크 공유"))
    }
}

