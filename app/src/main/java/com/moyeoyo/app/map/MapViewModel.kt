package com.moyeoyo.app.map

// MapViewModel: 위치 입력/검색, 중간지점 계산, 거리계산 등
// - UI에서 발생하는 이벤트를 수집하고, Repository를 호출하여 상태(MapState)를 갱신
// - 코루틴을 이용해 비동기 작업 처리

import android.util.Log
import android.os.Looper
import androidx.lifecycle.*
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.PlaceSuggestion
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val repo: MapRepository
) : ViewModel() {

    private val _state = MutableLiveData(MapState())
    val state: LiveData<MapState> = _state

    // 상태 업데이트 헬퍼
    // - 기존 상태를 받아 변경된 상태를 생성해 LiveData에 반영
    private fun update(block: (MapState) -> MapState) {
        val current = _state.value ?: MapState()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            _state.value = block(current)
        } else {
            _state.postValue(block(current))
        }
    }

    fun setGroupId(groupId: String) {
        update { it.copy(groupId = groupId) }
    }

    fun setMemberUidInput(uid: String) {
        update { it.copy(memberUidInput = uid) }
    }

    fun selectTransportMode(mode: TransportMode) {
        update { it.copy(selectedTransport = mode) }
    }

    // 현재 위치 획득
    // - 권한이 있으면 현재 위치를 repo에서 받아와 selected로 기본 설정
    fun fetchMyLocation() {
        viewModelScope.launch {
            update { it.copy(isLoading = true) }
            try {
                val loc = repo.getCurrentLocation()
                val uid = repo.currentUserId() ?: "anonymous"
                update {
                    it.copy(
                        myLocation = loc,
                        selected = loc?.let { l ->
                            InputLocation(
                                uid = uid,
                                latLng = l,
                                label = "현재 위치"
                            )
                        },
                        isLoading = false
                    )
                }
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
                val uid = repo.currentUserId() ?: "anonymous"
                update {
                    it.copy(
                        selected = InputLocation(uid = uid, latLng = latLng, label = s.label),
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

    private fun computeDistancesByMode(
        members: List<InputLocation>,
        destination: LatLngData
    ) {
        if (members.isEmpty()) {
            update { it.copy(error = "멤버 위치 정보가 없습니다.", isDistanceLoading = false) }
            return
        }
        Log.d(
            "MapViewModel",
            "Distance Matrix 계산 시작: members=${members.size}, destination=${destination.lat},${destination.lng}"
        )
        update { it.copy(isDistanceLoading = true, distanceByMember = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val results = repo.fetchDistanceMatrix(members, destination)
                val resultMap = results.associateBy { it.uid }
                val ordered = members.mapNotNull { resultMap[it.uid] }
                Log.d(
                    "MapViewModel",
                    "Distance Matrix 계산 완료: 결과 ${ordered.size}건"
                )
                update { it.copy(distanceByMember = ordered, isDistanceLoading = false) }
            } catch (e: Exception) {
                Log.e("MapViewModel", "Distance Matrix 계산 실패: ${e.message}", e)
                update { it.copy(error = e.message, isDistanceLoading = false) }
            }
        }
    }

    // Firestore: 그룹 멤버 입력 위치 로드 → 상태 반영 후 중간지점 계산
    fun loadGroupMembers(groupId: String) {
        val isGroupChanged = state.value?.groupId != groupId
        if (isGroupChanged) {
        setGroupId(groupId)
            update {
                it.copy(
                    isLoading = true,
                    error = null,
                    members = emptyList(),
                    weightedCenter = null,
                    nearbyPlaces = emptyList(),
                    selectedPlace = null,
                    distanceByMember = emptyList(),
                    isDistanceLoading = false
                )
            }
        } else {
            update { it.copy(isLoading = true, error = null) }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val members = repo.getInputLocations(groupId)
                Log.d("MapViewModel", "Firestore에서 가져온 멤버 수: ${members.size}명")
                members.forEach { member ->
                    Log.d(
                        "MapViewModel",
                        "멤버 정보: uid=${member.uid}, lat=${member.latLng.lat}, lng=${member.latLng.lng}, mode=${member.transportMode}"
                    )
                }

                withContext(Dispatchers.Main) {
                    val overrideMode = _state.value?.transportFilterMode
                    val adjustedMembers = overrideMode?.let { mode ->
                        members.map { it.copy(transportMode = mode) }
                    } ?: members

                    update { it.copy(members = adjustedMembers) }

                    val center = repo.computeWeightedCenter(adjustedMembers)
                    if (center != null) {
                        Log.d(
                            "MapViewModel",
                            "✅ 중간 지점 계산 성공: lat=${center.lat}, lng=${center.lng}"
                        )
                        update {
                            it.copy(
                                weightedCenter = center,
                                isLoading = false,
                                nearbyPlaces = emptyList(),
                                selectedPlace = null,
                                distanceByMember = emptyList(),
                                isDistanceLoading = false
                            )
                        }
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { repo.saveComputedCenter(groupId, center) }
                                .onSuccess {
                                    Log.d(
                                        "MapViewModel",
                                        "중간 지점 Firestore 저장 완료: group=$groupId, lat=${center.lat}, lng=${center.lng}"
                                    )
                                }
                                .onFailure { saveError ->
                                    Log.e("MapViewModel", "중간 지점 저장 실패: ${saveError.message}", saveError)
                                    update { it.copy(error = saveError.message) }
                                }
                        }
                    } else {
                        Log.e("MapViewModel", "❌ 중간 지점 계산 실패")
                        update {
                            it.copy(
                                weightedCenter = null,
                                isLoading = false,
                                error = "중간 지점 계산에 실패했습니다.",
                                nearbyPlaces = emptyList(),
                                selectedPlace = null,
                                distanceByMember = emptyList(),
                                isDistanceLoading = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapViewModel", "Firestore 로드 중 예외 발생: ${e.message}", e)
                withContext(Dispatchers.Main) {
                update { it.copy(error = e.message, isLoading = false) }
                }
            }
        }
    }

    // Firestore: 현재 선택된 위치를 그룹에 저장
    fun saveSelectedToGroup() {
        val sel = _state.value?.selected ?: return
        val groupId = _state.value?.groupId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.saveMyInputLocation(groupId, sel)
            } catch (e: Exception) {
                update { it.copy(error = e.message) }
            }
        }
    }

    fun saveSelectedTransportForMember() {
        val currentState = _state.value ?: return
        val groupId = currentState.groupId
        if (groupId.isNullOrBlank()) {
            update { it.copy(error = "그룹 ID가 설정되지 않았습니다.") }
            return
        }
        val memberUid = currentState.memberUidInput.trim()
        update { it.copy(memberUidInput = memberUid) }
        if (memberUid.isEmpty()) {
            update { it.copy(error = "멤버 UID를 입력해주세요.") }
            return
        }
        val membersSnapshot = currentState.members
        val targetMember = membersSnapshot.find { it.uid == memberUid }
        if (targetMember == null) {
            update { it.copy(error = "해당 UID의 멤버를 찾지 못했습니다.") }
            return
        }

        val desiredMode = currentState.selectedTransport
        val updatedMember = targetMember.copy(transportMode = desiredMode)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.saveMyInputLocation(groupId, updatedMember)
            }.onSuccess {
                Log.d(
                    "MapViewModel",
                    "이동수단 저장 성공: uid=$memberUid, mode=$desiredMode"
                )
                loadGroupMembers(groupId)
            }.onFailure { e ->
                Log.e("MapViewModel", "이동수단 저장 실패: ${e.message}", e)
                update { it.copy(error = e.message) }
            }
        }
    }

    fun loadNearbyPlaces(radiusMeters: Int = 1500) {
        val center = _state.value?.weightedCenter ?: run {
            update { it.copy(error = "중간 지점이 계산된 후에 주변 장소를 불러올 수 있습니다.") }
            return
        }
        val radius = if (radiusMeters != 1500) {
            radiusMeters.toDouble()
        } else {
            (_state.value?.maxDistanceKm ?: 5.0) * 1000.0
        }
        loadNearbyPlacesInternal(center, radius)
    }

    private fun loadNearbyPlacesInternal(center: LatLngData, radiusMeters: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            val radius = radiusMeters.roundToInt().coerceAtLeast(500)
            update {
                it.copy(
                    isNearbyLoading = true,
                    error = null,
                    // 기존에 선택한 장소를 유지하여 연속 계산 시 UX를 자연스럽게 보장
                    distanceByMember = emptyList(),
                    isDistanceLoading = false
                )
            }
            runCatching { repo.fetchNearbyPlaces(center, radius) }
                .onSuccess { places ->
                    val sorted = places.sortedBy { it.distanceMeters }
                    val limited = sorted.take(20)
                    if (limited.isNotEmpty()) {
                        Log.d("MapViewModel", "주변 장소 ${limited.size}건 로드 성공 (원본 ${places.size}건)")
                        update {
                            it.copy(
                                nearbyPlaces = limited,
                                isNearbyLoading = false,
                                selectedPlace = it.selectedPlace?.takeIf { selected ->
                                    limited.any { place -> place.placeId == selected.placeId }
                                },
                                isDistanceLoading = false
                            )
                        }
                    } else {
                        Log.w("MapViewModel", "주변 장소 응답이 0건입니다.")
                        update {
                            it.copy(
                                nearbyPlaces = emptyList(),
                                error = "주변 장소 응답이 없습니다.",
                                isNearbyLoading = false,
                                isDistanceLoading = false
                            )
                        }
                    }
                }
                .onFailure { e ->
                    Log.e("MapViewModel", "주변 장소 로드 실패: ${e.message}", e)
                    update { it.copy(error = e.message ?: "주변 장소 로드 실패", isNearbyLoading = false, isDistanceLoading = false) }
                }
        }
    }

    fun selectNearbyPlace(place: NearbyPlace) {
        val currentState = _state.value ?: return
        val members = currentState.members
        if (members.isEmpty()) {
            update { it.copy(error = "멤버 위치 정보가 필요합니다.") }
            return
        }
        update { it.copy(selectedPlace = place, error = null) }
        computeDistancesByMode(members, place.latLng)
    }

    fun computeTravelTimesForSelectedPlace() {
        val currentState = _state.value ?: run {
            update { it.copy(error = "상태 정보를 불러오지 못했습니다.") }
            return
        }
        val members = currentState.members
        if (members.isEmpty()) {
            update { it.copy(error = "멤버 위치 정보가 필요합니다.") }
            return
        }
        val place = currentState.selectedPlace ?: run {
            update { it.copy(error = "먼저 장소를 선택해주세요.") }
            return
        }
        computeDistancesByMode(members, place.latLng)
    }

    fun applyTransportFilter(
        transportMode: TransportMode,
        maxDistanceKm: Double
    ) {
        val currentMembers = _state.value?.members ?: emptyList()
        val adjustedMembers = currentMembers.map { it.copy(transportMode = transportMode) }
        val center = repo.computeWeightedCenter(adjustedMembers)
        update {
            it.copy(
                members = adjustedMembers,
                transportFilterMode = transportMode,
                maxDistanceKm = maxDistanceKm,
                weightedCenter = center,
                nearbyPlaces = emptyList(),
                selectedPlace = null,
                distanceByMember = emptyList(),
                isDistanceLoading = false
            )
        }

        val groupId = _state.value?.groupId
        if (center != null && !groupId.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { repo.saveComputedCenter(groupId, center) }
                    .onFailure { saveError ->
                        Log.e("MapViewModel", "필터 적용 중 중간 지점 저장 실패: ${saveError.message}", saveError)
                        update { it.copy(error = saveError.message) }
                    }
            }
        }
    }

    /*
        init {
        // ViewModel이 생성되자마자 이 코드가 실행됩니다.
        // "test-group-123" 그룹의 멤버 위치를 로드해서 중간 지점을 계산해줘!
        println("====== MapLogic 테스트 시작 ======")
        loadGroupMembers("test-group-123")
    }
     */

    /*

    fun onTestButtonClick() {
        Log.d("MapViewModel", "====== 테스트 버튼 클릭! MapLogic 테스트 시작 ======")// 1단계: 저장할 '가짜 데이터'를 먼저 만들어줍니다.

        setGroupId("test-group-123")

        // 사용자가 '강남역'을 검색해서 선택했다고 가정해봅시다.
        val fakeSelectedLocation = InputLocation(
            uid = "user_A", // 실제로는 로그인된 사용자의 UID
            latLng = LatLngData(lat = 37.4979, lng = 127.0276),
            label = "강남역"
        )

        // 2단계: 만든 가짜 데이터를 ViewModel의 'selected' 상태에 저장합니다.
        update { it.copy(selected = fakeSelectedLocation) }

        // 3단계: 이제 드디어 저장 함수를 '호출'합니다!
        saveSelectedToGroup()
    }

     */

}


