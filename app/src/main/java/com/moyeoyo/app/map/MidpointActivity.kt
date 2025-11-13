package com.moyeoyo.app.map

// MidpointActivity: 그룹 멤버의 위치로 중간지점을 계산하고 지도에 표시
// - ViewModel을 통해 가중중심 계산 및 Distance Matrix 결과 표시
// - 지도 마커로 멤버 위치와 중간지점을 시각화
// - 주변 장소 불러오기 및 예상 소요 시간 기능 포함

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.databinding.ActivityMidpointBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.widget.doOnTextChanged
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class MidpointActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMidpointBinding
    private val viewModel: MapViewModel by viewModels()
    private lateinit var map: GoogleMap
    private lateinit var adapter: MemberDistanceAdapter
    private var pendingTravelTimesDialog = false
    private var pendingNearbyDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMidpointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 지도
        // - GoogleMap 초기화 콜백 등록
        val mapFrag = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFrag.getMapAsync(this)

        // 리스트
        // - 멤버별 소요 시간/거리 표시용 RecyclerView
        adapter = MemberDistanceAdapter()
        binding.rvDistances.layoutManager = LinearLayoutManager(this)
        binding.rvDistances.adapter = adapter

        // 그룹 ID 설정 (테스트 데이터 버튼을 눌렀을 때 로드)
        val groupId = intent.getStringExtra("groupId") ?: "test-group-123"
        viewModel.setGroupId(groupId)

        setupViews()
        observe()
    }

    private fun setupViews() {
        // 테스트 데이터 불러오기 버튼
        binding.btnLoadTestData.setOnClickListener {
            val groupId = intent.getStringExtra("groupId") ?: "test-group-123"
            viewModel.loadGroupMembers(groupId)
        }

        // 멤버 UID 입력
        binding.editMemberUid.doOnTextChanged { text, _, _, _ ->
            viewModel.setMemberUidInput(text?.toString() ?: "")
        }

        // 이동수단 선택
        binding.groupTransportMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.buttonModeWalk -> TransportMode.WALK
                R.id.buttonModeDrive -> TransportMode.DRIVE
                R.id.buttonModeTransit -> TransportMode.TRANSIT
                else -> TransportMode.TRANSIT
            }
            viewModel.selectTransportMode(mode)
        }
        binding.groupTransportMode.check(R.id.buttonModeTransit)

        // 이동수단 저장 버튼
        binding.buttonSaveTransportMode.setOnClickListener {
            viewModel.saveSelectedTransportForMember()
        }

        // 주변 후보 보기 버튼
        binding.btnSeeNearby.setOnClickListener {
            val center = viewModel.state.value?.weightedCenter
            if (center == null) {
                Toast.makeText(this, "중간 지점이 계산된 후에 주변 장소를 불러올 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingNearbyDialog = true
            viewModel.loadNearbyPlaces()
        }

        // 주변 장소 추천 보기 버튼 (하단)
        binding.btnNearbyPlaces.setOnClickListener {
            val center = viewModel.state.value?.weightedCenter
            if (center == null) {
                Toast.makeText(this, "중간 지점이 계산된 후에 주변 장소를 불러올 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingNearbyDialog = true
            viewModel.loadNearbyPlaces()
        }
    }

    private fun observe() {
        viewModel.state.observe(this) { s ->
            // 로딩 패널
            binding.panelLoading.visibility = if (s.isLoading) View.VISIBLE else View.GONE

            // 멤버 UID 입력 필드 동기화
            if (binding.editMemberUid.text?.toString() != s.memberUidInput) {
                binding.editMemberUid.setText(s.memberUidInput)
                binding.editMemberUid.setSelection(s.memberUidInput.length)
            }

            // 이동수단 선택 동기화
            val desiredCheckedId = when (s.selectedTransport) {
                TransportMode.WALK -> R.id.buttonModeWalk
                TransportMode.DRIVE -> R.id.buttonModeDrive
                TransportMode.TRANSIT -> R.id.buttonModeTransit
            }
            if (binding.groupTransportMode.checkedButtonId != desiredCheckedId) {
                binding.groupTransportMode.check(desiredCheckedId)
            }

            // 저장 버튼 활성화
            binding.buttonSaveTransportMode.isEnabled = s.memberUidInput.isNotBlank()

            // 지도/중간지점 표시
            s.weightedCenter?.let { center ->
                binding.txtCenterCoord.text = "위도: %.6f, 경도: %.6f".format(center.lat, center.lng)
                if (::map.isInitialized) showMarkersOnMap(s.members, center)
            }

            // 멤버별 소요시간 표시
            if (s.distanceByMember.isNotEmpty()) {
                adapter.submitList(s.distanceByMember)
            }

            // 주변 장소 다이얼로그 표시
            if (pendingNearbyDialog && !s.isNearbyLoading) {
                if (s.nearbyPlaces.isNotEmpty()) {
                    pendingNearbyDialog = false
                    showNearbyPlacesDialog(s)
                } else if (s.error != null) {
                    pendingNearbyDialog = false
                    Toast.makeText(this, s.error, Toast.LENGTH_SHORT).show()
                }
            }

            // 예상 소요 시간 다이얼로그 표시
            if (pendingTravelTimesDialog && !s.isDistanceLoading) {
                pendingTravelTimesDialog = false
                showTravelTimesDialog(s)
            }

            // 에러 표시
            s.error?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                pendingTravelTimesDialog = false
            }
        }
    }

    override fun onMapReady(gm: GoogleMap) {
        map = gm
        map.uiSettings.isZoomControlsEnabled = true
    }

    private fun showMarkersOnMap(members: List<InputLocation>, center: LatLngData) {
        map.clear()
        // 멤버 마커
        members.forEach {
            map.addMarker(
                MarkerOptions().position(LatLng(it.latLng.lat, it.latLng.lng)).title(it.uid)
            )
        }
        // 중간 지점(A) 마커
        map.addMarker(
            MarkerOptions()
                .position(LatLng(center.lat, center.lng))
                .title("중간 지점")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.lat, center.lng), 13f))
    }

    private fun showNearbyPlacesDialog(state: MapState) {
        val places = state.nearbyPlaces
        if (places.isEmpty()) return
        var selectedIndex = places.indexOfFirst { it.placeId == state.selectedPlace?.placeId }
        if (selectedIndex < 0) selectedIndex = -1
        val placeLabels = places.map { place ->
            buildString {
                append(place.name)
                place.address?.let { addr ->
                    append("\n")
                    append(addr)
                }
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
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
                        this,
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
        MaterialAlertDialogBuilder(this)
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