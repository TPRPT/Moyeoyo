package com.moyeoyo.app.ui.time

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
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.databinding.FragmentFinalTimeVoteBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FinalTimeVoteFragment : Fragment() {

    private val args: FinalTimeVoteFragmentArgs by navArgs()
    private val groupId: String get() = args.groupId
    private val date: String get() = args.date

    private var _binding: FragmentFinalTimeVoteBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var timeVoteRepository: TimeVoteRepository
    @Inject lateinit var groupRepository: GroupRepository

    private val auth = FirebaseAuth.getInstance()

    private var selectedTime: String? = null
    private val overlappingTimes = mutableListOf<Pair<String, Int>>() // 시간과 투표 수

    private lateinit var adapter: FinalTimeAdapter
    private var winningTimeDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFinalTimeVoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupRecyclerView()
        loadOverlappingTimes()
        observeFinalVotes()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        winningTimeDialog?.dismiss()
        _binding = null
    }

    private fun setupViews() {
        binding.toolbar.setNavigationOnClickListener {
            safeNavigateUp()
        }

        binding.btnSubmitVote.setOnClickListener {
            selectedTime?.let { time ->
                submitFinalVote(time)
            } ?: run {
                Toast.makeText(requireContext(), "시간을 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = FinalTimeAdapter { time ->
            // 선택된 시간 업데이트
            selectedTime = if (selectedTime == time) {
                null // 같은 시간 클릭 시 선택 해제
            } else {
                time
            }

            // 어댑터 업데이트
            adapter.updateSelection(selectedTime)

            // 투표 버튼 표시
            binding.btnSubmitVote.visibility = if (selectedTime != null) View.VISIBLE else View.GONE
        }

        binding.rvFinalTimes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFinalTimes.adapter = adapter
    }

    private fun loadOverlappingTimes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 그룹 정보 가져오기
                val group = groupRepository.getGroupDetail(groupId)
                val memberUids = group?.memberUids ?: emptyList()

                if (memberUids.isEmpty()) {
                    Toast.makeText(requireContext(), "멤버 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    if (isFragmentValid()) {
                        safeNavigateUp()
                    }
                    return@launch
                }

                // 겹치는 시간 가져오기
                val overlapping = timeVoteRepository.getOverlappingTimes(groupId, date, memberUids)

                if (overlapping.isEmpty()) {
                    binding.tvEmptyMessage.visibility = View.VISIBLE
                    binding.rvFinalTimes.visibility = View.GONE
                    binding.btnSubmitVote.visibility = View.GONE
                    Toast.makeText(requireContext(), "모든 멤버가 겹치는 시간이 없습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // 시간과 투표 수를 리스트로 변환 (투표 수 내림차순 정렬)
                overlappingTimes.clear()
                overlappingTimes.addAll(overlapping.toList().sortedByDescending { it.second })

                // 어댑터에 데이터 전달 (날짜 정보 포함)
                adapter.submitList(overlappingTimes.map {
                    FinalTimeItem(it.first, date)
                })

                binding.tvEmptyMessage.visibility = View.GONE
                binding.rvFinalTimes.visibility = View.VISIBLE

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "데이터를 불러오는 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                if (isFragmentValid()) {
                    safeNavigateUp()
                }
            }
        }
    }

    /**
     * 최종 시간 투표를 실시간으로 관찰하여 모든 멤버 투표 완료 시 자동으로 시간 확정
     */
    private fun observeFinalVotes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupDetail(groupId)
                val memberUids = group?.memberUids ?: emptyList()

                if (memberUids.isEmpty()) {
                    return@launch
                }

                timeVoteRepository.observeFinalVotes(groupId, date).collectLatest { finalVotedUsers ->
                    val allVoted = memberUids.all { it in finalVotedUsers }

                    if (allVoted && finalVotedUsers.isNotEmpty()) {
                        android.util.Log.d("FinalTimeVoteFragment", "✅ 모든 멤버 최종 투표 완료! 만장일치 검사 시작.")

                        // ⭐ 핵심 수정: collectLatest 블록 밖에서 실행하여 취소되지 않도록 보장
                        // 별도의 코루틴 스코프에서 실행하여 collectLatest의 취소 동작으로부터 보호
                        launch {
                            try {
                                // ⭐ Fragment 유효성 확인
                                if (!isFragmentValid()) {
                                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ Fragment가 유효하지 않아 만장일치 검사를 건너뜁니다.")
                                    return@launch
                                }

                                // ⭐ 핵심 수정: 만장일치 검사
                                val finalVotes = timeVoteRepository.getFinalVotes(groupId, date)
                                val totalMembers = memberUids.size

                                // 득표 수 계산
                                val voteCounts = finalVotes.groupingBy { it }.eachCount()
                                android.util.Log.d("FinalTimeVoteFragment", "📊 최종 투표 득표 현황: $voteCounts, 전체 멤버 수: $totalMembers")

                                // 만장일치로 선택된 시간 찾기 (득표 수가 전체 멤버 수와 같은 시간)
                                val unanimouslyVotedTime = voteCounts.entries.find { it.value == totalMembers }?.key

                                if (unanimouslyVotedTime != null) {
                                    // [시나리오 1: 만장일치 성공]
                                    android.util.Log.d("FinalTimeVoteFragment", "✅ 만장일치 성공! 최종 시간 확정: $unanimouslyVotedTime")

                                    // ⭐ Fragment 유효성 재확인 (비동기 작업 중에 Fragment가 destroy될 수 있음)
                                    if (!isFragmentValid()) {
                                        android.util.Log.w("FinalTimeVoteFragment", "⚠️ Firestore 작업 전 Fragment 유효성 확인 실패")
                                        return@launch
                                    }

                                    // ⭐ 핵심 수정: 원자적 연산으로 최종 시간 확정 및 그룹 상태 업데이트
                                    val updateResult = groupRepository.confirmFinalTimeAndState(
                                        groupId,
                                        date,
                                        unanimouslyVotedTime
                                    )
                                    
                                    if (updateResult.isSuccess) {
                                        android.util.Log.d("FinalTimeVoteFragment", "✅ 모든 상태 업데이트 완료: LOCATION_INPUT_REQUIRED")

                                        // 시간 확정 시 진동 피드백
                                        com.moyeoyo.app.utils.VibrationHelper.strongVibration(requireContext())

                                        // ⭐ Fragment 유효성 최종 확인 (Firestore 작업 완료 후)
                                        if (isFragmentValid()) {
                                            // 3. 모든 Firestore 작업이 완료된 후에만 다이얼로그 표시
                                            // 이 시점에서는 모든 데이터가 안전하게 저장되었으므로 화면 전환해도 안전함
                                            showWinningTimeDialog(unanimouslyVotedTime)
                                        } else {
                                            android.util.Log.w("FinalTimeVoteFragment", "⚠️ Firestore 작업 완료 후 Fragment가 유효하지 않게 되었습니다.")
                                        }
                                    } else {
                                        val error = updateResult.exceptionOrNull()
                                        android.util.Log.e("FinalTimeVoteFragment", "❌ 시간 확정 실패: ${error?.message}", error)
                                        
                                        // ⭐ Fragment 유효성 확인 후 Toast 표시
                                        if (isFragmentValid()) {
                                            Toast.makeText(requireContext(), "시간 확정에 실패했습니다: ${error?.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    
                                } else {
                                    // [시나리오 2: 만장일치 실패]
                                    android.util.Log.d("FinalTimeVoteFragment", "❌ 만장일치 실패! 득표 현황: $voteCounts")

                                    // ⭐ Fragment 유효성 확인 후 다이얼로그 표시
                                    if (isFragmentValid()) {
                                        // 사용자에게 알리고 투표를 리셋
                                        showVoteFailedDialog()
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FinalTimeVoteFragment", "❌ 시간 확정 중 오류 발생: ${e.message}", e)
                                
                                // ⭐ Fragment 유효성 확인 후 Toast 표시
                                if (isFragmentValid()) {
                                    Toast.makeText(requireContext(), "시간 확정 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalTimeVoteFragment", "최종 투표 관찰 중 오류: ${e.message}", e)
            }
        }
    }

    private fun submitFinalVote(time: String) {
        val uid = auth.currentUser?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 최종 시간 투표 저장
                timeVoteRepository.voteFinalTime(groupId, date, time, uid)

                Toast.makeText(requireContext(), "투표가 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()

                // ⚠️ 실시간 관찰(observeFinalVotes)에서 모든 멤버 투표 완료 시 자동으로 시간 확정
                // 여기서는 투표만 저장하고, observeFinalVotes에서 처리
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "투표 저장 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
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
    private fun safeNavigateUp() {
        if (!isFragmentValid()) {
            android.util.Log.w("FinalTimeVoteFragment", "⚠️ Fragment가 유효하지 않아 네비게이션을 건너뜁니다.")
            return
        }
        
        try {
            if (!findNavController().popBackStack()) {
                android.util.Log.w("FinalTimeVoteFragment", "⚠️ popBackStack 실패, navigateUp 시도")
                findNavController().navigateUp()
            }
        } catch (e: IllegalStateException) {
            android.util.Log.e("FinalTimeVoteFragment", "❌ 네비게이션 실패: Fragment가 FragmentManager에 연결되지 않음", e)
            // Fragment가 이미 제거된 경우, Activity로 돌아가기
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
    }

    private fun showWinningTimeDialog(time: String) {
        // ⭐ Fragment 유효성 확인
        if (!isFragmentValid()) {
            android.util.Log.w("FinalTimeVoteFragment", "⚠️ Fragment가 유효하지 않아 다이얼로그 표시를 건너뜁니다.")
            return
        }

        winningTimeDialog?.dismiss()

        viewLifecycleOwner.lifecycleScope.launch {
            // ⭐ Fragment 유효성 재확인 (비동기 작업 중에 Fragment가 destroy될 수 있음)
            if (!isFragmentValid()) {
                android.util.Log.w("FinalTimeVoteFragment", "⚠️ 비동기 작업 중 Fragment가 유효하지 않게 되었습니다.")
                return@launch
            }

            try {
                val group = groupRepository.getGroupDetail(groupId)
                val groupName = group?.groupName ?: ""

                // 날짜 포맷팅
                val dateFormat = java.text.SimpleDateFormat("yyyy년 MM월 dd일 (E)", java.util.Locale.KOREA)
                val dateObj = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).parse(date)
                val formattedDate = dateObj?.let { dateFormat.format(it) } ?: date

                // ⭐ Fragment 유효성 최종 확인
                if (!isFragmentValid()) {
                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ 다이얼로그 생성 전 Fragment 유효성 재확인 실패")
                    return@launch
                }

                winningTimeDialog = AlertDialog.Builder(requireContext())
                    .setTitle("최종 약속 시간 확정")
                    .setMessage("최종 약속 일시는 \"${formattedDate} ${time}\"로 선정되었습니다.\n이제 장소 투표를 진행할 수 있습니다.")
                    .setPositiveButton("확인") { _, _ ->
                        // ⭐ 다이얼로그 버튼 클릭 시에도 Fragment 유효성 확인
                        if (isFragmentValid()) {
                            // GroupDetailFragment로 돌아가기 (확정된 시간이 표시되도록)
                            try {
                                // popBackStack을 사용하여 GroupDetailFragment까지 돌아감
                                // false = GroupDetailFragment는 스택에 유지
                                val popped = findNavController().popBackStack(R.id.groupDetailFragment, false)
                                if (!popped) {
                                    // GroupDetailFragment가 스택에 없으면 직접 navigate
                                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ popBackStack 실패, 직접 navigate 시도")
                                    findNavController().navigate(
                                        R.id.action_finalTimeVoteFragment_to_groupDetailFragment,
                                        Bundle().apply {
                                            putString("groupId", groupId)
                                        }
                                    )
                                }
                                android.util.Log.d("FinalTimeVoteFragment", "✅ GroupDetailFragment로 이동 완료: groupId=$groupId")
                            } catch (e: IllegalStateException) {
                                android.util.Log.e("FinalTimeVoteFragment", "❌ 네비게이션 실패: ${e.message}", e)
                                // Fallback: 뒤로 가기
                                safeNavigateUp()
                            }
                        } else {
                            android.util.Log.w("FinalTimeVoteFragment", "⚠️ 다이얼로그 확인 버튼 클릭 시 Fragment가 유효하지 않음")
                            // Activity로 돌아가기
                            activity?.onBackPressedDispatcher?.onBackPressed()
                        }
                    }
                    .setCancelable(false)
                    .create()

                // ⭐ 다이얼로그 표시 전 최종 확인
                if (isFragmentValid()) {
                    winningTimeDialog?.show()
                } else {
                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ 다이얼로그 표시 전 Fragment 유효성 확인 실패")
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalTimeVoteFragment", "❌ 다이얼로그 생성 중 오류: ${e.message}", e)
            }
        }
    }

    /**
     * 만장일치 실패 시 사용자에게 알리는 다이얼로그
     */
    private fun showVoteFailedDialog() {
        // ⭐ Fragment 유효성 확인
        if (!isFragmentValid()) {
            android.util.Log.w("FinalTimeVoteFragment", "⚠️ Fragment가 유효하지 않아 실패 다이얼로그 표시를 건너뜁니다.")
            return
        }

        try {
            AlertDialog.Builder(requireContext())
                .setTitle("의견 불일치")
                .setMessage("모든 멤버의 의견이 일치하지 않아 시간이 확정되지 못했습니다.\n다시 투표를 진행해주세요.")
                .setPositiveButton("확인") { _, _ ->
                    // ⭐ 다이얼로그 버튼 클릭 시에도 Fragment 유효성 확인
                    if (isFragmentValid()) {
                        // 최종 투표 데이터를 리셋하고 이전 화면으로 돌아가기
                        resetFinalVote()
                    } else {
                        android.util.Log.w("FinalTimeVoteFragment", "⚠️ 다이얼로그 확인 버튼 클릭 시 Fragment가 유효하지 않음")
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            android.util.Log.e("FinalTimeVoteFragment", "❌ 다이얼로그 표시 중 오류: ${e.message}", e)
        }
    }

    /**
     * 최종 투표를 리셋하는 함수
     */
    private fun resetFinalVote() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // ⭐ Fragment 유효성 확인
                if (!isFragmentValid()) {
                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ Fragment가 유효하지 않아 리셋을 건너뜁니다.")
                    return@launch
                }

                // Firestore의 최종 투표 기록 삭제
                timeVoteRepository.clearFinalVotes(groupId, date)

                android.util.Log.d("FinalTimeVoteFragment", "✅ 최종 투표 리셋 완료")

                // ⭐ Fragment Result를 통해 TimeVoteFragment에 리셋 신호 전달
                if (isFragmentValid()) {
                    parentFragmentManager.setFragmentResult(
                        "final_vote_reset",
                        Bundle().apply {
                            putBoolean("should_reset_flag", true)
                        }
                    )
                    android.util.Log.d("FinalTimeVoteFragment", "✅ 리셋 신호를 TimeVoteFragment에 전달")
                    safeNavigateUp()
                } else {
                    android.util.Log.w("FinalTimeVoteFragment", "⚠️ 리셋 완료 후 Fragment가 유효하지 않게 되었습니다.")
                    activity?.onBackPressedDispatcher?.onBackPressed()
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalTimeVoteFragment", "❌ 최종 투표 리셋 실패: ${e.message}", e)
                
                // ⭐ Fragment 유효성 확인 후 Toast 표시
                if (isFragmentValid()) {
                    Toast.makeText(requireContext(), "투표 리셋에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

