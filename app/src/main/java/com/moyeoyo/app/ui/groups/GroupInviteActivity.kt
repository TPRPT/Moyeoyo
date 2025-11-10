// com.moyeoyo.app.ui.groups/GroupInviteActivity.kt

package com.moyeoyo.app.ui.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moyeoyo.app.MainActivity // MainActivity 경로 가정
import com.moyeoyo.app.databinding.ActivityGroupInviteBinding

class GroupInviteActivity : AppCompatActivity() {

    // ⭐ Firebase Hosting 무료 도메인을 사용하여 딥링크 URL 생성
    private val HOSTING_DOMAIN = "moyeoyo-57ae0.web.app"

    private lateinit var binding: ActivityGroupInviteBinding
    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var invitationUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGroupInviteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Intent 데이터 수신 (GroupDetailActivity 또는 CreateGroupActivity에서 전달받음)
        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "새 그룹"

        // ⭐ 초대 링크 URL 생성 (수정된 도메인 및 Group ID 사용)
        invitationUrl = "https://$HOSTING_DOMAIN/join?groupId=$groupId"

        // UI 데이터 설정
        binding.createdGroupNameText.text = groupName
        binding.inviteLinkEditText.setText(invitationUrl) // EditText에 생성된 URL 표시

        // 1. 뒤로가기 버튼 리스너
        binding.backButtonInvite.setOnClickListener {
            // 메인 화면으로 이동
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        // 2. 그룹으로 이동 버튼
        binding.goToGroupButton.setOnClickListener {
            navigateToGroupDetail()
        }

        // 3. 링크 복사 버튼 (아이콘)
        binding.copyLinkButtonIcon.setOnClickListener {
            copyLinkToClipboard()
        }

        // 4. 링크 복사 버튼 (전체)
        binding.copyLinkButtonFull.setOnClickListener {
            copyLinkToClipboard()
        }

        // 5. 카카오톡 공유 버튼
        binding.shareKakaoButton.setOnClickListener {
            shareLinkViaIntent(groupName, invitationUrl)
        }
    }

    /**
     * 초대 링크를 클립보드에 복사합니다.
     */
    private fun copyLinkToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Moyeoyo Invitation Link", invitationUrl)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "초대 링크가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Android의 공유 시트를 띄워 링크를 공유합니다.
     */
    private fun shareLinkViaIntent(name: String, url: String) {
        val message = "그룹 [$name]에 초대합니다! $url"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, "초대 링크 공유"))
    }

    /**
     * Group Detail Activity로 이동합니다.
     */
    private fun navigateToGroupDetail() {
        // 그룹 ID와 이름을 GroupDetailActivity로 전달
        val intent = Intent(this, GroupDetailActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("GROUP_NAME", groupName)
        }
        startActivity(intent)
        finish()
    }
}