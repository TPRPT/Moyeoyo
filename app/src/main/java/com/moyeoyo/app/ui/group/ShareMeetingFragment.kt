package com.moyeoyo.app.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentShareMeetingBinding

class ShareMeetingFragment : Fragment(R.layout.fragment_share_meeting) {
    private lateinit var binding: FragmentShareMeetingBinding
    private val args by navArgs<ShareMeetingFragmentArgs>()


    private val HOSTING_DOMAIN = "moyeoyo.app"   // UI용 더미 도메인
    private lateinit var shareUrl: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentShareMeetingBinding.bind(view)

        // 전달받은 데이터
        val groupId = args.groupId
        val groupName = args.groupName
        val meetingTime = args.meetingTime ?: "시간 정보 없음"
        val meetingPlace = args.meetingPlace ?: "장소 정보 없음"
        val memberCount = args.memberCount

        // UI 미리보기용 더미 링크
        // 백엔드 연동 시 아래 한 줄만 실제 groupId 기반으로 변경하면 됨
        shareUrl = "https://$HOSTING_DOMAIN/meeting/preview"

        // UI 표시
        binding.tvShareLink.text = shareUrl
        binding.tvGroupName.text = groupName
        binding.tvMeetingTime.text = meetingTime
        binding.tvMeetingLocation.text = meetingPlace
        binding.tvMemberCount.text = "${memberCount}명"

        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnCopyLink.setOnClickListener {
            copyToClipboard(binding.tvShareLink.text.toString())
        }

        binding.btnCopy.setOnClickListener {
            copyToClipboard(binding.tvShareLink.text.toString())
        }

        // 다른 앱으로 공유
        binding.btnShareOther.setOnClickListener {
            shareLink(groupName, shareUrl)
        }

        // 카카오톡 공유 → 현재는 기본 공유 시트로
        binding.btnKakaoShare.setOnClickListener {
            shareLink(groupName, shareUrl)
        }

        binding.btnDone.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("약속 링크", text))
        Toast.makeText(requireContext(), "링크가 복사되었습니다", Toast.LENGTH_SHORT).show()
    }


    private fun shareLink(groupName: String, url: String) {
        val message = "약속 공유: [$groupName]\n$url"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(intent, "약속 공유"))
    }
}
