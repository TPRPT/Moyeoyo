package com.moyeoyo.app.ui.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCategory
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.Vote
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.data.repository.VoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecommendedPlaceViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val groupRepository: GroupRepository,
    private val voteRepository: VoteRepository
) : ViewModel() {

    private val _places = MutableLiveData<List<NearbyPlace>>(emptyList())
    val places: LiveData<List<NearbyPlace>> = _places

    private val _filteredPlaces = MutableLiveData<List<NearbyPlace>>(emptyList())
    val filteredPlaces: LiveData<List<NearbyPlace>> = _filteredPlaces

    private val _selectedCategory = MutableLiveData<PlaceCategory>(PlaceCategory.ALL)
    val selectedCategory: LiveData<PlaceCategory> = _selectedCategory

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    // placeId -> 대중교통 소요시간 (초) - 한 번에 하나만 표시
    private val _transitTimes = MutableLiveData<Map<String, Int>>(emptyMap())
    val transitTimes: LiveData<Map<String, Int>> = _transitTimes
    
    private val _rankingSaveSuccess = MutableLiveData<Boolean>(false)
    val rankingSaveSuccess: LiveData<Boolean> = _rankingSaveSuccess
    
    private val _allUsersCompleted = MutableLiveData<Boolean>(false)
    val allUsersCompleted: LiveData<Boolean> = _allUsersCompleted
    
    private var currentSelectedPlaceId: String? = null

    // 그룹원들의 입력 위치 목록
    private val _groupMembers = MutableLiveData<List<InputLocation>>(emptyList())
    val groupMembers: LiveData<List<InputLocation>> = _groupMembers

    private var centerLocation: LatLngData? = null
    private var groupId: String? = null
    private var userInputLocation: InputLocation? = null
    
    private val _hasUserRanking = MutableLiveData<Boolean>(false)
    val hasUserRanking: LiveData<Boolean> = _hasUserRanking

    /**
     * 그룹 ID 설정 및 vote 문서 리스너 시작
     * ⭐ 새로운 구조: vote 문서를 관찰하여 상태 변경 감지
     */
    fun setGroupId(id: String) {
        groupId = id
        
        // ⭐ Activity 시작 시 vote 문서 상태 확인 및 초기화 (이전 테스트 데이터 정리)
        viewModelScope.launch {
            try {
                val voteStatus = voteRepository.getVoteStatus(id)
                if (voteStatus?.status == "FINISHED") {
                    android.util.Log.d("RecommendedPlaceViewModel", 
                        "⚠️ vote 문서가 FINISHED 상태입니다. 이전 테스트 데이터를 초기화합니다.")
                    voteRepository.resetVoteStatus(id)
                    android.util.Log.d("RecommendedPlaceViewModel", 
                        "✅ vote 문서를 RANKING 상태로 초기화했습니다.")
                }
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceViewModel", 
                    "❌ vote 문서 상태 확인 실패: ${e.message}", e)
            }
        }
        
        // vote 문서 실시간 리스너 시작
        startListeningToVoteStatus(id)
    }

    /**
     * ⭐ [역할 축소] 오직 '1차 투표 완료' 여부만 감시하는 단순화된 리스너
     * FINAL_VOTING이나 FINISHED 상태는 이 ViewModel의 관심사가 아님
     */
    private fun startListeningToVoteStatus(groupId: String) {
        voteRepository.listenToVoteStatus(groupId)
            .onEach { vote ->
                if (vote == null) return@onEach
                
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "🎧 1차 투표 리스너: status=${vote.status}, rankedUsers=${vote.rankedUsers.size}명")
                
                // ⚠️ FINISHED 상태는 이전 테스트의 데이터일 수 있으므로 무시
                if (vote.status == "FINISHED") {
                    android.util.Log.d("RecommendedPlaceViewModel", 
                        "⏸️ FINISHED 상태 무시 (이전 테스트 데이터)")
                    return@onEach
                }
                
                // ⚠️ FINAL_VOTING 상태 처리: 이미 최종 투표 단계로 넘어간 상태
                // FINAL_VOTING 상태는 이미 모든 사용자가 1차 투표를 완료한 상태
                // ⚠️ 핵심: 현재 사용자가 이미 확정한 경우에만 allUsersCompleted를 true로 설정
                // Firestore에 데이터가 있다고 해서 확정한 것은 아니므로, 현재 세션에서 확정한 경우만 처리
                // ⭐ 그룹 상태가 GROUP_CREATED이면 투표가 시작되지 않은 상태이므로 무시
                if (vote.status == "FINAL_VOTING") {
                    viewModelScope.launch {
                        // ⭐ 그룹 상태 확인: GROUP_CREATED이면 투표 초기화 상태이므로 무시
                        val group = groupRepository.getGroupById(groupId)
                        if (group?.status == "GROUP_CREATED") {
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "⏸️ FINAL_VOTING 상태이지만 그룹 상태가 GROUP_CREATED - 투표 초기화 상태이므로 무시")
                            return@launch
                        }
                        
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "✅ FINAL_VOTING 상태 감지 - rankedUsers: ${vote.rankedUsers.size}명, _hasUserRanking: ${_hasUserRanking.value}")
                        
                        // ⚠️ 핵심: 현재 사용자가 이미 확정한 경우에만 화면 전환 가능
                        // 단순히 Firestore에 데이터가 있다고 해서 확정한 것은 아님
                        // saveUserRankings()에서 _hasUserRanking.value = true 설정 후에만 여기로 올 수 있음
                        if (_hasUserRanking.value == true) {
                            // 한 번 더 확인하여 모든 사용자가 확정했는지 검증
                            val memberUids = group?.memberUids ?: emptyList()
                            val allRanked = voteRepository.checkAllUsersRanked(groupId, memberUids)
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "🔍 FINAL_VOTING 상태 - 모든 사용자 완료 여부 확인: allRanked=$allRanked")
                            
                            // 모든 사용자가 확정한 경우에만 allUsersCompleted 설정
                            if (allRanked) {
                                _allUsersCompleted.value = true
                                android.util.Log.d("RecommendedPlaceViewModel", 
                                    "✅ FINAL_VOTING 상태에서 allUsersCompleted = true 설정 (화면 전환 가능)")
                            } else {
                                android.util.Log.d("RecommendedPlaceViewModel", 
                                    "⏸️ FINAL_VOTING 상태이지만 아직 모든 사용자가 확정하지 않음")
                            }
                        } else {
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "⏸️ FINAL_VOTING 상태이지만 현재 사용자가 아직 확정하지 않음 - 대기")
                        }
                    }
                    return@onEach
                }
                
                // ⭐ 오직 RANKING 상태일 때만 처리
                if (vote.status == "RANKING") {
                    // 현재 사용자가 이미 확정한 경우에만 다른 사용자들의 완료 여부 확인
                    if (_hasUserRanking.value == true) {
                        viewModelScope.launch {
                            val group = groupRepository.getGroupById(groupId)
                            val memberUids = group?.memberUids ?: emptyList()
                            val allRanked = voteRepository.checkAllUsersRanked(groupId, memberUids)
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "🔍 RANKING 상태 확인 (다른 사용자 확정 감지) - allRanked: $allRanked")
                            
                            // ⭐ 리스너는 오직 '모두 완료되었는가' 라는 사실만 전달
                            // 상태를 변경하지 않고, 단순히 완료 여부만 알림
                            _allUsersCompleted.value = allRanked
                            
                            if (allRanked) {
                                android.util.Log.d("RecommendedPlaceViewModel", 
                                    "✅ 모든 사용자 1차 투표 완료 감지!")
                            }
                        }
                    } else {
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "⏸️ RANKING 상태 - 현재 사용자가 아직 확정하지 않음, 확인 생략")
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * 중간값 위치 설정 및 주변 장소 조회
     */
    fun loadNearbyPlaces(center: LatLngData) {
        centerLocation = center
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                // 그룹원들의 입력 위치 가져오기
                val inputLocations = groupId?.let { id ->
                    mapRepository.getInputLocations(id)
                } ?: emptyList()
                
                _groupMembers.value = inputLocations
                
                // 현재 사용자의 입력 위치 찾기
                val currentUserId = mapRepository.currentUserId()
                userInputLocation = inputLocations.find { it.uid == currentUserId }
                
                // 카페, 식당, 술집 카테고리별로 각각 조회
                val allPlaces = mutableListOf<NearbyPlace>()
                
                // 카페 조회
                val cafes = mapRepository.fetchNearbyPlaces(center, 1500, "cafe")
                allPlaces.addAll(cafes)
                
                // 식당 조회
                val restaurants = mapRepository.fetchNearbyPlaces(center, 1500, "restaurant")
                allPlaces.addAll(restaurants)
                
                // 술집 조회
                val bars = mapRepository.fetchNearbyPlaces(center, 1500, "bar")
                allPlaces.addAll(bars)

                // 중복 제거 (placeId 기준)
                val uniquePlaces = allPlaces.distinctBy { it.placeId }
                
                // 평점 순으로 정렬 (평점이 null인 경우 맨 뒤로)
                val sortedPlaces = uniquePlaces.sortedByDescending { it.rating ?: 0.0 }
                
                _places.value = sortedPlaces
                
                // 모든 장소의 소요시간을 미리 계산하지 않음
                // 사용자가 특정 장소를 클릭했을 때만 계산
                
                filterPlacesByCategory(_selectedCategory.value ?: PlaceCategory.ALL)
            } catch (e: Exception) {
                _error.value = "주변 장소를 불러오는데 실패했습니다: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 특정 장소의 대중교통 소요시간 계산 (클릭 시에만 호출)
     * 한 번에 하나의 장소만 소요시간 표시
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
                }
            } catch (e: Exception) {
                // 소요시간 계산 실패는 무시
                currentSelectedPlaceId = null
                _transitTimes.value = emptyMap()
            }
        }
    }

    /**
     * 카테고리별 필터링 및 상위 10개 반환
     */
    fun filterPlacesByCategory(category: PlaceCategory) {
        _selectedCategory.value = category
        val allPlaces = _places.value ?: return

        val filtered = when (category) {
            PlaceCategory.ALL -> allPlaces
            PlaceCategory.CAFE -> allPlaces.filter { place ->
                place.categories.any { it in listOf("cafe", "bakery") }
            }
            PlaceCategory.RESTAURANT -> allPlaces.filter { place ->
                place.categories.any { it in listOf("restaurant", "meal_takeaway", "food") }
            }
            PlaceCategory.BAR -> allPlaces.filter { place ->
                place.categories.any { it in listOf("bar", "night_club") }
            }
            PlaceCategory.DESSERT -> allPlaces.filter { place ->
                place.categories.any { it in listOf("cafe", "bakery", "dessert") }
            }
        }

        // 평점 높은 순으로 정렬 후 상위 10개만
        val sorted = filtered.sortedByDescending { it.rating ?: 0.0 }.take(10)
        _filteredPlaces.value = sorted
    }

    /**
     * 사용자별 순위 지정 저장
     * ⭐ 책임 분리: 이 함수는 '저장'만 수행하고, '확인'은 리스너에게 완전히 위임
     */
    fun saveUserRankings(rankedPlaces: List<RankedPlace>) {
        val id = groupId ?: return
        val uid = mapRepository.currentUserId() ?: return
        _rankingSaveSuccess.value = false
        
        viewModelScope.launch {
            try {
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "💾 순위 저장 시작 - groupId: $id, 순위 개수: ${rankedPlaces.size}")
                
                // 1. 사용자별 순위 지정을 userRankings에 저장
                mapRepository.saveUserRankings(id, rankedPlaces)
                
                // 2. vote 문서의 rankedUsers 배열에 현재 사용자 추가
                // ⚠️ 이 변경이 리스너를 트리거하여 자동으로 완료 여부를 확인함
                voteRepository.addUserToRankedList(id, uid)
                
                // 3. UI 업데이트를 위한 상태 설정
                _hasUserRanking.value = true
                _rankingSaveSuccess.value = true
                
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "✅ 순위 저장 및 rankedUsers 업데이트 완료. 리스너가 상태를 확인할 것임.")
                
                // 4. 저장 후 즉시 vote 문서 상태 확인 (리스너가 즉시 반응하지 않을 수 있으므로)
                // ⚠️ 단, 역할 분리를 유지하기 위해 간단한 확인만 수행
                // ⚠️ 핵심: 현재 사용자가 확정한 상태(_hasUserRanking.value == true)에서만 확인
                kotlinx.coroutines.delay(800) // Firestore 동기화 시간 확보
                
                // ⚠️ 핵심: 현재 사용자가 확정한 상태에서만 확인
                // _hasUserRanking.value == true이어야 이 함수가 실행되는 것이 보장되지만,
                // 혹시 모를 상황을 대비하여 명시적으로 확인
                if (_hasUserRanking.value == true) {
                    val currentVoteStatus = voteRepository.getVoteStatus(id)
                    if (currentVoteStatus != null) {
                        val group = groupRepository.getGroupById(id)
                        val memberUids = group?.memberUids ?: emptyList()
                        val allRanked = voteRepository.checkAllUsersRanked(id, memberUids)
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "🔍 순위 저장 후 즉시 확인 - status: ${currentVoteStatus.status}, allRanked: $allRanked, _hasUserRanking: ${_hasUserRanking.value}")
                        
                        // ⚠️ 핵심: 모든 사용자가 완료했고, 현재 사용자도 확정한 경우에만 allUsersCompleted 설정
                        if (allRanked && _hasUserRanking.value == true) {
                            _allUsersCompleted.value = true
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "✅ 순위 저장 후 즉시 확인 - 모든 사용자 완료! allUsersCompleted = true")
                        } else {
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "⏸️ 순위 저장 후 확인 - 아직 모든 사용자가 완료하지 않음 또는 현재 사용자가 확정하지 않음")
                        }
                    }
                } else {
                    android.util.Log.d("RecommendedPlaceViewModel", 
                        "⏸️ 순위 저장 후 확인 생략 - 현재 사용자가 아직 확정하지 않음 (_hasUserRanking: ${_hasUserRanking.value})")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceViewModel", 
                    "❌ 순위 저장 실패: ${e.message}", e)
                _error.value = "순위 저장에 실패했습니다: ${e.message}"
                _rankingSaveSuccess.value = false
            }
        }
    }

    /**
     * 현재 사용자가 이미 순위를 확정했는지 확인
     */
    fun checkUserRankingStatus() {
        val id = groupId ?: return
        val uid = mapRepository.currentUserId() ?: return
        
        viewModelScope.launch {
            try {
                // Source.SERVER를 사용하여 서버에서 직접 가져오기 (캐시 무시)
                val userRankings = mapRepository.getUserRankings(
                    id, 
                    uid, 
                    com.google.firebase.firestore.Source.SERVER
                )
                _hasUserRanking.value = userRankings.isNotEmpty()
                
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "🔍 현재 사용자 순위 확인 - uid: $uid, 순위 개수: ${userRankings.size}, hasRanking: ${_hasUserRanking.value}")
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceViewModel", 
                    "❌ 사용자 순위 확인 실패: ${e.message}", e)
                _hasUserRanking.value = false
            }
        }
    }

    /**
     * 모든 사용자가 순위 지정을 완료했는지 확인
     * ⚠️ Deprecated: 이 함수는 더 이상 사용되지 않습니다.
     * 대신 checkAllUsersCompletedWithRetry()를 사용하세요.
     * checkAllUsersCompleted()는 오직 saveUserRankings() 내부에서만 호출되어야 합니다.
     */
    @Deprecated("Use checkAllUsersCompletedWithRetry() instead")
    fun checkAllUsersCompleted() {
        val id = groupId ?: return
        
        viewModelScope.launch {
            try {
                val group = groupRepository.getGroupById(id)
                val totalMembers = group?.memberUids?.size ?: 0
                val completedCount = mapRepository.getCompletedRankingCount(id)
                
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "🔍 완료 확인 - 총 멤버: $totalMembers, 완료된 수: $completedCount, groupId: $id")
                
                _allUsersCompleted.value = totalMembers > 0 && completedCount >= totalMembers
                
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "✅ 모든 사용자 완료 여부: ${_allUsersCompleted.value}")
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceViewModel", 
                    "❌ 완료 상태 확인 실패: ${e.message}", e)
                _error.value = "완료 상태 확인에 실패했습니다: ${e.message}"
                _allUsersCompleted.value = false
            }
        }
    }

    /**
     * ⚠️ 삭제됨: checkAllUsersRankedWithRetry()
     * 
     * 이 함수는 saveUserRankings()에서 호출되어 경쟁 조건을 발생시켰습니다.
     * 이제 완료 여부 확인은 startListeningToVoteStatus() 리스너에서만 처리됩니다.
     * 
     * 역할 분리:
     * - saveUserRankings(): 저장만 수행
     * - startListeningToVoteStatus(): 감시만 수행
     */
}

