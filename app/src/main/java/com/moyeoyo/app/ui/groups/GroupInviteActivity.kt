package com.moyeoyo.app.ui.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.databinding.ActivityGroupInviteBinding

class GroupInviteActivity : AppCompatActivity() {

    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    private lateinit var binding: ActivityGroupInviteBinding
    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var inviteUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent에서 데이터 수신
        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "새 그룹"

        // 초대 링크 생성
        inviteUrl = "https://$HOSTING_DOMAIN/join?groupId=$groupId"

        // UI 구성
        binding.createdGroupNameText.text = groupName
        binding.inviteLinkEditText.setText(inviteUrl)

        // === 버튼 리스너 ===

        // 뒤로가기 → 메인 화면 (MaterialToolbar의 NavigationIcon 사용)
        binding.toolbarInvite.setNavigationOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 링크 복사
        binding.copyLinkButtonFull.setOnClickListener { copyToClipboard() }

        // 공유하기
        binding.shareKakaoButton.setOnClickListener { shareLink() }

        // 그룹으로 이동
        binding.goToGroupButton.setOnClickListener {
            val intent = Intent(this, GroupDetailActivity::class.java).apply {
                putExtra("GROUP_ID", groupId)
                putExtra("GROUP_NAME", groupName)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun copyToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Moyeoyo Group Invitation", inviteUrl)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "초대 링크가 복사되었습니다!", Toast.LENGTH_SHORT).show()
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