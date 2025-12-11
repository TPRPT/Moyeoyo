package com.moyeoyo.app.map

// MidpointFragment: 그룹 멤버의 위치로 중간지점을 계산하고 표시
// - ViewModel을 통해 가중중심 계산 및 Distance Matrix 결과 표시
// - 주변 장소 불러오기 및 예상 소요 시간 기능 포함

import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.databinding.FragmentMidpointBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moyeoyo.app.data.repository.GroupRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import javax.inject.Inject

@AndroidEntryPoint
class MidpointFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentMidpointBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by viewModels()
    private lateinit var adapter: MemberDistanceAdapter
    private var pendingTravelTimesDialog = false
    private var pendingNearbyDialog = false
    private var googleMap: GoogleMap? = null
    
    @Inject
    lateinit var groupRepository: GroupRepository

    private val args: MidpointFragmentArgs by navArgs()
    private val groupId: String get() = args.groupId

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMidpointBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 리스트
        // - 멤버별 소요 시간/거리 표시용 RecyclerView
        adapter = MemberDistanceAdapter()
        binding.rvDistances.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDistances.adapter = adapter
        binding.rvDistances.isNestedScrollingEnabled = true

        (childFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment)
            ?.getMapAsync(this)

        // 그룹 ID 설정
        viewModel.setGroupId(groupId)

        setupViews()
        observe()

        // 중간 지점 계산 모드로 시작
        // ⭐ 항상 멤버를 다시 로드하여 상태 복원 (중간값 계산 완료 후 다시 접근 시 빈 화면 방지)
        viewModel.loadGroupMembers(groupId)
    }
    
    override fun onResume() {
        super.onResume()
        // ⭐ Fragment가 다시 나타날 때 상태 확인 및 복원
        val currentState = viewModel.state.value
        
        // 상태가 비어있거나 중간값이 없는 경우 다시 로드
        if (currentState == null || 
            (currentState.members.isEmpty() && currentState.weightedCenter == null)) {
            android.util.Log.d("MidpointFragment", "상태 복원: 멤버 및 중간값 다시 로드")
            viewModel.loadGroupMembers(groupId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupViews() {
        // 뒤로가기 버튼
        binding.btnBack.setOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnLoadNearby.setOnClickListener {
            val center = viewModel.state.value?.weightedCenter
            if (center == null) {
                Toast.makeText(requireContext(), "중간 지점이 계산된 후에 주변 장소를 불러올 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingNearbyDialog = true
            viewModel.loadNearbyPlaces()
        }
    }

    private fun observe() {
        viewModel.state.observe(viewLifecycleOwner) { s ->
                // ⭐ ViewModel의 isFinalized 상태에 따라 UI 업데이트
                val isFinalized = viewModel.isFinalized.value == true
                updateUIForMode(isFinalized, s)

                // 멤버별 소요시간 표시
                if (s.distanceByMember.isNotEmpty()) {
                    bindMemberDistances(s)
                } else if (adapter.itemCount != 0) {
                    adapter.submitList(emptyList())
                }

                // 주변 장소 필터링 화면으로 이동
                if (pendingNearbyDialog && !s.isNearbyLoading) {
                    if (s.weightedCenter != null) {
                        pendingNearbyDialog = false
                        // ⭐ 주변 장소 검색 버튼을 눌렀을 때는 상태를 변경하지 않음 (이미 LocationInputFragment에서 변경됨)
                        // RecommendedPlaceFragment로 이동
                        findNavController().navigate(
                            R.id.action_midpointFragment_to_recommendedPlaceFragment,
                            Bundle().apply {
                                putString("groupId", groupId)
                                putFloat("centerLat", s.weightedCenter.lat.toFloat())
                                putFloat("centerLng", s.weightedCenter.lng.toFloat())
                            }
                        )
                    } else if (s.error != null) {
                        pendingNearbyDialog = false
                        Toast.makeText(requireContext(), s.error, Toast.LENGTH_SHORT).show()
                    }
                }

                // 예상 소요 시간 다이얼로그 표시
                if (pendingTravelTimesDialog && !s.isDistanceLoading) {
                    pendingTravelTimesDialog = false
                    showTravelTimesDialog(s)
                }

                // 에러 표시
                s.error?.let { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    pendingTravelTimesDialog = false
                }

                updateMapMarkers(s)
        }
    }

    /**
     * 최종 모드 여부에 따라 UI 업데이트
     */
    private fun updateUIForMode(isFinalized: Boolean, state: MapState) {
        if (isFinalized) {
            // 최종 확정된 약속 장소 정보 표시
            binding.tvMidpointTitle.text = "최종 확정된 약속 장소"
            state.selectedPlace?.let { place ->
                binding.tvMidpointAddress.text = place.name
                if (!place.address.isNullOrBlank()) {
                    binding.tvMidpointCoords.text = place.address
                } else {
                    binding.tvMidpointCoords.text = "위도: %.6f° / 경도: %.6f°".format(
                        place.latLng.lat, 
                        place.latLng.lng
                    )
                }
            } ?: run {
                binding.tvMidpointAddress.text = "장소 정보를 불러오는 중..."
                binding.tvMidpointCoords.text = ""
            }
        } else {
            // 중간지점 표시
            binding.tvMidpointTitle.text = "계산된 중간 지점"
            state.weightedCenter?.let { center ->
                binding.tvMidpointCoords.text = "위도: %.6f° / 경도: %.6f°".format(center.lat, center.lng)
                // 주소 가져오기
                getAddressFromLocation(center) { address ->
                    binding.tvMidpointAddress.text = address
                }
            } ?: run {
                binding.tvMidpointAddress.text = "중간 지점을 계산 중입니다..."
                binding.tvMidpointCoords.text = ""
            }
        }
    }

    private fun bindMemberDistances(state: MapState) {
        val labelMap = state.members.associateBy({ it.uid }) { it.nickname ?: it.label ?: it.uid }
        val modeMap = state.members.associateBy({ it.uid }) { it.transportMode }
        val items = state.distanceByMember.map { result ->
            MemberDistanceItem(
                uid = result.uid,
                displayName = labelMap[result.uid] ?: result.uid,
                transportMode = modeMap[result.uid] ?: TransportMode.TRANSIT,
                durationSeconds = result.durationSeconds,
                distanceMeters = result.distanceMeters
            )
        }
        adapter.submitList(items)
        updateAverageDistance(state.distanceByMember, state.members)
    }

    private fun getAddressFromLocation(center: LatLngData, callback: (String) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val geocoder = Geocoder(requireContext(), Locale.KOREA)
                val addresses = geocoder.getFromLocation(center.lat, center.lng, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "주소 정보 없음"
                callback(address)
            } catch (e: Exception) {
                callback("주소 정보 없음")
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        
        // ⭐ ViewModel에 지도 준비 완료를 알림
        viewModel.onMapReady()
        
        // 마커 클릭 리스너 설정
        map.setOnMarkerClickListener { marker ->
            // 최종 확정된 장소 마커인지 확인
            val isFinalized = viewModel.isFinalized.value == true
            val selectedPlace = viewModel.state.value?.selectedPlace
            
            if (isFinalized && selectedPlace != null && 
                marker.position.latitude == selectedPlace.latLng.lat && 
                marker.position.longitude == selectedPlace.latLng.lng) {
                // 최종 확정된 장소 마커 클릭 시 정보 표시
                marker.showInfoWindow()
                true // 이벤트 소비
            } else {
                false // 기본 동작 수행
            }
        }
        
        // 지도가 준비되면 현재 상태로 마커 업데이트
        viewModel.state.value?.let { updateMapMarkers(it) }
    }

    private fun updateMapMarkers(state: MapState) {
        val map = googleMap ?: return
        map.clear()

        val builder = LatLngBounds.Builder()
        var hasPoint = false

        state.members.forEach { member ->
            val position = LatLng(member.latLng.lat, member.latLng.lng)
            val displayName = member.nickname ?: member.label ?: member.uid
            map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(displayName)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
            builder.include(position)
            hasPoint = true
        }

        // ⭐ 최종 모드 여부에 따라 마커 표시 분기
        val isFinalized = viewModel.isFinalized.value == true
        
        if (isFinalized) {
            // 최종 모드: 최종 확정된 장소에 빨간색 마커 표시
            state.selectedPlace?.let { place ->
                val position = LatLng(place.latLng.lat, place.latLng.lng)
                android.util.Log.d("MidpointFragment", 
                    "✅ 승리한 장소 마커 추가: ${place.name}, 위치: (${place.latLng.lat}, ${place.latLng.lng}), 주소: ${place.address}")
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(place.name)
                        .snippet(place.address ?: "최종 확정된 장소")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                if (marker != null) {
                    android.util.Log.d("MidpointFragment", "✅ 빨간색 마커 추가 성공")
                } else {
                    android.util.Log.e("MidpointFragment", "❌ 마커 추가 실패")
                }
                builder.include(position)
                hasPoint = true
            } ?: run {
                android.util.Log.d("MidpointFragment", "⏸️ 최종 모드이지만 selectedPlace가 null입니다")
            }
        } else {
            // 중간 지점 모드: 중간 지점에 파란색 마커 표시
            state.weightedCenter?.let { center ->
                val position = LatLng(center.lat, center.lng)
                map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(getString(R.string.center_marker_title))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                builder.include(position)
                hasPoint = true
            }
        }

        if (hasPoint) {
            try {
                val bounds = builder.build()
                val padding = resources.getDimensionPixelSize(R.dimen.map_bounds_padding)
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            } catch (_: IllegalStateException) {
                // ignore if bounds cannot be built
            }
        } else {
            val seoul = LatLng(37.5665, 126.9780)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(seoul, 11f))
        }
    }

    private fun updateAverageDistance(results: List<DistanceResult>, members: List<InputLocation>) {
        if (results.isEmpty()) return

        val totalDistance = results.sumOf { it.distanceMeters }
        val avgDistanceKm = (totalDistance / results.size) / 1000.0
        binding.tvAvgDistance.text = "평균 이동 거리: %.1fkm".format(avgDistanceKm)

        val distances = results.map { it.distanceMeters / 1000.0 }
        val maxDistance = distances.maxOrNull() ?: 0.0
        val minDistance = distances.minOrNull() ?: 0.0
        val maxDeviation = (maxDistance - minDistance) / 2.0
        binding.tvMaxDeviation.text = "최대 편차: ±%.1fkm (공평한 위치)".format(maxDeviation)
    }

    private fun showNearbyPlacesDialog(state: MapState) {
        val places = state.nearbyPlaces
        if (places.isEmpty()) return
        var selectedIndex = places.indexOfFirst { it.placeId == state.selectedPlace?.placeId }
        if (selectedIndex < 0) selectedIndex = -1
        val placeLabels = places.map { place ->
            val distanceKm = place.distanceMeters / 1000.0
            buildString {
                append(place.name)
                append(" • ")
                append(String.format(Locale.getDefault(), "%.2fkm", distanceKm))
                place.address?.let { addr ->
                    append("\n")
                    append(addr)
                }
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.load_nearby_button_label))
            .setSingleChoiceItems(placeLabels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    viewModel.selectNearbyPlace(places[selectedIndex])
                    // 장소 선택 후 예상 소요 시간 계산
                    pendingTravelTimesDialog = true
                    viewModel.computeTravelTimesForSelectedPlace()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.travel_time_error_no_place),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTravelTimesDialog(state: MapState) {
        val placeName = state.selectedPlace?.name ?: return
        val message = if (state.distanceByMember.isNotEmpty()) {
            formatTravelTimes(state.distanceByMember, state.members)
        } else {
            getString(R.string.travel_times_empty)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.travel_time_dialog_title) + " - " + placeName)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatTravelTimes(
        results: List<DistanceResult>,
        members: List<InputLocation>
    ): String {
        val memberLabels = members.associateBy({ it.uid }) { it.label ?: it.uid }
        val memberModes = members.associateBy({ it.uid }) { it.transportMode }
        val sortedResults = results.sortedBy { memberLabels[it.uid] ?: it.uid }
        return sortedResults.joinToString(separator = "\n") { result ->
            val label = memberLabels[result.uid] ?: result.uid
            val minutes = (result.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
            val distanceKm = result.distanceMeters / 1000.0
            val distanceString = String.format(Locale.getDefault(), "%.1f", distanceKm)
            val modeLabel = formatTransportModeLabel(memberModes[result.uid])
            getString(
                R.string.travel_time_line_format,
                label,
                minutes,
                distanceString,
                modeLabel
            )
        }
    }

    private fun formatTransportModeLabel(mode: TransportMode?): String = when (mode) {
        TransportMode.WALK -> getString(R.string.mode_walk)
        TransportMode.TRANSIT -> getString(R.string.mode_transit)
        TransportMode.DRIVE -> getString(R.string.mode_drive)
        null -> "-"
    }
}

