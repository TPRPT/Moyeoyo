package com.moyeoyo.app.ui.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import android.location.Location
import com.google.firebase.firestore.GeoPoint
import com.moyeoyo.app.data.model.FinalCandidateData
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCandidate
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.Vote
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.data.repository.VoteRepository
import com.moyeoyo.app.ui.place.FinalCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VoteStatus(
    val completed: Int,
    val total: Int
)

@HiltViewModel
class FinalVoteViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val groupRepository: GroupRepository,
    private val voteRepository: VoteRepository
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
     * 투표 상태 확인 (UI 표시용)
     * ⚠️ 승리한 장소 결정은 submitVote에서 처리하므로 여기서는 상태만 업데이트
     */
    fun loadVoteStatus(groupId: String) {
        viewModelScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                val totalMembers = group?.memberUids?.size ?: 0
                
                // UI 표시용 투표 상태 (placeCandidates에서 집계)
                val candidates = mapRepository.getPlaceCandidates(groupId)
                val completedVotes = candidates.sumOf { it.voterUids.size }
                _voteStatus.value = VoteStatus(completedVotes, totalMembers)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "🔍 투표 상태 확인 (UI 업데이트) - groupId: $groupId, totalMembers: $totalMembers, completedVotes: $completedVotes")
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteViewModel", 
                    "❌ 투표 상태 확인 실패: ${e.message}", e)
                _error.value = "투표 상태를 불러오는데 실패했습니다: ${e.message}"
            }
        }
    }
    
    /**
     * 승리한 장소 결정 (내 투표 후에만 호출)
     * ⭐ 핵심: 내가 투표한 후에만 이 함수를 호출하여 승리한 장소를 결정
     */
    private suspend fun determineWinner(groupId: String) {
        try {
            val group = groupRepository.getGroupById(groupId)
            val totalMembers = group?.memberUids?.size ?: 0
            
            // ⭐ vote 문서의 finalVotedUsers 배열 확인 (Single Source of Truth)
            val memberUids = group?.memberUids ?: emptyList()
            val allFinalVoted = voteRepository.checkAllUsersFinalVoted(groupId, memberUids)
            
            android.util.Log.d("FinalVoteViewModel", 
                "🔍 승리 장소 결정 확인 - groupId: $groupId, totalMembers: $totalMembers, allFinalVoted: $allFinalVoted")
            
            // 모든 멤버가 최종 투표를 완료했고, 후보가 있으면 승리한 장소 확인
            if (!allFinalVoted) {
                android.util.Log.d("FinalVoteViewModel", 
                    "⏸️ 아직 모든 사용자가 최종 투표를 완료하지 않음 - 승리 장소 결정 대기")
                return
            }
            
            val candidates = mapRepository.getPlaceCandidates(groupId)
            if (candidates.isEmpty()) {
                android.util.Log.e("FinalVoteViewModel", 
                    "❌ 후보 목록이 비어있습니다.")
                return
            }
            
            android.util.Log.d("FinalVoteViewModel", 
                "🏆 모든 사용자 최종 투표 완료! 승리한 장소 결정 시작")
            
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
                // ⭐ vote 문서에 승리한 장소 저장 및 상태를 FINISHED로 변경
                voteRepository.setWinningPlace(groupId, candidate.placeId, candidate.name)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "✅ 승리한 장소 결정: ${candidate.name} (placeId: ${candidate.placeId})")
                
                // ⭐ vote 문서의 finalCandidates에서 승리한 장소의 상세 정보 가져오기 (주소 포함)
                val voteStatus = voteRepository.getVoteStatus(groupId)
                val finalCandidate = voteStatus?.finalCandidates?.firstOrNull { 
                    it.placeId == candidate.placeId 
                }
                
                // PlaceCandidate를 NearbyPlace로 변환하여 UI에 표시
                        val latLng = candidate.latLng?.let { 
                            LatLngData(it.latitude, it.longitude) 
                        } ?: LatLngData(0.0, 0.0)
                        
                        val winningPlace = NearbyPlace(
                            placeId = candidate.placeId,
                            name = candidate.name,
                            address = finalCandidate?.address, // ⭐ finalCandidates에서 주소 가져오기
                            latLng = latLng,
                            categories = finalCandidate?.categories ?: emptyList(),
                            rating = finalCandidate?.rating,
                    distanceMeters = userInputLocation?.let { inputLoc ->
                        calculateDistanceMeters(
                            inputLoc.latLng,
                            latLng
                        )
                    } ?: 0.0
                )
                
                _winningPlace.value = winningPlace
                // ⚠️ 대중교통 시간 계산은 MidpointActivity에서 자동으로 처리됨 (selectNearbyPlace 호출 시)
            }
        } catch (e: Exception) {
            android.util.Log.e("FinalVoteViewModel", 
                "❌ 승리 장소 결정 실패: ${e.message}", e)
            _error.value = "승리 장소를 결정하는데 실패했습니다: ${e.message}"
        }
    }

    /**
     * vote 문서 실시간 리스너 시작
     * ⭐ 새로운 구조: vote 문서를 관찰하여 finalCandidates를 가져오고 상태 변경 감지
     */
    fun startListeningToVoteStatus(groupId: String) {
        viewModelScope.launch {
            // 현재 사용자의 입력 위치 가져오기 (거리 계산용)
            val inputLocations = mapRepository.getInputLocations(groupId)
            val currentUserId = mapRepository.currentUserId()
            userInputLocation = inputLocations.find { it.uid == currentUserId }
            
            android.util.Log.d("FinalVoteViewModel", 
                "📍 현재 사용자 입력 위치: ${if (userInputLocation != null) "있음" else "없음"}")
            
            // vote 문서 실시간 리스너 시작
            voteRepository.listenToVoteStatus(groupId)
                .onEach { vote ->
                    if (vote != null) {
                        android.util.Log.d("FinalVoteViewModel", 
                            "🔍 vote 문서 업데이트 - status: ${vote.status}, finalCandidates: ${vote.finalCandidates.size}개")
                        
                        when (vote.status) {
                            "FINAL_VOTING" -> {
                                // finalCandidates를 FinalCandidate로 변환
                                val candidates = vote.finalCandidates.map { candidateData ->
                                    val nearbyPlace = NearbyPlace(
                                        placeId = candidateData.placeId,
                                        name = candidateData.name,
                                        address = candidateData.address,
                                        latLng = LatLngData(
                                            candidateData.latLng.latitude,
                                            candidateData.latLng.longitude
                                        ),
                                        categories = candidateData.categories,
                                        rating = candidateData.rating,
                                        distanceMeters = userInputLocation?.let { inputLoc ->
                                            calculateDistanceMeters(
                                                inputLoc.latLng,
                                                LatLngData(
                                                    candidateData.latLng.latitude,
                                                    candidateData.latLng.longitude
                                                )
                                            )
                                        } ?: 0.0
                                    )
                                    
                                    FinalCandidate(
                                        place = nearbyPlace,
                                        totalScore = candidateData.totalScore,
                                        isSelected = false
                                    )
                                }
                                
                                android.util.Log.d("FinalVoteViewModel", 
                                    "✅ finalCandidates 변환 완료 - 후보 수: ${candidates.size}")
                                
                                _finalCandidates.value = candidates
                            }
                            "FINISHED" -> {
                                // 투표 완료 상태 - 승리한 장소 표시
                                vote.winningPlaceId?.let { placeId ->
                                    vote.winningPlaceName?.let { placeName ->
                                        // ⭐ finalCandidates에서 승리 장소를 찾아서 좌표 정보 가져오기
                                        val winningCandidateData = vote.finalCandidates.firstOrNull { 
                                            it.placeId == placeId 
                                        }
                                        
                                        val latLng = winningCandidateData?.latLng?.let {
                                            LatLngData(it.latitude, it.longitude)
                                        } ?: LatLngData(0.0, 0.0)
                                        
                                        val winningPlace = NearbyPlace(
                                            placeId = placeId,
                                            name = placeName,
                                            address = winningCandidateData?.address,
                                            latLng = latLng,
                                            categories = winningCandidateData?.categories ?: emptyList(),
                                            rating = winningCandidateData?.rating,
                                            distanceMeters = userInputLocation?.let { inputLoc ->
                                                if (latLng.lat != 0.0 && latLng.lng != 0.0) {
                                                    calculateDistanceMeters(
                                                        inputLoc.latLng,
                                                        latLng
                                                    )
                                                } else {
                                                    0.0
                                                }
                                            } ?: 0.0
                        )
                        
                        _winningPlace.value = winningPlace
                        // ⚠️ 대중교통 시간 계산은 MidpointActivity에서 자동으로 처리됨 (selectNearbyPlace 호출 시)
                                    }
                                }
                            }
                        }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    /**
     * 모든 사용자의 순위 지정 불러와서 점수 합산하여 상위 3개 후보 생성 및 저장
     * ⭐ 새로운 구조: vote 문서의 finalCandidates 업데이트 후, vote 문서 리스너가 자동으로 UI 업데이트
     */
    fun loadAllUserRankingsAndCreateCandidates(groupId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("FinalVoteViewModel", 
                    "📥 모든 사용자 순위 불러오기 시작 - groupId: $groupId")
                
                // 현재 사용자의 입력 위치 가져오기 (거리 계산용)
                val inputLocations = mapRepository.getInputLocations(groupId)
                val currentUserId = mapRepository.currentUserId()
                userInputLocation = inputLocations.find { it.uid == currentUserId }
                
                // Firestore에서 모든 사용자의 순위 지정 불러오기 (서버에서 직접 가져오기)
                val allUserRankings = mapRepository.getAllUserRankings(groupId)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📥 모든 사용자 순위 불러오기 완료 - 사용자 수: ${allUserRankings.size}")
                
                // 모든 사용자의 RankedPlace 리스트로 변환
                val allRankedPlaces = allUserRankings.values.flatten()
                
                android.util.Log.d("FinalVoteViewModel", 
                    "📊 총 RankedPlace 수: ${allRankedPlaces.size}")
                
                // 순위 데이터가 비어있으면 아직 순위 지정이 완료되지 않은 상태 (정상적인 상황)
                if (allRankedPlaces.isEmpty()) {
                    android.util.Log.d("FinalVoteViewModel", 
                        "ℹ️ 순위 지정 데이터가 아직 없습니다. 순위 지정이 완료되면 자동으로 후보가 생성됩니다.")
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
                
                // FinalCandidateData 리스트 생성 (vote 문서에 저장할 데이터)
                val finalCandidateDataList = top3.mapNotNull { (placeId, totalScore) ->
                    allRankedPlaces.firstOrNull { it.place.placeId == placeId }?.let { rankedPlace ->
                        FinalCandidateData(
                            placeId = rankedPlace.place.placeId,
                            name = rankedPlace.place.name,
                            latLng = GeoPoint(
                                rankedPlace.place.latLng.lat,
                                rankedPlace.place.latLng.lng
                            ),
                            totalScore = totalScore,
                            categories = rankedPlace.place.categories,
                            rating = rankedPlace.place.rating,
                            address = rankedPlace.place.address
                        )
                    }
                }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "✅ FinalCandidateData 리스트 생성 완료 - 후보 수: ${finalCandidateDataList.size}")
                
                if (finalCandidateDataList.isEmpty()) {
                    android.util.Log.e("FinalVoteViewModel", 
                        "❌ FinalCandidateData 리스트가 비어있습니다")
                    _error.value = "최종 후보를 생성할 수 없습니다."
                    return@launch
                }
                
                // ⭐ vote 문서의 finalCandidates 업데이트 및 상태를 FINAL_VOTING으로 변경
                android.util.Log.d("FinalVoteViewModel", 
                    "💾 vote 문서에 finalCandidates 업데이트 및 상태 변경 시작 - 후보 수: ${finalCandidateDataList.size}")
                
                voteRepository.updateFinalCandidates(groupId, finalCandidateDataList)
                
                android.util.Log.d("FinalVoteViewModel", 
                    "✅ vote 문서 업데이트 완료 - status: FINAL_VOTING")
                
                // 상위 3개 후보를 RankedPlace로 변환하여 Firestore에 저장 (placeCandidates 컬렉션)
                // ⚠️ 참고: 이건 기존 로직 유지 (finalVote에서 투표 수 집계용)
                val top3RankedPlaces = top3.mapNotNull { (placeId, totalScore) ->
                    allRankedPlaces.firstOrNull { it.place.placeId == placeId }
                }
                
                android.util.Log.d("FinalVoteViewModel", 
                    "💾 placeCandidates 컬렉션에 후보 저장 시작 - 후보 수: ${top3RankedPlaces.size}")
                
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
     * ⭐ 핵심: 내 투표 후에만 모든 사용자 완료 여부를 확인하고 승리 장소 결정
     */
    fun submitVote(groupId: String, placeId: String) {
        val uid = auth.currentUser?.uid ?: return
        
        viewModelScope.launch {
            try {
                // 해당 placeId의 candidate 찾기
                val candidates = mapRepository.getPlaceCandidates(groupId)
                val candidate = candidates.firstOrNull { it.placeId == placeId }
                
                if (candidate != null) {
                    // 1. placeCandidates 컬렉션에 투표 기록 (기존 로직 유지)
                    mapRepository.votePlaceCandidate(groupId, candidate.id, uid)
                    
                    // 2. ⭐ vote 문서의 finalVotedUsers 배열에 현재 사용자 추가 (새로운 구조)
                    voteRepository.addUserToFinalVotedList(groupId, uid)
                    
                    android.util.Log.d("FinalVoteViewModel", 
                        "✅ 최종 투표 완료 - placeId: $placeId, uid: $uid")
                    
                    // 3. UI 표시용 투표 상태 업데이트
                    loadVoteStatus(groupId)
                    
                    // 4. ⭐ 내 투표 완료 후 충분한 지연을 두고 모든 사용자 완료 여부 확인 (Firestore 서버 동기화 시간 확보)
                    android.util.Log.d("FinalVoteViewModel", 
                        "🔍 내 투표 저장 완료 후 모든 사용자 투표 완료 여부 확인 시작 (동기화 대기)")
                    kotlinx.coroutines.delay(1500) // Firestore 동기화 시간 확보
                    
                    // 5. ⭐ 핵심: 내 투표 후에만 모든 사용자가 투표했는지 확인하고 승리 장소 결정
                    determineWinner(groupId)
                    
                    _voteSuccess.value = true
                } else {
                    _error.value = "후보를 찾을 수 없습니다."
                }
            } catch (e: Exception) {
                android.util.Log.e("FinalVoteViewModel", 
                    "❌ 최종 투표 실패: ${e.message}", e)
                _error.value = "투표에 실패했습니다: ${e.message}"
            }
        }
    }
}

