package com.moyeoyo.app.ui.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.moyeoyo.app.databinding.ActivityShareMeetingBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class ShareMeetingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareMeetingBinding
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var groupId: String
    private lateinit var groupName: String

    private var shareLat: Double? = null
    private var shareLng: Double? = null
    private var sharePlaceName: String = ""
    private var shareAddress: String = ""
    private var shareTimeFormatted: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareMeetingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "모임"

        setupToolbar()
        loadMeetingData()
        setupButtons()
    }

    private fun setupToolbar() {
        binding.toolbarShare.title = "약속 공유"
        binding.toolbarShare.setNavigationOnClickListener { finish() }
    }

    private fun loadMeetingData() {
        firestore.collection("groups").document(groupId).get()
            .addOnSuccessListener { doc ->
                val confirmedTime = doc.getTimestamp("confirmedTime")
                val confirmedPlace = doc.get("confirmedPlace") as? Map<String, Any>
                val memberUids = doc.get("memberUids") as? List<String> ?: emptyList()

                binding.tvGroupName.text = groupName
                binding.tvMemberCount.text = "${memberUids.size}명"

                // 시간
                if (confirmedTime != null) {
                    val sdf = SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.getDefault())
                    shareTimeFormatted = sdf.format(confirmedTime.toDate())
                    binding.tvMeetingTime.text = shareTimeFormatted
                }

                // 장소
                confirmedPlace?.let {
                    sharePlaceName = it["name"] as? String ?: ""
                    shareAddress = it["address"] as? String ?: ""

                    binding.tvMeetingLocation.text = "$sharePlaceName\n$shareAddress"

                    // 다양한 좌표 케이스 처리
                    shareLat = (it["latitude"] as? Number)?.toDouble()
                        ?: (it["lat"] as? Number)?.toDouble()
                    shareLng = (it["longitude"] as? Number)?.toDouble()
                        ?: (it["lng"] as? Number)?.toDouble()

                    if (shareLat != null && shareLng != null) {
                        binding.btnOpenMap.setOnClickListener {
                            val uri =
                                Uri.parse("https://www.google.com/maps/search/?api=1&query=$shareLat,$shareLng")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            startActivity(intent)
                        }
                    }
                }

                // 🔥 공유 링크 UI 표시
                binding.tvShareLink.text = buildShareLink()
            }
    }

    private fun setupButtons() {
        binding.btnCopyLink.setOnClickListener {
            copyText(binding.tvShareLink.text.toString())
        }

        binding.btnKakaoShare.setOnClickListener {
            val msg = buildShareMessage()

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }
            startActivity(Intent.createChooser(intent, "카카오톡으로 공유"))
        }

        binding.btnDone.setOnClickListener { finish() }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("shareLink", text))
        Toast.makeText(this, "링크가 복사되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * 공유 링크 생성 (고정: index.html)
     */
    private fun buildShareLink(): String {
        return "https://moyeoyo-57ac0.web.app"
    }


    /**
     * 초대 메시지 포맷 생성
     */
    private fun buildShareMessage(): String {
        val link = buildShareLink()

        return """
        📅 [${groupName}] 약속이 확정되었어요!

        🕒 시간  
        $shareTimeFormatted

        📍 장소  
        $sharePlaceName  
        $shareAddress

        🔗 링크  
        $link
    """.trimIndent()
    }
}