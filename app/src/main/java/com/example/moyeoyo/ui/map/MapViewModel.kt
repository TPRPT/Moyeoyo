package com.example.moyeoyo.ui.map

// MapViewModel: 위치 입력/검색, 중간지점 계산, 거리계산 등
// - UI에서 발생하는 이벤트를 수집하고, Repository를 호출하여 상태(MapState)를 갱신
// - 코루틴을 이용해 비동기 작업 처리

import android.content.Context
import androidx.lifecycle.*
import com.example.moyeoyo.data.model.*
import com.example.moyeoyo.data.repository.MapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MapViewModel(
    private val repo: MapRepository
) : ViewModel() {

    private val _state = MutableLiveData(MapState())
    val state: LiveData<MapState> = _state

    // 상태 업데이트 헬퍼
    // - 기존 상태를 받아 변경된 상태를 생성해 LiveData에 반영
    private fun update(block: (MapState) -> MapState) {
        _state.value = block(_state.value ?: MapState())
    }

    // 현재 위치 획득
    // - 권한이 있으면 현재 위치를 repo에서 받아와 selected로 기본 설정
    fun fetchMyLocation() {
        viewModelScope.launch {
            update { it.copy(isLoading = true) }
            try {
                val loc = repo.getCurrentLocation()
                update { it.copy(myLocation = loc, selected = loc?.let { l ->
                    InputLocation(uid = "me", latLng = l, label = "현재 위치")
                }, isLoading = false) }
            } catch (e: Exception) {
                update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    // 자동완성 검색
    // - 검색어 변경 시 Places 자동완성 결과를 가져와 suggestions에 반영
    fun searchPlaces(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = repo.findSuggestions(query)
                update { it.copy(searchQuery = query, suggestions = list) }
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            }
        }
    }

    // placeId -> 좌표, 선택 반영
    // - 사용자가 추천 항목을 고르면 상세 좌표를 가져와 선택 상태로 저장
    fun pickSuggestion(s: PlaceSuggestion) {
        viewModelScope.launch {
            try {
                val latLng = repo.fetchPlaceLatLng(s.placeId)
                update {
                    it.copy(
                        selected = InputLocation(uid = "me", latLng = latLng, label = s.label),
                        suggestions = emptyList()
                    )
                }
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            }
        }
    }

    // 중간 지점 계산
    // - 그룹 멤버 위치들을 받아 가중중심(중간지점)을 계산하여 상태 반영
    fun computeWeightedCenter(members: List<InputLocation>) {
        val center = repo.computeWeightedCenter(members)
        update { it.copy(members = members, weightedCenter = center) }
    }

    // Distance Matrix
    // - 중간지점(center)까지 각 멤버의 소요시간/거리를 비동기로 계산
    fun computeDistances(members: List<InputLocation>, center: LatLngData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val origins = members.map { it.uid to it.latLng }
                val results = repo.fetchDistanceMatrix(origins, center, mode = "transit")
                update { it.copy(distanceByMember = results) }
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            }
        }
    }
}