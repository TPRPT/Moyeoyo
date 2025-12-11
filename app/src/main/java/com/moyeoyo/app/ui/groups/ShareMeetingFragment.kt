package com.moyeoyo.app.ui.groups

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
import com.moyeoyo.app.databinding.FragmentShareMeetingBinding
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import java.text.SimpleDateFormat
import java.util.*

class ShareMeetingFragment : Fragment() {

    private var _binding: FragmentShareMeetingBinding? = null
    private val binding get() = _binding!!
    
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val HOSTING_DOMAIN = "moyeoyo-57ac0.web.app"

    private lateinit var groupId: String
    private lateinit var groupName: String

    private var shareLat: Double? = null
    private var shareLng: Double? = null
    private var sharePlaceName: String = ""
    private var shareAddress: String = ""
    private var sharePlaceId: String = ""
    private var shareTimeFormatted: String = ""
    private var shareLink: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentShareMeetingBinding.inflate(inflater, container, false)
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
        // 💡 순서 변경: 리스너를 먼저 설정하고, 데이터를 나중에 불러온다.
        setupButtons()
        loadMeetingData()
    }

    override fun onResume() {
        super.onResume()
        // 화면이 다시 활성화될 때 데이터 동기화
        loadMeetingData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun isFragmentValid(): Boolean {
        return isAdded && view != null && !isDetached && !isRemoving
    }

    private fun setupToolbar() {
        // 상단 바 제목 제거 (중복)
        binding.toolbarShare.title = ""
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
                    // 💡 전역 변수에 데이터 저장
                    sharePlaceName = it["name"] as? String ?: ""
                    shareAddress = it["address"] as? String ?: ""
                    sharePlaceId = it["placeId"] as? String ?: ""

                    binding.tvMeetingLocation.text = "$sharePlaceName\n$shareAddress"

                    // 다양한 좌표 케이스 처리
                    shareLat = (it["latitude"] as? Number)?.toDouble()
                        ?: (it["lat"] as? Number)?.toDouble()
                    shareLng = (it["longitude"] as? Number)?.toDouble()
                        ?: (it["lng"] as? Number)?.toDouble()
                }
                
                // 공유 링크 생성 (그룹 링크)
                shareLink = "https://$HOSTING_DOMAIN/join?groupId=$groupId"
            }
            .addOnFailureListener {
                // 실패 처리
                if (isFragmentValid()) {
                    Toast.makeText(requireContext(), "약속 정보를 불러오는 데 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun setupButtons() {
        // ⭐ '지도로 보기' 버튼의 리스너를 이곳으로 이동!
        binding.btnOpenMap.setOnClickListener {
            // 💡 클릭하는 시점의 전역 변수 값을 사용한다.
            val uri = when {
                // 1. 장소 이름과 주소를 함께 query로 사용 (가장 구체적이고 정확함)
                sharePlaceName.isNotBlank() && shareAddress.isNotBlank() -> {
                    val fullQuery = "$sharePlaceName, $shareAddress"
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(fullQuery)}")
                }
                // 2. 주소만 있을 경우 (세부 주소로 검색)
                shareAddress.isNotBlank() -> {
                    val query = if (shareLat != null && shareLng != null) {
                        // 좌표가 있으면 함께 전달하여 더 정확한 검색
                        "${Uri.encode(shareAddress)}&ll=$shareLat,$shareLng"
                    } else {
                        Uri.encode(shareAddress)
                    }
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")
                }
                // 3. 장소 이름만 있을 경우
                sharePlaceName.isNotBlank() -> {
                    val query = if (shareLat != null && shareLng != null) {
                        // 좌표가 있으면 함께 전달하여 더 정확한 검색
                        "${Uri.encode(sharePlaceName)}&ll=$shareLat,$shareLng"
                    } else {
                        Uri.encode(sharePlaceName)
                    }
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")
                }
                // 4. 좌표만 있는 경우
                shareLat != null && shareLng != null -> {
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=$shareLat,$shareLng")
                }
                // 5. 아무 정보도 없으면
                else -> {
                    if (isFragmentValid()) {
                        Toast.makeText(requireContext(), "장소 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                    return@setOnClickListener
                }
            }

            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.google.android.apps.maps") // 구글 지도 앱으로 직접 열기
                startActivity(intent)
            } catch (e: Exception) {
                // 구글 지도 앱이 없으면 웹 브라우저로 열기
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
            }
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
        
        $shareLink
                """.trimIndent()
        }
}

