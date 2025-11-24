package com.moyeoyo.app.ui.time

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.databinding.ActivityFinalTimeVoteBinding
import com.moyeoyo.app.ui.groups.GroupDetailActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FinalTimeVoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinalTimeVoteBinding
    
    @Inject
    lateinit var timeVoteRepository: TimeVoteRepository
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private val auth = FirebaseAuth.getInstance()
    
    private lateinit var groupId: String
    private lateinit var date: String
    private var selectedTime: String? = null
    private val overlappingTimes = mutableListOf<Pair<String, Int>>() // 시간과 투표 수
    
    private lateinit var adapter: FinalTimeAdapter
    private var winningTimeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinalTimeVoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("groupId") ?: run {
            Toast.makeText(this, "그룹 ID가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        date = intent.getStringExtra("date") ?: run {
            Toast.makeText(this, "날짜가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupViews()
        setupRecyclerView()
        loadOverlappingTimes()
        observeFinalVotes()
    }

    private fun setupViews() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnSubmitVote.setOnClickListener {
            selectedTime?.let { time ->
                submitFinalVote(time)
            } ?: run {
                Toast.makeText(this, "시간을 선택해주세요.", Toast.LENGTH_SHORT).show()
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
        
        binding.rvFinalTimes.layoutManager = LinearLayoutManager(this)
        binding.rvFinalTimes.adapter = adapter
    }

    private fun loadOverlappingTimes() {
        lifecycleScope.launch {
            try {
                // 그룹 정보 가져오기
                val group = groupRepository.getGroupDetail(groupId)
                val memberUids = group?.memberUids ?: emptyList()
                
                if (memberUids.isEmpty()) {
                    Toast.makeText(this@FinalTimeVoteActivity, "멤버 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                
                // 겹치는 시간 가져오기
                val overlapping = timeVoteRepository.getOverlappingTimes(groupId, date, memberUids)
                
                if (overlapping.isEmpty()) {
                    binding.tvEmptyMessage.visibility = View.VISIBLE
                    binding.rvFinalTimes.visibility = View.GONE
                    binding.btnSubmitVote.visibility = View.GONE
                    Toast.makeText(this@FinalTimeVoteActivity, "모든 멤버가 겹치는 시간이 없습니다.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                
                // 시간과 투표 수를 리스트로 변환 (투표 수 내림차순 정렬)
                overlappingTimes.clear()
                overlappingTimes.addAll(overlapping.toList().sortedByDescending { it.second })
                
                // 어댑터에 데이터 전달 (날짜 정보 포함)
                adapter.submitList(overlappingTimes.map { 
                    com.moyeoyo.app.ui.time.FinalTimeItem(it.first, date) 
                })
                
                binding.tvEmptyMessage.visibility = View.GONE
                binding.rvFinalTimes.visibility = View.VISIBLE
                
            } catch (e: Exception) {
                Toast.makeText(this@FinalTimeVoteActivity, "데이터를 불러오는 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * 최종 시간 투표를 실시간으로 관찰하여 모든 멤버 투표 완료 시 자동으로 시간 확정
     */
    private fun observeFinalVotes() {
        lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupDetail(groupId)
                val memberUids = group?.memberUids ?: emptyList()
                
                if (memberUids.isEmpty()) {
                    return@launch
                }
                
                timeVoteRepository.observeFinalVotes(groupId, date).collectLatest { finalVotedUsers ->
                    val allVoted = memberUids.all { it in finalVotedUsers }
                    
                    if (allVoted && finalVotedUsers.isNotEmpty()) {
                        android.util.Log.d("FinalTimeVoteActivity", "✅ 모든 멤버 최종 투표 완료! 만장일치 검사 시작.")
                        
                        // ⭐ 핵심 수정: 만장일치 검사
                        val finalVotes = timeVoteRepository.getFinalVotes(groupId, date)
                        val totalMembers = memberUids.size
                        
                        // 득표 수 계산
                        val voteCounts = finalVotes.groupingBy { it }.eachCount()
                        android.util.Log.d("FinalTimeVoteActivity", "📊 최종 투표 득표 현황: $voteCounts, 전체 멤버 수: $totalMembers")
                        
                        // 만장일치로 선택된 시간 찾기 (득표 수가 전체 멤버 수와 같은 시간)
                        val unanimouslyVotedTime = voteCounts.entries.find { it.value == totalMembers }?.key
                        
                        if (unanimouslyVotedTime != null) {
                            // [시나리오 1: 만장일치 성공]
                            android.util.Log.d("FinalTimeVoteActivity", "✅ 만장일치 성공! 최종 시간 확정: $unanimouslyVotedTime")
                            
                            // 최종 시간 확정
                            val success = groupRepository.setFinalTime(groupId, date, unanimouslyVotedTime)
                            
                            if (success) {
                                // 그룹 상태를 LOCATION_INPUT_REQUIRED로 변경
                                groupRepository.updateGroupStatus(groupId, "LOCATION_INPUT_REQUIRED")
                                
                                // 승리한 시간 다이얼로그 표시
                                showWinningTimeDialog(unanimouslyVotedTime)
                            } else {
                                Toast.makeText(this@FinalTimeVoteActivity, "시간 확정에 실패했습니다.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            // [시나리오 2: 만장일치 실패]
                            android.util.Log.d("FinalTimeVoteActivity", "❌ 만장일치 실패! 득표 현황: $voteCounts")
                            
                            // 사용자에게 알리고 투표를 리셋
                            showVoteFailedDialog()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalTimeVoteActivity", "최종 투표 관찰 중 오류: ${e.message}", e)
            }
        }
    }

    private fun submitFinalVote(time: String) {
        val uid = auth.currentUser?.uid ?: return
        
        lifecycleScope.launch {
            try {
                // 최종 시간 투표 저장
                timeVoteRepository.voteFinalTime(groupId, date, time, uid)
                
                Toast.makeText(this@FinalTimeVoteActivity, "투표가 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
                
                // ⚠️ 실시간 관찰(observeFinalVotes)에서 모든 멤버 투표 완료 시 자동으로 시간 확정
                // 여기서는 투표만 저장하고, observeFinalVotes에서 처리
            } catch (e: Exception) {
                Toast.makeText(this@FinalTimeVoteActivity, "투표 저장 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWinningTimeDialog(time: String) {
        winningTimeDialog?.dismiss()
        
        lifecycleScope.launch {
            val group = groupRepository.getGroupDetail(groupId)
            val groupName = group?.groupName ?: ""
            
            // 날짜 포맷팅
            val dateFormat = java.text.SimpleDateFormat("yyyy년 MM월 dd일 (E)", java.util.Locale.KOREA)
            val dateObj = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA).parse(date)
            val formattedDate = dateObj?.let { dateFormat.format(it) } ?: date
            
            winningTimeDialog = AlertDialog.Builder(this@FinalTimeVoteActivity)
                .setTitle("최종 약속 시간 확정")
                .setMessage("최종 약속 일시는 \"${formattedDate} ${time}\"로 선정되었습니다.\n이제 장소 투표를 진행할 수 있습니다.")
                .setPositiveButton("확인") { _, _ ->
                    // GroupDetailActivity로 이동
                    val intent = Intent(this@FinalTimeVoteActivity, GroupDetailActivity::class.java).apply {
                        putExtra("GROUP_ID", groupId)
                        putExtra("GROUP_NAME", groupName)
                    }
                    startActivity(intent)
                    finish()
                }
                .setCancelable(false)
                .create()
            
            winningTimeDialog?.show()
        }
    }

    /**
     * 만장일치 실패 시 사용자에게 알리는 다이얼로그
     */
    private fun showVoteFailedDialog() {
        AlertDialog.Builder(this)
            .setTitle("의견 불일치")
            .setMessage("모든 멤버의 의견이 일치하지 않아 시간이 확정되지 못했습니다.\n다시 투표를 진행해주세요.")
            .setPositiveButton("확인") { _, _ ->
                // 최종 투표 데이터를 리셋하고 이전 화면으로 돌아가기
                resetFinalVote()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 최종 투표를 리셋하는 함수
     */
    private fun resetFinalVote() {
        lifecycleScope.launch {
            try {
                // Firestore의 최종 투표 기록 삭제
                timeVoteRepository.clearFinalVotes(groupId, date)
                
                android.util.Log.d("FinalTimeVoteActivity", "✅ 최종 투표 리셋 완료")
                
                // Activity를 닫아 이전 화면으로 돌아가기
                finish()
            } catch (e: Exception) {
                android.util.Log.e("FinalTimeVoteActivity", "❌ 최종 투표 리셋 실패: ${e.message}", e)
                Toast.makeText(this@FinalTimeVoteActivity, "투표 리셋에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        winningTimeDialog?.dismiss()
    }
}

