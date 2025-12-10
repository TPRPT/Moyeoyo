package com.moyeoyo.app.ui.groups

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.moyeoyo.app.databinding.ActivityShareMeetingBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class ShareMeetingFragment : Fragment() {

    private var _binding: ActivityShareMeetingBinding? = null
    private val binding get() = _binding!!
    
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private lateinit var groupId: String
    private lateinit var groupName: String

    private var shareLat: Double? = null
    private var shareLng: Double? = null
    private var sharePlaceName: String = ""
    private var shareAddress: String = ""
    private var shareTimeFormatted: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityShareMeetingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigation arguments에서 데이터 가져오기
        groupId = arguments?.getString("groupId") ?: run {
            findNavController().popBackStack()
            return
        }
        groupName = arguments?.getString("groupName") ?: "모임"

        setupToolbar()
        loadMeetingData()
        setupButtons()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupToolbar() {
        binding.toolbarShare.title = "약속 공유"
        binding.toolbarShare.setNavigationOnClickListener { 
            findNavController().popBackStack()
        }
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

        binding.btnDone.setOnClickListener { 
            findNavController().popBackStack()
        }
    }

    private fun copyText(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("shareLink", text))
        Toast.makeText(requireContext(), "링크가 복사되었습니다.", Toast.LENGTH_SHORT).show()
    }

    /**
     * 초대 메시지 포맷 생성
     */
    private fun buildShareMessage(): String {
        return """
        📅 [${groupName}] 약속이 확정되었어요!
        
        🕒 시간  
        $shareTimeFormatted
        
        📍 장소  
        $sharePlaceName  
        $shareAddress
                """.trimIndent()
        }
}

