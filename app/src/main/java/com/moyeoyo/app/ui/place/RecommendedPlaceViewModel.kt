package com.moyeoyo.app.ui.place

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCategory
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.repository.MapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecommendedPlaceViewModel @Inject constructor(
    private val mapRepository: MapRepository
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
    
    private var currentSelectedPlaceId: String? = null

    // 그룹원들의 입력 위치 목록
    private val _groupMembers = MutableLiveData<List<InputLocation>>(emptyList())
    val groupMembers: LiveData<List<InputLocation>> = _groupMembers

    private var centerLocation: LatLngData? = null
    private var groupId: String? = null
    private var userInputLocation: InputLocation? = null

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
}

