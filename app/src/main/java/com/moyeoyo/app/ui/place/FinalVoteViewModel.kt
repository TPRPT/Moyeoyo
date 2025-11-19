package com.moyeoyo.app.ui.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import android.location.Location
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCandidate
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.ui.place.FinalCandidate
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

    private val _saveComplete = MutableLiveData<Boolean>(false)
    val saveComplete: LiveData<Boolean> = _saveComplete

    private val _finalCandidates = MutableLiveData<List<FinalCandidate>>(emptyList())
    val finalCandidates: LiveData<List<FinalCandidate>> = _finalCandidates

    // placeId -> 대중교통 소요시간 (초) - 한 번에 하나만 표시
    private val _transitTimes = MutableLiveData<Map<String, Int>>(emptyMap())
    val transitTimes: LiveData<Map<String, Int>> = _transitTimes
    
    private var userInputLocation: InputLocation? = null
    private var currentSelectedPlaceId: String? = null

    /**
     * 순위별 점수 합산하여 상위 3개 후보를 Firestore에 저장
     */
    fun savePlaceCandidates(groupId: String, rankedPlaces: List<RankedPlace>) {
        _saveComplete.value = false
        viewModelScope.launch {
            try {
                // 기존 후보 모두 삭제 (이전 세션 데이터 정리) - 완료될 때까지 대기
                mapRepository.clearPlaceCandidates(groupId)
                
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
                
                // 저장 완료 신호
                _saveComplete.value = true
            } catch (e: Exception) {
                _error.value = "후보 저장에 실패했습니다: ${e.message}"
                _saveComplete.value = false
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
     * 모든 사용자의 순위 지정 불러와서 점수 합산하여 상위 3개 후보 생성 및 저장
     */
    fun loadAllUserRankingsAndCreateCandidates(groupId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("FinalVoteViewModel", 
                    "📥 모든 사용자 순위 불러오기 시작 - groupId: $groupId")
                
                // 현재 사용자의 입력 위치 가져오기 (거리 및 대중교통 시간 계산용)
                val inputLocations = mapRepository.getInputLocations(groupId)
                val currentUserId = mapRepository.currentUserId()
                userInputLocation = inputLocations.find { it.uid == currentUserId }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📍 현재 사용자 입력 위치: ${if (userInputLocation != null) "있음" else "없음"}")
                
                // Firestore에서 모든 사용자의 순위 지정 불러오기 (서버에서 직접 가져오기)
                val allUserRankings = mapRepository.getAllUserRankings(groupId)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📥 모든 사용자 순위 불러오기 완료 - 사용자 수: ${allUserRankings.size}")
                
                // 모든 사용자의 RankedPlace 리스트로 변환
                val allRankedPlaces = allUserRankings.values.flatten()
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📊 총 RankedPlace 수: ${allRankedPlaces.size}")
                
                if (allRankedPlaces.isEmpty()) {
                    android.util.Log.e("FinalVoteViewModel", 
                        "❌ 순위 지정 데이터가 비어있습니다 - allUserRankings: $allUserRankings")
                    _error.value = "순위 지정 데이터를 불러올 수 없습니다. 모든 사용자가 순위를 확정했는지 확인해주세요."
                    return@launch
                }
                
                // placeId별로 점수 합산
                val scoreMap = mutableMapOf<String, Int>()
                allRankedPlaces.forEach { rankedPlace ->
                    val currentScore = scoreMap[rankedPlace.place.placeId] ?: 0
                    scoreMap[rankedPlace.place.placeId] = currentScore + rankedPlace.score
                }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📊 점수 집계 완료 - 장소 수: ${scoreMap.size}")
                
                // 점수 높은 순으로 정렬하여 상위 3개 선택
                val top3 = scoreMap.entries
                    .sortedByDescending { it.value }
                    .take(3)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "🏆 상위 3개 선택 완료 - 장소 수: ${top3.size}")
                
                // FinalCandidate 리스트 생성 (거리 정보만 현재 사용자 기준으로 재계산)
                val candidates = top3.mapNotNull { (placeId, totalScore) ->
                    allRankedPlaces.firstOrNull { it.place.placeId == placeId }?.let { rankedPlace ->
                        // 현재 사용자의 입력 위치를 기준으로 거리만 재계산 (평점, 카테고리는 저장된 데이터 사용)
                        val updatedPlace = userInputLocation?.let { inputLoc ->
                            val distanceMeters = calculateDistanceMeters(
                                inputLoc.latLng,
                                rankedPlace.place.latLng
                            )
                            rankedPlace.place.copy(distanceMeters = distanceMeters)
                        } ?: rankedPlace.place
                        
                        android.util.Log.d("FinalVoteViewModel", 
                            "✅ 후보 생성 - placeId: $placeId, 이름: ${updatedPlace.name}, 총점: $totalScore, 평점: ${updatedPlace.rating}, 거리: ${updatedPlace.distanceMeters}m")
                        FinalCandidate(
                            place = updatedPlace,
                            totalScore = totalScore,
                            isSelected = false
                        )
                    }
                }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "✅ FinalCandidate 리스트 생성 완료 - 후보 수: ${candidates.size}")
                
                if (candidates.isEmpty()) {
                    android.util.Log.e("FinalVoteViewModel", 
                        "❌ FinalCandidate 리스트가 비어있습니다")
                    _error.value = "최종 후보를 생성할 수 없습니다."
                    return@launch
                }
                
                // ⚠️ 중요: LiveData 업데이트는 메인 스레드에서 수행
                // 코루틴이 다른 디스패처에서 실행될 수 있으므로 Main으로 전환
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.util.Log.d("FinalVoteViewModel", 
                        "📤 _finalCandidates LiveData 업데이트 중 - 후보 수: ${candidates.size}")
                    _finalCandidates.value = candidates
                    android.util.Log.d("FinalVoteViewModel", 
                        "✅ _finalCandidates LiveData 업데이트 완료")
                }
                
                // 상위 3개 후보를 RankedPlace로 변환하여 Firestore에 저장
                val top3RankedPlaces = top3.mapNotNull { (placeId, totalScore) ->
                    allRankedPlaces.firstOrNull { it.place.placeId == placeId }
                }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "💾 Firestore에 후보 저장 시작 - 후보 수: ${top3RankedPlaces.size}")
                
                // Firestore에 후보 저장 (완료 후 투표 상태 확인)
                savePlaceCandidates(groupId, top3RankedPlaces)
                
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteViewModel", 
                    "❌ 순위 데이터를 불러오는데 실패했습니다: ${e.message}", e)
                _error.value = "순위 데이터를 불러오는데 실패했습니다: ${e.message}"
            }
        }
    }

    /**
     * 특정 장소에 대한 대중교통 소요시간 계산
     */
    fun calculateTransitTimeForPlace(place: NearbyPlace) {
        val inputLocation = userInputLocation ?: return
        
        // 같은 장소를 다시 클릭하면 제거
        if (currentSelectedPlaceId == place.placeId) {
            currentSelectedPlaceId = null
            _transitTimes.value = emptyMap()
            return
        }
        
        // 새로운 장소 선택
        currentSelectedPlaceId = place.placeId
        
        viewModelScope.launch {
            try {
                val userInput = InputLocation(
                    uid = inputLocation.uid,
                    latLng = inputLocation.latLng,
                    transportMode = TransportMode.TRANSIT
                )

                val results = mapRepository.fetchDistanceMatrix(
                    origins = listOf(userInput),
                    destination = place.latLng
                )

                results.firstOrNull()?.let { result ->
                    // 선택된 장소만 표시 (기존 것 제거)
                    _transitTimes.value = mapOf(place.placeId to result.durationSeconds)
                    android.util.Log.d("FinalVoteViewModel", 
                        "✅ 대중교통 시간 계산 완료 - 장소: ${place.name}, 시간: ${result.durationSeconds}초")
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteViewModel", 
                    "❌ 대중교통 시간 계산 실패: ${e.message}", e)
                // 소요시간 계산 실패는 무시
                currentSelectedPlaceId = null
                _transitTimes.value = emptyMap()
            }
        }
    }

    /**
     * 두 좌표 사이의 거리 계산 (미터 단위)
     */
    private fun calculateDistanceMeters(from: LatLngData, to: LatLngData): Double {
        val result = FloatArray(1)
        Location.distanceBetween(from.lat, from.lng, to.lat, to.lng, result)
        return result.firstOrNull()?.toDouble() ?: 0.0
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

