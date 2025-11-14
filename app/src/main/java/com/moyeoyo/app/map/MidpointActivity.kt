package com.moyeoyo.app.map

// MidpointActivity: 그룹 멤버의 위치로 중간지점을 계산하고 표시
// - ViewModel을 통해 가중중심 계산 및 Distance Matrix 결과 표시
// - 주변 장소 불러오기 및 예상 소요 시간 기능 포함

import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.content.ContextCompat
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
import com.moyeoyo.app.databinding.ActivityMidpointBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@AndroidEntryPoint
class MidpointActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMidpointBinding
    private val viewModel: MapViewModel by viewModels()
    private lateinit var adapter: MemberDistanceAdapter
    private var pendingTravelTimesDialog = false
    private var pendingNearbyDialog = false
    private var googleMap: GoogleMap? = null
    private var isMapReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMidpointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 리스트
        // - 멤버별 소요 시간/거리 표시용 RecyclerView
        adapter = MemberDistanceAdapter()
        binding.rvDistances.layoutManager = LinearLayoutManager(this)
        binding.rvDistances.adapter = adapter
        binding.rvDistances.isNestedScrollingEnabled = true

        (supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment)
            ?.getMapAsync(this)

        // 그룹 ID 설정
        val groupId = intent.getStringExtra("groupId") ?: "test-group-123"
        viewModel.setGroupId(groupId)

        setupViews()
        observe()

        // Intent로 groupId가 전달된 경우 자동으로 데이터 로드
        if (intent.hasExtra("groupId")) {
            viewModel.loadGroupMembers(groupId)
        }
    }

    private fun setupViews() {
        // 뒤로가기 버튼
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnLoadNearby.setOnClickListener {
            val center = viewModel.state.value?.weightedCenter
            if (center == null) {
                Toast.makeText(this, "중간 지점이 계산된 후에 주변 장소를 불러올 수 있습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingNearbyDialog = true
            viewModel.loadNearbyPlaces()
        }

        binding.btnOpenFilter.setOnClickListener {
            showTransportFilterDialog(viewModel.state.value)
        }
    }

    private fun observe() {
        viewModel.state.observe(this) { s ->
            // 중간지점 표시
            s.weightedCenter?.let { center ->
                binding.tvMidpointCoords.text = "위도: %.6f° / 경도: %.6f°".format(center.lat, center.lng)
                // 주소 가져오기
                getAddressFromLocation(center) { address ->
                    binding.tvMidpointAddress.text = address
                }
            }

            // 멤버별 소요시간 표시
            if (s.distanceByMember.isNotEmpty()) {
                bindMemberDistances(s)
            } else if (adapter.itemCount != 0) {
                adapter.submitList(emptyList())
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

            updateMapMarkers(s)
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geocoder = Geocoder(this@MidpointActivity, Locale.KOREA)
                val addresses = geocoder.getFromLocation(center.lat, center.lng, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0) ?: "주소 정보 없음"
                withContext(Dispatchers.Main) {
                    callback(address)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback("주소 정보 없음")
                }
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        isMapReady = true
        map.uiSettings.isZoomControlsEnabled = true
        viewModel.state.value?.let { updateMapMarkers(it) }
    }

    private fun updateMapMarkers(state: MapState) {
        if (!isMapReady) return
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

        state.selectedPlace?.let { place ->
            val position = LatLng(place.latLng.lat, place.latLng.lng)
        map.addMarker(
            MarkerOptions()
                    .position(position)
                    .title(place.name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
            builder.include(position)
            hasPoint = true
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

    private fun showTransportFilterDialog(state: MapState?) {
        val dialog = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_transport_filter, null)
        dialog.setContentView(content)

        val btnWalk = content.findViewById<TextView>(R.id.btnWalk)
        val btnTransit = content.findViewById<TextView>(R.id.btnTransit)
        val btnCar = content.findViewById<TextView>(R.id.btnCar)
        val tvMaxDistanceValue = content.findViewById<TextView>(R.id.tvMaxDistanceValue)
        val distanceSeek = content.findViewById<SeekBar>(R.id.seekBarDistance)
        val btnApply = content.findViewById<View>(R.id.btnApplyFilter)
        content.findViewById<View>(R.id.btnCloseFilter).setOnClickListener { dialog.dismiss() }

        var selectedMode = state?.transportFilterMode ?: TransportMode.TRANSIT

        fun updateModeSelection() {
            listOf(btnWalk, btnTransit, btnCar).forEach { button ->
                val mode = when (button.id) {
                    R.id.btnWalk -> TransportMode.WALK
                    R.id.btnCar -> TransportMode.DRIVE
                    else -> TransportMode.TRANSIT
                }
                val selected = selectedMode == mode
                val backgroundRes = if (selected) {
                    R.drawable.bg_transport_selected
                } else {
                    R.drawable.bg_transport_unselected
                }
                button.background = ContextCompat.getDrawable(this, backgroundRes)
                val colorRes = if (selected) android.R.color.white else R.color.black
                button.setTextColor(ContextCompat.getColor(this, colorRes))
            }
        }
        updateModeSelection()

        btnWalk.setOnClickListener {
            selectedMode = TransportMode.WALK
            updateModeSelection()
        }
        btnTransit.setOnClickListener {
            selectedMode = TransportMode.TRANSIT
            updateModeSelection()
        }
        btnCar.setOnClickListener {
            selectedMode = TransportMode.DRIVE
            updateModeSelection()
        }

        val initialDistance = ((state?.maxDistanceKm ?: 5.0) * 10).roundToInt().coerceIn(1, 100)

        fun updateDistanceLabel(progress: Int) {
            val km = progress.coerceAtLeast(1) / 10.0
            tvMaxDistanceValue.text = String.format(Locale.getDefault(), "%.1fkm", km)
        }

        distanceSeek.progress = initialDistance
        updateDistanceLabel(initialDistance)
        distanceSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateDistanceLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnApply.setOnClickListener {
            val distanceKm = max(distanceSeek.progress / 10.0, 0.5)
            viewModel.applyTransportFilter(selectedMode, distanceKm)
            dialog.dismiss()
        }

        dialog.show()
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