package com.moyeoyo.app.ui.place

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.local.saveMeetingToLocal
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.databinding.FragmentFinalVoteBinding
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.widget.NextMeetingWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FinalVoteFragment : Fragment() {

    private var _binding: FragmentFinalVoteBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: FinalVoteViewModel by viewModels()
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private lateinit var adapter: FinalCandidateAdapter
    private val finalCandidates = mutableListOf<FinalCandidate>()
    private var selectedCandidate: FinalCandidate? = null
    private val auth = FirebaseAuth.getInstance()
    
    private var winningPlaceDialog: AlertDialog? = null

    private val args: FinalVoteFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFinalVoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = args.groupId
        
        lifecycleScope.launch {
            try {
                val currentGroup = groupRepository.getGroupDetail(groupId)
                if (currentGroup?.status == "PLACE_RANKING") {
                    val success = groupRepository.updateGroupStatus(groupId, "FINAL_PLACE_VOTE")
                    if (success) {
                        android.util.Log.d("FinalVoteFragment", 
                            "✅ 그룹 상태 변경: PLACE_RANKING → FINAL_PLACE_VOTE")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteFragment", 
                    "그룹 상태 변경 중 오류: ${e.message}")
            }
        }

        setupViews()
        setupRecyclerView()
        observeViewModel()

        // ⭐ 최종 후보는 '진행하기' 버튼을 눌렀을 때만 생성되므로, 여기서는 리스너만 시작
        // loadAllUserRankingsAndCreateCandidates는 navigateToFinalVote()에서 호출됨
        viewModel.startListeningToVoteStatus(groupId)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        winningPlaceDialog?.dismiss()
        winningPlaceDialog = null
        _binding = null
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnSubmitVote.setOnClickListener {
            selectedCandidate?.let { candidate ->
                val groupId = args.groupId
                viewModel.submitVote(groupId, candidate.place.placeId)
            } ?: run {
                Toast.makeText(requireContext(), "장소를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = FinalCandidateAdapter { candidate ->
            selectedCandidate = if (selectedCandidate == candidate) {
                null
            } else {
                candidate
            }
            
            if (selectedCandidate != null) {
                viewModel.calculateTransitTimeForPlace(candidate.place)
            } else {
                viewModel.calculateTransitTimeForPlace(candidate.place)
            }
            
            finalCandidates.forEachIndexed { index, item ->
                finalCandidates[index] = item.copy(isSelected = item == selectedCandidate)
            }
            adapter.submitList(finalCandidates.toList())
            
            binding.btnSubmitVote.visibility = if (selectedCandidate != null) View.VISIBLE else View.GONE
        }
        
        binding.rvFinalCandidates.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFinalCandidates.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.finalCandidates.observe(viewLifecycleOwner) { candidates ->
            android.util.Log.d("FinalVoteFragment", 
                "🔍 finalCandidates observer 트리거 - 후보 수: ${candidates.size}")
            
            if (candidates.isEmpty()) {
                finalCandidates.clear()
                adapter.submitList(emptyList())
                return@observe
            }
            
            finalCandidates.clear()
            finalCandidates.addAll(candidates)
            
            adapter.submitList(candidates.toList()) {
                android.util.Log.d("FinalVoteFragment", 
                    "✅ RecyclerView 어댑터 업데이트 완료 - 아이템 수: ${adapter.itemCount}")
            }
        }

        viewModel.transitTimes.observe(viewLifecycleOwner) { transitTimes ->
            adapter.updateTransitTimes(transitTimes)
        }

        viewModel.voteStatus.observe(viewLifecycleOwner) { status ->
            binding.tvVoteStatus.text = "투표 상태: ${status.completed}/${status.total} 명 완료"
        }

        viewModel.voteSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                android.util.Log.d("FinalVoteFragment", 
                    "✅ 투표 완료 - 승리 장소 결정은 ViewModel에서 처리됨")
                // 투표 제출 완료 시 진동 피드백은 제거 (최종 확정 시에만 진동)
            }
        }

        viewModel.winningPlace.observe(viewLifecycleOwner) { winningPlace ->
            winningPlace?.let { place ->
                // ⭐ Fragment 유효성 확인
                if (isFragmentValid()) {
                    android.util.Log.d("FinalVoteFragment", 
                        "🏆 승리한 장소 확인: ${place.name}")
                    showWinningPlaceDialog(place)
                } else {
                    android.util.Log.w("FinalVoteFragment", 
                        "⚠️ Fragment가 유효하지 않아 다이얼로그 표시를 건너뜁니다.")
                }
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }
        
        viewModel.saveComplete.observe(viewLifecycleOwner) { saved ->
            if (saved) {
                android.util.Log.d("FinalVoteFragment", 
                    "✅ 후보 저장 완료 - finalCandidates는 vote 문서 리스너에서 자동 업데이트됨")
            }
        }
    }
    
    /**
     * Fragment가 유효한지 확인하는 안전한 체크 함수
     */
    private fun isFragmentValid(): Boolean {
        return isAdded && view != null && !isDetached && !isRemoving
    }

    /**
     * 안전한 네비게이션 - Fragment가 유효한 경우에만 실행
     */
    private fun safeNavigateToGroupDetail(groupId: String) {
        if (!isFragmentValid()) {
            android.util.Log.w("FinalVoteFragment", "⚠️ Fragment가 유효하지 않아 네비게이션을 건너뜁니다.")
            return
        }
        
        try {
            // popBackStack을 사용하여 GroupDetailFragment까지 돌아감
            // false = GroupDetailFragment는 스택에 유지
            val popped = findNavController().popBackStack(R.id.groupDetailFragment, false)
            if (!popped) {
                // GroupDetailFragment가 스택에 없으면 직접 navigate
                android.util.Log.w("FinalVoteFragment", "⚠️ popBackStack 실패, 직접 navigate 시도")
                findNavController().navigate(
                    R.id.action_finalVoteFragment_to_groupDetailFragment,
                    Bundle().apply {
                        putString("groupId", groupId)
                    }
                )
            }
            android.util.Log.d("FinalVoteFragment", "✅ GroupDetailFragment로 이동 완료: groupId=$groupId")
        } catch (e: IllegalStateException) {
            android.util.Log.e("FinalVoteFragment", "❌ 네비게이션 실패: Fragment가 FragmentManager에 연결되지 않음", e)
            // Fragment가 이미 제거된 경우, Activity로 돌아가기
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun showWinningPlaceDialog(winningPlace: NearbyPlace) {
        // ⭐ Fragment 유효성 확인
        if (!isFragmentValid()) {
            android.util.Log.w("FinalVoteFragment", "⚠️ Fragment가 유효하지 않아 다이얼로그 표시를 건너뜁니다.")
            return
        }
        
        winningPlaceDialog?.dismiss()
        
        val groupId = args.groupId
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // ⭐ Fragment 유효성 재확인 (비동기 작업 중에 Fragment가 destroy될 수 있음)
                if (!isFragmentValid()) {
                    android.util.Log.w("FinalVoteFragment", "⚠️ 비동기 작업 중 Fragment가 유효하지 않게 되었습니다.")
                    return@launch
                }

                val group = groupRepository.getGroupDetail(groupId)
                val groupName = group?.groupName ?: ""
                
                val placeData = mapOf(
                    "placeId" to winningPlace.placeId,
                    "name" to winningPlace.name,
                    "latitude" to winningPlace.latLng.lat,
                    "longitude" to winningPlace.latLng.lng,
                    "address" to (winningPlace.address ?: "")
                )
                
                // ⭐ 원자적 연산: 약속 확정 및 상태 업데이트
                groupRepository.confirmGroupSchedule(
                    context = requireContext(),
                    groupId = groupId,
                    confirmedPlace = placeData,
                    confirmedTime = group?.confirmedTime ?: com.google.firebase.Timestamp.now(),
                    newTitle = groupName
                )
                saveMeetingToLocal(
                    context = requireContext(),
                    groupId = groupId,
                    groupName = groupName,
                    meetingAt = (group?.confirmedTime ?: com.google.firebase.Timestamp.now()).toDate().time,
                    placeName = winningPlace.name ?: ""
                )

                NextMeetingWidgetProvider.requestUpdateAll(requireContext())
                
                // ⭐ Fragment 유효성 최종 확인 (Firestore 작업 완료 후)
                if (!isFragmentValid()) {
                    android.util.Log.w("FinalVoteFragment", "⚠️ Firestore 작업 완료 후 Fragment가 유효하지 않게 되었습니다.")
                    return@launch
                }
                
                android.util.Log.d("FinalVoteFragment", "✅ 모든 상태 업데이트 완료: 약속 확정됨")
                
                // 약속 최종 확정 시 강한 진동 피드백
                com.moyeoyo.app.utils.VibrationHelper.strongVibration(requireContext())
                
                winningPlaceDialog = AlertDialog.Builder(requireContext())
                    .setTitle("최종 약속 장소 확정")
                    .setMessage("최종 약속 장소는 \"${winningPlace.name}\"로 선정되었습니다.")
                    .setPositiveButton("확인") { _, _ ->
                        // ⭐ 다이얼로그 버튼 클릭 시에도 Fragment 유효성 확인
                        if (isFragmentValid()) {
                            safeNavigateToGroupDetail(groupId)
                        } else {
                            android.util.Log.w("FinalVoteFragment", "⚠️ 다이얼로그 확인 버튼 클릭 시 Fragment가 유효하지 않음")
                            // Activity로 돌아가기
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        }
                    }
                    .setCancelable(false)
                    .create()
                
                // ⭐ 다이얼로그 표시 전 최종 확인
                if (isFragmentValid()) {
                    winningPlaceDialog?.show()
                } else {
                    android.util.Log.w("FinalVoteFragment", "⚠️ 다이얼로그 표시 직전 Fragment가 유효하지 않게 되었습니다.")
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteFragment", "❌ 장소 확정 중 오류 발생: ${e.message}", e)
                
                // ⭐ Fragment 유효성 확인 후 Toast 표시
                if (isFragmentValid()) {
                    Toast.makeText(requireContext(), "장소 확정 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

