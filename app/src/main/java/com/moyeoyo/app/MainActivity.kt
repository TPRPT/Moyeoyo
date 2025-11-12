package com.moyeoyo.app

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.databinding.ActivityMainBinding
import com.moyeoyo.app.map.MapState
import com.moyeoyo.app.map.MapViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.widget.doOnTextChanged
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.data.model.DistanceResult
import java.util.Locale
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private val mapViewModel: MapViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private var googleMap: GoogleMap? = null
    private var pendingTravelTimesDialog = false
    private var pendingNearbyDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeEmulators()
        setupMap()
        setupViews()
        observeState()
    }

    private fun initializeEmulators() {
        try {
            FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
            FirebaseStorage.getInstance().useEmulator("10.0.2.2", 9199)
            Log.d("INIT", "✅ Local Firebase Emulators에 연결 설정 완료.")
        } catch (e: Exception) {
            Log.e("INIT", "❌ Emulator 연결 실패 (서버가 켜져 있는지 확인): ${e.message}")
        }
    }

    private fun setupMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapContainer) as? SupportMapFragment
            ?: SupportMapFragment.newInstance().also { fragment ->
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainer, fragment)
                    .commitNow()
            }
        mapFragment.getMapAsync(this)
    }

    private fun setupViews() = with(binding) {
        testButton.setOnClickListener {
            mapViewModel.loadGroupMembers("test-group-123")
        }

        buttonSelectFirstPlace.setOnClickListener {
            val nearbyPlaces = mapViewModel.state.value?.nearbyPlaces.orEmpty()
            if (nearbyPlaces.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.select_first_place_empty),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val firstPlace = nearbyPlaces.first()
                mapViewModel.selectNearbyPlace(firstPlace)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.select_first_place_toast, firstPlace.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        buttonLoadNearby.setOnClickListener {
            pendingNearbyDialog = true
            mapViewModel.loadNearbyPlaces()
        }

        editMemberUid.doOnTextChanged { text, _, _, _ ->
            mapViewModel.setMemberUidInput(text?.toString() ?: "")
        }

        groupTransportMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.buttonModeWalk -> TransportMode.WALK
                R.id.buttonModeDrive -> TransportMode.DRIVE
                R.id.buttonModeTransit -> TransportMode.TRANSIT
                else -> TransportMode.TRANSIT
            }
            mapViewModel.selectTransportMode(mode)
        }
        groupTransportMode.check(R.id.buttonModeTransit)

        buttonSaveTransportMode.setOnClickListener {
            mapViewModel.saveSelectedTransportForMember()
        }

        buttonShowTravelTimes.setOnClickListener {
            val currentState = mapViewModel.state.value
            if (currentState?.selectedPlace == null) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.travel_time_error_no_place),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (currentState.isDistanceLoading) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.travel_times_loading),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (currentState.distanceByMember.isNotEmpty()) {
                showTravelTimesDialog(currentState)
            } else {
                pendingTravelTimesDialog = true
                mapViewModel.computeTravelTimesForSelectedPlace()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.travel_times_loading),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun observeState() {
        mapViewModel.state.observe(this) { state ->
            renderState(state)
        }
    }

    private fun renderState(state: MapState) = with(binding) {
        progressBar.isVisible = state.isLoading

        val centerText = state.weightedCenter?.let { center ->
            getString(R.string.weighted_center_format, center.lat, center.lng)
        } ?: getString(R.string.weighted_center_placeholder)
        textWeightedCenter.text = centerText

        if (editMemberUid.text?.toString() != state.memberUidInput) {
            editMemberUid.setText(state.memberUidInput)
            editMemberUid.setSelection(state.memberUidInput.length)
        }

        val desiredCheckedId = when (state.selectedTransport) {
            TransportMode.WALK -> R.id.buttonModeWalk
            TransportMode.DRIVE -> R.id.buttonModeDrive
            TransportMode.TRANSIT -> R.id.buttonModeTransit
        }
        if (groupTransportMode.checkedButtonId != desiredCheckedId) {
            groupTransportMode.check(desiredCheckedId)
        }

        buttonSaveTransportMode.isEnabled = state.memberUidInput.isNotBlank()

        buttonLoadNearby.isEnabled = state.weightedCenter != null && !state.isNearbyLoading
        progressNearby.isVisible = state.isNearbyLoading

        val selectedPlace = state.selectedPlace
        textSelectedPlace.text = selectedPlace?.name
            ?: getString(R.string.selected_place_placeholder)
        textTravelTimes.text = when {
            state.isDistanceLoading -> getString(R.string.travel_times_loading)
            else -> getString(R.string.travel_times_placeholder)
        }
        buttonShowTravelTimes.isEnabled =
            selectedPlace != null && !state.isNearbyLoading && state.members.isNotEmpty() && !state.isDistanceLoading
        buttonShowTravelTimes.text = if (state.isDistanceLoading) {
            getString(R.string.travel_times_loading)
        } else {
            getString(R.string.travel_times_button_label)
        }

        if (pendingNearbyDialog && !state.isNearbyLoading) {
            if (state.nearbyPlaces.isNotEmpty()) {
                pendingNearbyDialog = false
                showNearbyPlacesDialog(state)
            } else if (state.error != null) {
                pendingNearbyDialog = false
            }
        }

        if (pendingTravelTimesDialog && !state.isDistanceLoading) {
            pendingTravelTimesDialog = false
            showTravelTimesDialog(state)
        }

        val membersText = if (state.members.isEmpty()) {
            getString(R.string.members_placeholder)
        } else {
            state.members.joinToString(separator = "\n") { member ->
                formatMemberLine(member)
            }
        }
        textMembers.text = membersText

        state.error?.let { message ->
            Log.e("MainActivity", "상태 에러: $message")
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            pendingTravelTimesDialog = false
        }

        updateMapMarkers(state.members, state.weightedCenter)
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
                    mapViewModel.selectNearbyPlace(places[selectedIndex])
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

    private fun formatMemberLine(member: InputLocation): String =
        getString(
            R.string.member_line_format,
            member.uid,
            member.latLng.lat,
            member.latLng.lng,
            member.label ?: "-"
        )

    private fun updateMapMarkers(members: List<InputLocation>, center: LatLngData?) {
        val map = googleMap ?: return
        map.clear()

        members.forEach { member ->
            val latLng = LatLng(member.latLng.lat, member.latLng.lng)
            map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(member.label ?: member.uid)
            )
        }

        center?.let {
            val centerLatLng = LatLng(it.lat, it.lng)
            map.addMarker(
                MarkerOptions()
                    .position(centerLatLng)
                    .title(getString(R.string.center_marker_title))
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(centerLatLng, 12f))
        } ?: run {
            if (members.isNotEmpty()) {
                val first = members.first().latLng
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(first.lat, first.lng),
                        11f
                    )
                )
            }
        }
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

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isMapToolbarEnabled = false
        }
    }
}

