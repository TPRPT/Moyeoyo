package com.moyeoyo.app.ui.groups

import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.ui.place.FinalCandidate
import com.moyeoyo.app.ui.place.FinalCandidateAdapter
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import com.moyeoyo.app.ui.time.FinalTimeAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * GroupDetailActivity의 투표 탭 관련 로직을 처리하는 클래스
 */
class VoteTabHandler(
    private val activity: GroupDetailActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val groupId: String,
    private val groupName: String,
    private val voteViewModel: FinalVoteViewModel,
    private val groupRepository: GroupRepository,
    private val timeVoteRepository: TimeVoteRepository,
    private val auth: FirebaseAuth
) {
    // UI 요소
    private lateinit var recyclerFinalCandidates: RecyclerView
    private lateinit var btnSubmitVote: Button
    private lateinit var finalVoteAdapter: FinalCandidateAdapter
    private val finalCandidates = mutableListOf<FinalCandidate>()
    private var selectedCandidate: FinalCandidate? = null
    
    // 시간 최종투표 관련
    private lateinit var recyclerFinalTimes: RecyclerView
    private lateinit var btnSubmitTimeVote: Button
    private lateinit var finalTimeAdapter: FinalTimeAdapter
    private var selectedTime: String? = null
    private var finalTimeDate: String? = null
    private var layoutFinalTimeVote: View? = null
    private val overlappingTimes = mutableListOf<Pair<String, Int>>()
    
    // 상태
    private var isVoteTabInitialized = false
    private var isTimeVoteInitialized = false
    
    // 콜백
    var onTimeVoteCompleted: ((String, String) -> Unit)? = null
    var onPlaceVoteCompleted: ((NearbyPlace) -> Unit)? = null
    var onHideVoteUI: (() -> Unit)? = null

    fun bindVoteTab(view: View) {
        recyclerFinalCandidates = view.findViewById<RecyclerView>(R.id.rvFinalCandidates)
        btnSubmitVote = view.findViewById<Button>(R.id.btnSubmitVote)
        recyclerFinalCandidates.layoutManager = LinearLayoutManager(activity)

        // 시간 최종 투표 UI
        layoutFinalTimeVote = view.findViewById<View>(R.id.layoutFinalTimeVote)
        recyclerFinalTimes = view.findViewById<RecyclerView>(R.id.rvFinalTimes)
        btnSubmitTimeVote = view.findViewById<Button>(R.id.btnSubmitTimeVote)
        recyclerFinalTimes.layoutManager = LinearLayoutManager(activity)

        // 위치 필터 버튼
        val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)
        btnFilterLocation.setOnClickListener {
            activity.startLocationInput()
        }

        // 시간 버튼
        val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
        btnFilterTime.setOnClickListener {
            startFinalTimeVote()
        }

        // 장소 투표 어댑터 설정
        finalVoteAdapter = FinalCandidateAdapter { candidate ->
            selectedCandidate = if (selectedCandidate == candidate) {
                null
            } else {
                candidate
            }
            
            if (selectedCandidate != null) {
                voteViewModel.calculateTransitTimeForPlace(candidate.place)
            } else {
                voteViewModel.calculateTransitTimeForPlace(candidate.place)
            }
            
            finalCandidates.forEachIndexed { index, item ->
                finalCandidates[index] = item.copy(isSelected = item == selectedCandidate)
            }
            finalVoteAdapter.submitList(finalCandidates.toList())
            btnSubmitVote.visibility = if (selectedCandidate != null) View.VISIBLE else View.GONE
        }

        recyclerFinalCandidates.adapter = finalVoteAdapter

        if (!isVoteTabInitialized) {
            observeVoteViewModel()
            startVoteLoading()
            checkTimeVoteStatus()
            isVoteTabInitialized = true
        }

        btnSubmitVote.setOnClickListener {
            val candidate = selectedCandidate
            if (candidate == null) {
                Toast.makeText(activity, "장소를 선택해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            voteViewModel.submitVote(groupId, candidate.place.placeId)
        }

        btnSubmitTimeVote.setOnClickListener {
            selectedTime?.let { time ->
                submitFinalTimeVote(time)
            } ?: run {
                Toast.makeText(activity, "시간을 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoteLoading() {
        voteViewModel.startListeningToVoteStatus(groupId)
        voteViewModel.loadAllUserRankingsAndCreateCandidates(groupId)
    }

    private fun observeVoteViewModel() {
        voteViewModel.finalCandidates.observe(activity) { candidates ->
            if (candidates.isEmpty()) {
                finalCandidates.clear()
                finalVoteAdapter.submitList(emptyList())
                return@observe
            }
            
            finalCandidates.clear()
            finalCandidates.addAll(candidates)
            finalVoteAdapter.submitList(candidates.toList())
        }

        voteViewModel.transitTimes.observe(activity) { transitTimes ->
            finalVoteAdapter.updateTransitTimes(transitTimes)
        }

        voteViewModel.voteSuccess.observe(activity) { success ->
            if (success) {
                Toast.makeText(activity, "투표를 완료했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        voteViewModel.error.observe(activity) { msg ->
            msg?.let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
        }

        voteViewModel.saveComplete.observe(activity) { saved ->
            if (saved) {
                android.util.Log.d("VoteTabHandler", "✅ 후보 저장 완료")
            }
        }

        voteViewModel.winningPlace.observe(activity) { place ->
            place?.let {
                onPlaceVoteCompleted?.invoke(it)
            }
        }
    }

    private fun checkTimeVoteStatus() {
        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            if (group?.status == "TIME_FINALIZING") {
                startFinalTimeVote()
            }
        }
    }

    private fun startFinalTimeVote() {
        lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                val memberUids = group?.memberUids ?: emptyList()

                if (memberUids.isEmpty()) {
                    Toast.makeText(activity, "멤버 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 현재 주의 모든 날짜 확인
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                
                val datesToCheck = mutableListOf<String>()
                for (i in 0 until 7) {
                    val dateStr = dateFormat.format(calendar.time)
                    datesToCheck.add(dateStr)
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }
                
                // 모든 멤버가 투표한 날짜 찾기
                var dateWithAllVoted: String? = null
                for (dateStr in datesToCheck) {
                    val allVoted = timeVoteRepository.checkAllMembersVoted(groupId, dateStr, memberUids)
                    if (allVoted) {
                        dateWithAllVoted = dateStr
                        break
                    }
                }

                if (dateWithAllVoted == null) {
                    // 아직 모든 멤버가 투표하지 않았으면 TimeVoteActivity로 이동
                    val intent = android.content.Intent(activity, com.moyeoyo.app.ui.time.TimeVoteActivity::class.java).apply {
                        putExtra("groupId", groupId)
                    }
                    activity.startActivity(intent)
                    return@launch
                }

                // 겹치는 시간 가져오기
                val overlapping = timeVoteRepository.getOverlappingTimes(groupId, dateWithAllVoted, memberUids)

                if (overlapping.isEmpty()) {
                    Toast.makeText(activity, "모든 멤버가 겹치는 시간이 없습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // 시간 최종투표 UI 표시
                finalTimeDate = dateWithAllVoted
                showFinalTimeVoteUI(overlapping.toList().sortedByDescending { it.second })
            } catch (e: Exception) {
                Toast.makeText(activity, "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFinalTimeVoteUI(overlappingTimesList: List<Pair<String, Int>>) {
        layoutFinalTimeVote?.visibility = View.VISIBLE
        
        overlappingTimes.clear()
        overlappingTimes.addAll(overlappingTimesList)
        
        finalTimeAdapter = FinalTimeAdapter { time ->
            selectedTime = if (selectedTime == time) {
                null
            } else {
                time
            }
            
            finalTimeAdapter.updateSelection(selectedTime)
            btnSubmitTimeVote.visibility = if (selectedTime != null) View.VISIBLE else View.GONE
        }
        
        recyclerFinalTimes.adapter = finalTimeAdapter
        finalTimeAdapter.submitList(overlappingTimes.map { it.first })
        
        // 최종 투표 섹션 숨기기
        recyclerFinalCandidates.visibility = View.GONE
        btnSubmitVote.visibility = View.GONE

        // 최종 투표 관찰 시작
        if (!isTimeVoteInitialized) {
            observeTimeFinalization()
            isTimeVoteInitialized = true
        }
    }

    private fun submitFinalTimeVote(time: String) {
        val uid = auth.currentUser?.uid ?: return
        finalTimeDate ?: return

        lifecycleScope.launch {
            try {
                timeVoteRepository.voteFinalTime(groupId, finalTimeDate!!, time, uid)
                Toast.makeText(activity, "투표가 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "투표 저장 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeTimeFinalization() {
        finalTimeDate?.let { date ->
            lifecycleScope.launch {
                try {
                    val group = groupRepository.getGroupById(groupId)
                    val memberUids = group?.memberUids ?: emptyList()

                    if (memberUids.isEmpty()) return@launch

                    timeVoteRepository.observeFinalVotes(groupId, date).collectLatest { finalVotedUsers ->
                        val allVoted = memberUids.all { it in finalVotedUsers }

                        if (allVoted && finalVotedUsers.isNotEmpty()) {
                            val finalVotes = timeVoteRepository.getFinalVotes(groupId, date)
                            val finalTime = if (finalVotes.isNotEmpty()) {
                                val voteCounts = finalVotes.groupingBy { it }.eachCount()
                                voteCounts.maxByOrNull { it.value }?.key ?: overlappingTimes.firstOrNull()?.first
                            } else {
                                overlappingTimes.firstOrNull()?.first
                            }

                            if (finalTime == null) {
                                android.util.Log.e("VoteTabHandler", "최종 시간을 결정할 수 없습니다.")
                                return@collectLatest
                            }

                            val success = groupRepository.setFinalTime(groupId, date, finalTime)
                            
                            if (success) {
                                groupRepository.updateGroupStatus(groupId, "LOCATION_INPUT_REQUIRED")
                                showWinningTimeDialog(finalTime, date)
                                onTimeVoteCompleted?.invoke(date, finalTime)
                                
                                // 시간 투표 완료 후 장소 투표 UI 다시 표시
                                layoutFinalTimeVote?.visibility = View.GONE
                                recyclerFinalCandidates.visibility = View.VISIBLE
                                btnSubmitVote.visibility = View.GONE
                            } else {
                                Toast.makeText(activity, "시간 확정에 실패했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoteTabHandler", "최종 투표 관찰 중 오류: ${e.message}", e)
                }
            }
        }
    }

    private fun showWinningTimeDialog(time: String, date: String) {
        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val name = group?.groupName ?: groupName
            
            val dateFormat = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREA)
            val dateObj = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).parse(date)
            val formattedDate = dateObj?.let { dateFormat.format(it) } ?: date
            
            android.app.AlertDialog.Builder(activity)
                .setTitle("최종 약속 시간 확정")
                .setMessage("최종 약속 일시는 \"${formattedDate} ${time}\"로 선정되었습니다.\n이제 장소 투표를 진행할 수 있습니다.")
                .setPositiveButton("확인", null)
                .setCancelable(false)
                .show()
        }
    }

    fun hideAllVoteUI() {
        layoutFinalTimeVote?.visibility = View.GONE
        recyclerFinalCandidates.visibility = View.GONE
        btnSubmitVote.visibility = View.GONE
        btnSubmitTimeVote.visibility = View.GONE
        
        // 초기 버튼 컨테이너도 숨기기
        activity.findViewById<View>(R.id.layoutInitialButtons)?.visibility = View.GONE
        onHideVoteUI?.invoke()
    }
}

