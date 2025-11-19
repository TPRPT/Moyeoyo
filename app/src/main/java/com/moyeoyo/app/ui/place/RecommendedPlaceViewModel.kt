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
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecommendedPlaceViewModel @Inject constructor(
    private val mapRepository: MapRepository,
    private val groupRepository: GroupRepository
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
     * 그룹 ID 설정
     */
    fun setGroupId(id: String) {
        groupId = id
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
     */
    fun saveUserRankings(rankedPlaces: List<RankedPlace>) {
        val id = groupId ?: return
        _rankingSaveSuccess.value = false
        
        viewModelScope.launch {
            try {
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "💾 순위 저장 시작 - groupId: $id, 순위 개수: ${rankedPlaces.size}")
                
                mapRepository.saveUserRankings(id, rankedPlaces)
                _rankingSaveSuccess.value = true
                
                android.util.Log.d("RecommendedPlaceViewModel", "✅ 순위 저장 완료")
                
                // 저장 완료 후 hasUserRanking을 true로 설정 (UI 업데이트용 observer 트리거)
                // ⚠️ 중요: 이 시점에 hasUserRanking을 true로 설정하면,
                // Activity의 hasUserRanking observer가 트리거되어 UI가 업데이트됩니다.
                // 하지만 checkAllUsersCompleted()는 여기서만 호출하므로 중복 호출 방지!
                _hasUserRanking.value = true
                
                // 저장 완료 후 충분한 지연을 두고 완료 여부 확인 (Firestore 서버 동기화 시간 확보)
                // Source.SERVER를 사용하더라도 서버에 데이터가 반영되는데 시간이 걸릴 수 있음
                kotlinx.coroutines.delay(1500)
                
                // ⚠️ 핵심: checkAllUsersCompleted()는 오직 여기서만 호출!
                // 다른 곳(observer 등)에서는 절대 호출하지 않음!
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "🔍 내 투표 저장 완료 후 모든 사용자 완료 여부 확인 시작")
                checkAllUsersCompletedWithRetry()
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
     * 모든 사용자가 순위 지정을 완료했는지 확인 (재시도 로직 포함)
     * Firestore 서버 동기화 지연을 고려하여 최대 5회까지 재시도
     * 각 재시도마다 실제 그룹 멤버 수와 완료된 사용자 수를 비교하여 정확성 보장
     */
    private fun checkAllUsersCompletedWithRetry(maxRetries: Int = 5) {
        val id = groupId ?: return
        
        viewModelScope.launch {
            var retryCount = 0
            var allCompleted = false
            
            while (retryCount < maxRetries && !allCompleted) {
                try {
                    val group = groupRepository.getGroupById(id)
                    val totalMembers = group?.memberUids?.size ?: 0
                    val completedCount = mapRepository.getCompletedRankingCount(id)
                    
                    android.util.Log.d("RecommendedPlaceViewModel", 
                        "🔍 완료 확인 (시도 ${retryCount + 1}/$maxRetries) - 총 멤버: $totalMembers, 완료된 수: $completedCount, groupId: $id")
                    
                    // ⚠️ 중요: 완료된 수가 총 멤버 수와 정확히 일치해야만 완료로 판단
                    // completedCount >= totalMembers는 위험할 수 있음 (이전 세션 데이터 포함 가능)
                    val countMatches = totalMembers > 0 && completedCount == totalMembers
                    
                    if (countMatches) {
                        // 모든 사용자가 완료되었는지 한 번 더 확인 (정확성 보장)
                        // 실제로 각 멤버의 순위 데이터가 존재하는지 확인
                        val allRankings = mapRepository.getAllUserRankings(id)
                        val actualCompletedCount = allRankings.values.count { it.isNotEmpty() }
                        
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "🔍 실제 데이터 확인 - 문서 수: $completedCount, 실제 완료: $actualCompletedCount, 총 멤버: $totalMembers")
                        
                        if (actualCompletedCount == totalMembers) {
                            allCompleted = true
                            _allUsersCompleted.value = true
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "✅ 모든 사용자 완료 여부: true (시도 ${retryCount + 1}/$maxRetries, 실제 완료: $actualCompletedCount/$totalMembers)")
                            return@launch
                        } else {
                            android.util.Log.d("RecommendedPlaceViewModel", 
                                "⏸️ 문서 수는 일치하지만 실제 데이터 확인 실패 - 실제 완료: $actualCompletedCount/$totalMembers")
                        }
                    } else {
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "⏸️ 완료 수 불일치 - 완료: $completedCount, 총 멤버: $totalMembers")
                    }
                    
                    // 아직 완료되지 않았고 재시도 가능하면 잠시 대기 후 재시도
                    if (retryCount < maxRetries - 1) {
                        // 재시도 전에 지연 시간 증가 (지수 백오프)
                        val delayMs = 800L * (retryCount + 1)
                        android.util.Log.d("RecommendedPlaceViewModel", 
                            "⏳ 모든 사용자 미완료 - ${delayMs}ms 후 재시도 (${retryCount + 1}/$maxRetries)")
                        kotlinx.coroutines.delay(delayMs)
                    }
                    
                    retryCount++
                } catch (e: Exception) {
                    android.util.Log.e("RecommendedPlaceViewModel", 
                        "❌ 완료 상태 확인 실패 (시도 ${retryCount + 1}/$maxRetries): ${e.message}", e)
                    
                    if (retryCount < maxRetries - 1) {
                        kotlinx.coroutines.delay(800L * (retryCount + 1))
                        retryCount++
                    } else {
                        _error.value = "완료 상태 확인에 실패했습니다: ${e.message}"
                        _allUsersCompleted.value = false
                        return@launch
                    }
                }
            }
            
            // 모든 재시도 후에도 완료되지 않음
            if (!allCompleted) {
                _allUsersCompleted.value = false
                android.util.Log.d("RecommendedPlaceViewModel", 
                    "⏸️ 아직 모든 사용자가 완료하지 않음 (최대 재시도 횟수 도달)")
            }
        }
    }
}

