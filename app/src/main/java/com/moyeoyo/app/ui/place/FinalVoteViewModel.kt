package com.moyeoyo.app.ui.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCandidate
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoteStatus(
    val completed: Int,
    val total: Int
)

@HiltViewModel
class FinalVoteViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val groupRepository: GroupRepository
) : ViewModel() {

    private val auth = FirebaseAuth.getInstance()

    private val _voteStatus = MutableLiveData<VoteStatus>(VoteStatus(0, 0))
    val voteStatus: LiveData<VoteStatus> = _voteStatus

    private val _voteSuccess = MutableLiveData<Boolean>(false)
    val voteSuccess: LiveData<Boolean> = _voteSuccess
    
    // 승리한 장소
    private val _winningPlace = MutableLiveData<NearbyPlace?>(null)
    val winningPlace: LiveData<NearbyPlace?> = _winningPlace

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    /**
     * 순위별 점수 합산하여 상위 3개 후보를 Firestore에 저장
     */
    fun savePlaceCandidates(groupId: String, rankedPlaces: List<RankedPlace>) {
        viewModelScope.launch {
            try {
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

                // Firestore에 후보 저장 (1차 투표 총점 포함)
                top3.forEach { (placeId, totalScore) ->
                    rankedPlaces.firstOrNull { it.place.placeId == placeId }?.let { rankedPlace ->
                        val candidate = rankedPlace.toPlaceCandidate(firstRoundScore = totalScore)
                        mapRepository.addPlaceCandidate(groupId, candidate)
                    }
                }
            } catch (e: Exception) {
                _error.value = "후보 저장에 실패했습니다: ${e.message}"
            }
        }
    }

    /**
     * 투표 상태 확인 및 승리한 장소 확인
     */
    fun loadVoteStatus(groupId: String) {
        viewModelScope.launch {
            try {
                val candidates = mapRepository.getPlaceCandidates(groupId)
                val group = groupRepository.getGroupById(groupId)
                
                // 그룹 멤버 수 확인
                val totalMembers = group?.memberUids?.size ?: 0
                
                // 각 후보의 투표 수 계산
                val completedVotes = candidates.sumOf { it.voterUids.size }
                
                _voteStatus.value = VoteStatus(completedVotes, totalMembers)
                
                // 모든 멤버가 투표했고, 후보가 있으면 승리한 장소 확인
                if (totalMembers > 0 && completedVotes >= totalMembers && candidates.isNotEmpty()) {
                    // 각 후보의 최종 투표 수 계산
                    val voteCounts = candidates.map { it to it.voterUids.size }
                    val maxVotes = voteCounts.maxOfOrNull { it.second } ?: 0
                    
                    // 가장 많은 투표를 받은 후보들 찾기 (동점 확인)
                    val topVotedCandidates = voteCounts.filter { it.second == maxVotes }
                    
                    val winningCandidate = if (topVotedCandidates.size == 1) {
                        // 단독 1위인 경우
                        topVotedCandidates.first().first
                    } else {
                        // 동점이 발생한 경우: 1차 투표 총점 비교
                        topVotedCandidates.maxByOrNull { it.first.firstRoundScore }?.first
                    }
                    
                    winningCandidate?.let { candidate ->
                        // PlaceCandidate를 NearbyPlace로 변환
                        val latLng = candidate.latLng?.let { 
                            LatLngData(it.latitude, it.longitude) 
                        } ?: LatLngData(0.0, 0.0)
                        
                        val winningPlace = NearbyPlace(
                            placeId = candidate.placeId,
                            name = candidate.name,
                            address = null,
                            latLng = latLng,
                            categories = emptyList(),
                            rating = null,
                            distanceMeters = 0.0
                        )
                        
                        _winningPlace.value = winningPlace
                    }
                }
            } catch (e: Exception) {
                _error.value = "투표 상태를 불러오는데 실패했습니다: ${e.message}"
            }
        }
    }

    /**
     * 최종 투표 제출
     */
    fun submitVote(groupId: String, placeId: String) {
        val uid = auth.currentUser?.uid ?: return
        
        viewModelScope.launch {
            try {
                // 해당 placeId의 candidate 찾기
                val candidates = mapRepository.getPlaceCandidates(groupId)
                val candidate = candidates.firstOrNull { it.placeId == placeId }
                
                if (candidate != null) {
                    mapRepository.votePlaceCandidate(groupId, candidate.id, uid)
                    
                    // 투표 후 상태 다시 확인 (승리한 장소 확인을 위해)
                    loadVoteStatus(groupId)
                    
                    _voteSuccess.value = true
                } else {
                    _error.value = "후보를 찾을 수 없습니다."
                }
            } catch (e: Exception) {
                _error.value = "투표에 실패했습니다: ${e.message}"
            }
        }
    }
}

