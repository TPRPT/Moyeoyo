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
        val rankedPlaceParcelables = intent.getParcelableArrayListExtra<RankedPlaceParcelable>("rankedPlaces") ?: arrayListOf()

        setupViews()
        setupRecyclerView()
        observeViewModel()

        // Parcelable을 RankedPlace로 변환
        val rankedPlaces = rankedPlaceParcelables.map { it.toRankedPlace() }

        // 순위별 점수 합산하여 상위 3개 선정
        val top3Candidates = calculateTop3Candidates(rankedPlaces)
        
        finalCandidates.clear()
        finalCandidates.addAll(top3Candidates)
        adapter.submitList(finalCandidates)

        // Firestore에 후보 저장
        viewModel.savePlaceCandidates(groupId, rankedPlaces)

        // 투표 상태 확인
        viewModel.loadVoteStatus(groupId)
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
        viewModel.voteStatus.observe(this) { status ->
            binding.tvVoteStatus.text = "투표 상태: ${status.completed}/${status.total} 명 완료"
        }

        viewModel.voteSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "투표가 완료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.winningPlace.observe(this) { winningPlace ->
            winningPlace?.let { place ->
                // 승리한 장소가 확인되면 팝업 표시 (중복 방지)
                if (!isFinishing) {
                    showWinningPlaceDialog(place)
                }
            }
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
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

    private fun calculateTop3Candidates(rankedPlaces: List<RankedPlace>): List<FinalCandidate> {
        // placeId별로 점수 합산
        val scoreMap = mutableMapOf<String, Int>()
        
        rankedPlaces.forEach { rankedPlace ->
            val currentScore = scoreMap[rankedPlace.place.placeId] ?: 0
            scoreMap[rankedPlace.place.placeId] = currentScore + rankedPlace.score
        }

        // 점수 높은 순으로 정렬하여 상위 3개 선택
        val top3 = scoreMap.entries
            .sortedByDescending { it.value }
            .take(3)

        // FinalCandidate 리스트 생성
        return top3.mapNotNull { (placeId, totalScore) ->
            rankedPlaces.firstOrNull { it.place.placeId == placeId }?.let { rankedPlace ->
                FinalCandidate(
                    place = rankedPlace.place,
                    totalScore = totalScore,
                    isSelected = false
                )
            }
        }
    }
}

