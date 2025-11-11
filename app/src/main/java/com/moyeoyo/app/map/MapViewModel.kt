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
import com.moyeoyo.app.data.model.TransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // Firestore: 그룹 멤버 입력 위치 로드 → 상태 반영 후 중간지점 계산
    fun loadGroupMembers(groupId: String) {
        setGroupId(groupId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                update { it.copy(isLoading = true, error = null, nearbyPlaces = emptyList()) }
                val members = repo.getInputLocations(groupId)
                Log.d("MapViewModel", "Firestore에서 가져온 멤버 수: ${members.size}명")
                members.forEach { member ->
                    Log.d(
                        "MapViewModel",
                        "멤버 정보: uid=${member.uid}, lat=${member.latLng.lat}, lng=${member.latLng.lng}, label=${member.label}"
                    )
                }
                val center = repo.computeWeightedCenter(members)
                if (center != null) {
                    Log.d(
                        "MapViewModel",
                        "✅ 중간 지점 계산 성공: lat=${center.lat}, lng=${center.lng}"
                    )
                } else {
                    Log.e("MapViewModel", "❌ 중간 지점 계산 실패 - 결과가 null입니다.")
                }
                update { it.copy(members = members, weightedCenter = center, isLoading = false) }
                if (center != null) {
                    computeDistances(members, center)
                    runCatching {
                        repo.saveComputedCenter(groupId, center)
                        Log.d(
                            "MapViewModel",
                            "중간 지점 Firestore 저장 완료: group=$groupId, lat=${center.lat}, lng=${center.lng}"
                        )
                    }.onFailure { saveError ->
                        Log.e("MapViewModel", "중간 지점 저장 실패: ${saveError.message}", saveError)
                        update { it.copy(error = saveError.message) }
                    }
                }
            } catch (e: Exception) {
                Log.e("MapViewModel", "Firestore 로드 중 예외 발생: ${e.message}", e)
                update { it.copy(error = e.message, isLoading = false) }
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
        viewModelScope.launch(Dispatchers.IO) {
            update { it.copy(isNearbyLoading = true, error = null) }
            runCatching { repo.fetchNearbyPlaces(center, radiusMeters) }
                .onSuccess { places ->
                    update { it.copy(nearbyPlaces = places, isNearbyLoading = false) }
                }
                .onFailure { e ->
                    Log.e("MapViewModel", "주변 장소 로드 실패: ${e.message}", e)
                    update { it.copy(error = e.message, isNearbyLoading = false) }
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


