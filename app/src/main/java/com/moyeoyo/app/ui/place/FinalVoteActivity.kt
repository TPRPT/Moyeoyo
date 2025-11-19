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
import com.moyeoyo.app.map.MidpointActivity
import com.moyeoyo.app.ui.place.FinalCandidate
import com.moyeoyo.app.ui.place.RankedPlaceParcelable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class FinalVoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFinalVoteBinding
    private val viewModel: FinalVoteViewModel by viewModels()
    
    private lateinit var adapter: FinalCandidateAdapter
    private val finalCandidates = mutableListOf<FinalCandidate>()
    private var selectedCandidate: FinalCandidate? = null
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinalVoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val groupId = intent.getStringExtra("groupId") ?: ""

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
                    "✅ 투표 완료 - 투표 상태 다시 확인 시작")
                val groupId = intent.getStringExtra("groupId") ?: ""
                // 투표 완료 후 상태 확인 (승리한 장소 확인을 위해)
                viewModel.loadVoteStatus(groupId)
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
        
        // 저장 완료 후 투표 상태 확인
        viewModel.saveComplete.observe(this) { saved ->
            if (saved) {
                val groupId = intent.getStringExtra("groupId") ?: ""
                viewModel.loadVoteStatus(groupId)
            }
        }
    }
    
    private fun showWinningPlaceDialog(winningPlace: NearbyPlace) {
        val groupId = intent.getStringExtra("groupId") ?: ""
        
        AlertDialog.Builder(this)
            .setTitle("최종 약속 장소 확정")
            .setMessage("최종 약속 장소는 \"${winningPlace.name}\"로 선정되었습니다.\n중간값 계산 화면으로 이동하여 소요시간을 확인하시겠습니까?")
            .setPositiveButton("확인") { _, _ ->
                // 중간값 계산 화면으로 이동하며 승리한 장소 전달
                val intent = Intent(this, MidpointActivity::class.java).apply {
                    putExtra("groupId", groupId)
                    putExtra("selectedPlaceId", winningPlace.placeId)
                    putExtra("selectedPlaceName", winningPlace.name)
                    putExtra("selectedPlaceLat", winningPlace.latLng.lat)
                    putExtra("selectedPlaceLng", winningPlace.latLng.lng)
                }
                startActivity(intent)
                finish()
            }
            .setCancelable(false)
            .show()
    }

}

