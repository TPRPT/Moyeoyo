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
                
                // 어댑터에 데이터 전달
                adapter.submitList(overlappingTimes.map { it.first })
                
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
                        android.util.Log.d("FinalTimeVoteActivity", "✅ 모든 멤버 최종 투표 완료! 시간 확정합니다.")
                        
                        // 최종 시간 가져오기: 최종 투표에서 가장 많이 선택된 시간
                        val finalVotes = timeVoteRepository.getFinalVotes(groupId, date)
                        val finalTime = if (finalVotes.isNotEmpty()) {
                            // 최종 투표에서 가장 많이 선택된 시간 찾기
                            val voteCounts = finalVotes.groupingBy { it }.eachCount()
                            voteCounts.maxByOrNull { it.value }?.key ?: overlappingTimes.firstOrNull()?.first
                        } else {
                            // 최종 투표가 없으면 겹치는 시간 중 첫 번째
                            overlappingTimes.firstOrNull()?.first
                        }
                        
                        if (finalTime == null) {
                            android.util.Log.e("FinalTimeVoteActivity", "최종 시간을 결정할 수 없습니다.")
                            return@collectLatest
                        }
                        
                        // 최종 시간 확정
                        val success = groupRepository.setFinalTime(groupId, date, finalTime)
                        
                        if (success) {
                            // 그룹 상태를 LOCATION_INPUT_REQUIRED로 변경
                            groupRepository.updateGroupStatus(groupId, "LOCATION_INPUT_REQUIRED")
                            
                            // 승리한 시간 다이얼로그 표시
                            showWinningTimeDialog(finalTime)
                        } else {
                            Toast.makeText(this@FinalTimeVoteActivity, "시간 확정에 실패했습니다.", Toast.LENGTH_SHORT).show()
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

    override fun onDestroy() {
        super.onDestroy()
        winningTimeDialog?.dismiss()
    }
}

