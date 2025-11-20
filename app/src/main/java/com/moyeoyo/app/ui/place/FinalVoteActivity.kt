package com.moyeoyo.app.ui.place

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.databinding.ActivityFinalVoteBinding
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.place.FinalCandidate
import com.moyeoyo.app.ui.place.RankedPlaceParcelable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FinalVoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinalVoteBinding
    private val viewModel: FinalVoteViewModel by viewModels()
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private lateinit var adapter: FinalCandidateAdapter
    private val finalCandidates = mutableListOf<FinalCandidate>()
    private var selectedCandidate: FinalCandidate? = null
    private val auth = FirebaseAuth.getInstance()
    
    // ⭐ 다이얼로그 메모리 누수 방지를 위한 변수
    private var winningPlaceDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinalVoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groupId = intent.getStringExtra("groupId") ?: ""
        
        // ⭐ 최종 투표 화면 진입 시 그룹 상태를 FINAL_PLACE_VOTE로 변경
        lifecycleScope.launch {
            try {
                val currentGroup = groupRepository.getGroupDetail(groupId)
                if (currentGroup?.status == "PLACE_RANKING") {
                    val success = groupRepository.updateGroupStatus(groupId, "FINAL_PLACE_VOTE")
                    if (success) {
                        android.util.Log.d("FinalVoteActivity", 
                            "✅ 그룹 상태 변경: PLACE_RANKING → FINAL_PLACE_VOTE")
                    } else {
                        android.util.Log.e("FinalVoteActivity", 
                            "❌ 그룹 상태 변경 실패")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteActivity", 
                    "그룹 상태 변경 중 오류: ${e.message}")
            }
        }

        setupViews()
        setupRecyclerView()
        observeViewModel()

        // ⭐ vote 문서 실시간 리스너 시작 (finalCandidates 자동 업데이트)
        viewModel.startListeningToVoteStatus(groupId)

        // Firestore에서 모든 사용자의 순위 지정 불러와서 점수 합산하여 vote 문서 업데이트
        viewModel.loadAllUserRankingsAndCreateCandidates(groupId)
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSubmitVote.setOnClickListener {
            selectedCandidate?.let { candidate ->
                val groupId = intent.getStringExtra("groupId") ?: ""
                viewModel.submitVote(groupId, candidate.place.placeId)
            } ?: run {
                Toast.makeText(this, "장소를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = FinalCandidateAdapter { candidate ->
            // 선택된 후보 업데이트
            selectedCandidate = if (selectedCandidate == candidate) {
                null // 같은 후보 클릭 시 선택 해제
            } else {
                candidate
            }
            
            // 클릭 시 대중교통 시간 계산 (현재 사용자 위치 기준)
            if (selectedCandidate != null) {
                viewModel.calculateTransitTimeForPlace(candidate.place)
            } else {
                // 선택 해제 시 대중교통 시간 제거
                viewModel.calculateTransitTimeForPlace(candidate.place) // 같은 장소를 다시 클릭하면 제거됨
            }
            
            // 어댑터 업데이트
            finalCandidates.forEachIndexed { index, item ->
                finalCandidates[index] = item.copy(isSelected = item == selectedCandidate)
            }
            adapter.submitList(finalCandidates.toList())
            
            // 투표 버튼 표시
            binding.btnSubmitVote.visibility = if (selectedCandidate != null) View.VISIBLE else View.GONE
        }
        
        binding.rvFinalCandidates.layoutManager = LinearLayoutManager(this)
        binding.rvFinalCandidates.adapter = adapter
    }

    private fun observeViewModel() {
        viewModel.finalCandidates.observe(this) { candidates ->
            android.util.Log.d("FinalVoteActivity", 
                "🔍 finalCandidates observer 트리거 - 후보 수: ${candidates.size}")
            
            if (candidates.isEmpty()) {
                android.util.Log.w("FinalVoteActivity", 
                    "⚠️ 최종 후보 목록이 비어있습니다.")
                // 빈 리스트도 어댑터에 전달 (기존 데이터 클리어)
                finalCandidates.clear()
                adapter.submitList(emptyList())
                return@observe
            }
            
            // 로그 출력: 각 후보 정보
            candidates.forEachIndexed { index, candidate ->
                android.util.Log.d("FinalVoteActivity", 
                    "  [${index + 1}] ${candidate.place.name} - 총점: ${candidate.totalScore}점, placeId: ${candidate.place.placeId}")
            }
            
            // 기존 데이터 업데이트
            finalCandidates.clear()
            finalCandidates.addAll(candidates)
            
            android.util.Log.d("FinalVoteActivity", 
                "✅ 어댑터에 ${candidates.size}개 후보 전달 시작")
            
            // ⚠️ 중요: ListAdapter는 새 리스트 인스턴스를 요구하므로 toList()로 복사본 생성
            adapter.submitList(candidates.toList()) {
                // submitList 완료 후 콜백
                android.util.Log.d("FinalVoteActivity", 
                    "✅ RecyclerView 어댑터 업데이트 완료 - 아이템 수: ${adapter.itemCount}")
                
                // RecyclerView가 제대로 업데이트되었는지 확인
                if (adapter.itemCount > 0) {
                    android.util.Log.d("FinalVoteActivity", 
                        "✅ RecyclerView에 ${adapter.itemCount}개 아이템 표시됨")
                } else {
                    android.util.Log.e("FinalVoteActivity", 
                        "❌ RecyclerView 어댑터에 아이템이 없습니다!")
                }
            }
        }

        // 대중교통 소요시간 업데이트
        viewModel.transitTimes.observe(this) { transitTimes ->
            // 소요시간 업데이트 시 어댑터 데이터만 업데이트 (스크롤 위치 유지)
            adapter.updateTransitTimes(transitTimes)
        }

        viewModel.voteStatus.observe(this) { status ->
            binding.tvVoteStatus.text = "투표 상태: ${status.completed}/${status.total} 명 완료"
            
            // 모든 그룹원이 투표했는지 확인 (이미 loadVoteStatus에서 승리한 장소 확인 후 winningPlace LiveData 업데이트)
            // 이 observer는 단순히 투표 상태만 표시
        }

        viewModel.voteSuccess.observe(this) { success ->
            if (success) {
                android.util.Log.d("FinalVoteActivity", 
                    "✅ 투표 완료 - 승리 장소 결정은 ViewModel에서 처리됨")
                // ⚠️ loadVoteStatus 호출 제거 - submitVote에서 이미 처리됨
            }
        }

        viewModel.winningPlace.observe(this) { winningPlace ->
            winningPlace?.let { place ->
                // 승리한 장소가 확인되면 팝업 표시 (중복 방지)
                if (!isFinishing && !isDestroyed) {
                    android.util.Log.d("FinalVoteActivity", 
                        "🏆 승리한 장소 확인: ${place.name}")
                    showWinningPlaceDialog(place)
                }
            }
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
        
        // ⚠️ saveComplete observer에서 loadVoteStatus 호출 제거
        // 이전 테스트의 finalVotedUsers가 남아있어서 잘못된 판단이 발생할 수 있음
        // finalCandidates는 startListeningToVoteStatus에서 자동으로 업데이트됨
        viewModel.saveComplete.observe(this) { saved ->
            if (saved) {
                android.util.Log.d("FinalVoteActivity", 
                    "✅ 후보 저장 완료 - finalCandidates는 vote 문서 리스너에서 자동 업데이트됨")
            }
        }
    }
    
    private fun showWinningPlaceDialog(winningPlace: NearbyPlace) {
        // ⭐ Activity가 종료 중이면 다이얼로그를 띄우지 않음 (메모리 누수 방지)
        if (isFinishing || isDestroyed) {
            return
        }
        
        // ⭐ 기존 다이얼로그가 있으면 먼저 닫기
        winningPlaceDialog?.dismiss()
        
        val groupId = intent.getStringExtra("groupId") ?: ""
        
        lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupDetail(groupId)
                val groupName = group?.groupName ?: ""
                
                // 장소를 그룹의 confirmedPlace에 저장
                val placeData = mapOf(
                    "placeId" to winningPlace.placeId,
                    "name" to winningPlace.name,
                    "latitude" to winningPlace.latLng.lat,
                    "longitude" to winningPlace.latLng.lng,
                    "address" to (winningPlace.address ?: "")
                )
                groupRepository.confirmGroupSchedule(
                    groupId = groupId,
                    confirmedPlace = placeData,
                    confirmedTime = group?.confirmedTime 
                        ?: com.google.firebase.Timestamp.now(),
                    newTitle = groupName
                )
                
                // ⭐ 다이얼로그를 변수에 저장하여 onDestroy에서 닫을 수 있도록 함
                winningPlaceDialog = AlertDialog.Builder(this@FinalVoteActivity)
                    .setTitle("최종 약속 장소 확정")
                    .setMessage("최종 약속 장소는 \"${winningPlace.name}\"로 선정되었습니다.")
                    .setPositiveButton("확인") { _, _ ->
                        // 그룹 디테일 화면으로 이동
                        val intent = Intent(this@FinalVoteActivity, com.moyeoyo.app.ui.groups.GroupDetailActivity::class.java).apply {
                            putExtra("GROUP_ID", groupId)
                            putExtra("GROUP_NAME", groupName)
                        }
                        startActivity(intent)
                        finish()
                    }
                    .setCancelable(false)
                    .create()
                
                winningPlaceDialog?.show()
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteActivity", "그룹 정보 조회 실패: ${e.message}", e)
                // 그룹 이름 없이도 이동
                winningPlaceDialog = AlertDialog.Builder(this@FinalVoteActivity)
                    .setTitle("최종 약속 장소 확정")
                    .setMessage("최종 약속 장소는 \"${winningPlace.name}\"로 선정되었습니다.")
                    .setPositiveButton("확인") { _, _ ->
                        val intent = Intent(this@FinalVoteActivity, com.moyeoyo.app.ui.groups.GroupDetailActivity::class.java).apply {
                            putExtra("GROUP_ID", groupId)
                        }
                        startActivity(intent)
                        finish()
                    }
                    .setCancelable(false)
                    .create()
                
                winningPlaceDialog?.show()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // ⭐ Activity가 파괴될 때 다이얼로그가 열려있다면 반드시 닫아줌 (메모리 누수 방지)
        winningPlaceDialog?.dismiss()
        winningPlaceDialog = null
    }

}

