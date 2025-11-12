package com.moyeoyo.app.map

// MapState: 위치 입력/중간지점 화면에서 사용하는 UI 상태 집합
// - 현재 위치, 자동완성 결과, 선택된 위치
// - 그룹 멤버 목록, 가중중심 결과, Distance Matrix 결과

import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.PlaceSuggestion
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.NearbyPlace

data class MapState(
    val groupId: String? = null,
    // 위치 입력 화면
    val myLocation: LatLngData? = null,           // 현재 위치(GPS)
    val savedHome: LatLngData? = null,            // 자주 사용하는 위치 - 집
    val savedWork: LatLngData? = null,            // 자주 사용하는 위치 - 회사
    val searchQuery: String = "",                 // 검색어
    val suggestions: List<PlaceSuggestion> = emptyList(), // 자동완성 제안
    val selected: InputLocation? = null,          // 최종 선택된 위치
    val selectedTransport: TransportMode = TransportMode.TRANSIT,
    val memberUidInput: String = "",

    // 중간 지점 화면
    val members: List<InputLocation> = emptyList(),        // 그룹 멤버 입력 위치
    val weightedCenter: LatLngData? = null,                // 가중중심 결과
    val nearestStationText: String? = null,                // 가까운 지하철 텍스트
    val distanceByMember: List<DistanceResult> = emptyList(), // 멤버별 소요시간
    val isDistanceLoading: Boolean = false,
    val selectedPlace: NearbyPlace? = null,                // 선택된 약속 장소

    // 주변 추천 장소
    val nearbyPlaces: List<NearbyPlace> = emptyList(),

    // 상태 공통
    val isLoading: Boolean = false,
    val isNearbyLoading: Boolean = false,
    val error: String? = null
)