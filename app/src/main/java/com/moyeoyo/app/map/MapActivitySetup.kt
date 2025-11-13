package com.moyeoyo.app.map

import android.util.Log
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * MainActivity의 지도 및 UI 관련 로직을 분리한 클래스
 * 초기 세팅 코드를 분리하여 브랜치 병합 시 충돌을 최소화합니다.
 */
class MapActivitySetup(
    private val activity: android.app.Activity,
    private val binding: ActivityMainBinding,
    private val viewModel: MapViewModel,
    private var googleMap: GoogleMap?
) {
    private var pendingTravelTimesDialog = false
    private var pendingNearbyDialog = false

    fun setupViews() = with(binding) {
        testButton.setOnClickListener {
            viewModel.loadGroupMembers("test-group-123")
        }

        buttonSelectFirstPlace.setOnClickListener {
            val nearbyPlaces = viewModel.state.value?.nearbyPlaces.orEmpty()
            if (nearbyPlaces.isEmpty()) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.select_first_place_empty),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val firstPlace = nearbyPlaces.first()
                viewModel.selectNearbyPlace(firstPlace)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.select_first_place_toast, firstPlace.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        buttonLoadNearby.setOnClickListener {
            pendingNearbyDialog = true
            viewModel.loadNearbyPlaces()
        }

        editMemberUid.doOnTextChanged { text, _, _, _ ->
            viewModel.setMemberUidInput(text?.toString() ?: "")
        }

        groupTransportMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.buttonModeWalk -> TransportMode.WALK
                R.id.buttonModeDrive -> TransportMode.DRIVE
                R.id.buttonModeTransit -> TransportMode.TRANSIT
                else -> TransportMode.TRANSIT
            }
            viewModel.selectTransportMode(mode)
        }
        groupTransportMode.check(R.id.buttonModeTransit)

        buttonSaveTransportMode.setOnClickListener {
            viewModel.saveSelectedTransportForMember()
        }

        buttonShowTravelTimes.setOnClickListener {
            val currentState = viewModel.state.value
            if (currentState?.selectedPlace == null) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.travel_time_error_no_place),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (currentState.isDistanceLoading) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.travel_times_loading),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (currentState.distanceByMember.isNotEmpty()) {
                showTravelTimesDialog(currentState)
            } else {
                pendingTravelTimesDialog = true
                viewModel.computeTravelTimesForSelectedPlace()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.travel_times_loading),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun observeState() {
        viewModel.state.observe(activity as androidx.lifecycle.LifecycleOwner) { state ->
            renderState(state)
        }
    }

    private fun renderState(state: MapState) = with(binding) {
        progressBar.isVisible = state.isLoading

        val centerText = state.weightedCenter?.let { center ->
            activity.getString(R.string.weighted_center_format, center.lat, center.lng)
        } ?: activity.getString(R.string.weighted_center_placeholder)
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
            ?: activity.getString(R.string.selected_place_placeholder)
        textTravelTimes.text = when {
            state.isDistanceLoading -> activity.getString(R.string.travel_times_loading)
            else -> activity.getString(R.string.travel_times_placeholder)
        }
        buttonShowTravelTimes.isEnabled =
            selectedPlace != null && !state.isNearbyLoading && state.members.isNotEmpty() && !state.isDistanceLoading
        buttonShowTravelTimes.text = if (state.isDistanceLoading) {
            activity.getString(R.string.travel_times_loading)
        } else {
            activity.getString(R.string.travel_times_button_label)
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
            activity.getString(R.string.members_placeholder)
        } else {
            state.members.joinToString(separator = "\n") { member ->
                formatMemberLine(member)
            }
        }
        textMembers.text = membersText

        state.error?.let { message ->
            Log.e("MapActivitySetup", "상태 에러: $message")
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
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

        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.load_nearby_button_label))
            .setSingleChoiceItems(placeLabels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selectedIndex >= 0) {
                    viewModel.selectNearbyPlace(places[selectedIndex])
                } else {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.travel_time_error_no_place),
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
            activity.getString(R.string.travel_times_empty)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.travel_time_dialog_title) + " - " + placeName)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatMemberLine(member: InputLocation): String =
        activity.getString(
            R.string.member_line_format,
            member.uid,
            member.latLng.lat,
            member.latLng.lng,
            member.label ?: "-"
        )

    fun updateMapMarkers(members: List<InputLocation>, center: LatLngData?) {
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
                    .title(activity.getString(R.string.center_marker_title))
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

    fun setGoogleMap(map: GoogleMap?) {
        googleMap = map
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
            activity.getString(
                R.string.travel_time_line_format,
                label,
                minutes,
                distanceString,
                modeLabel
            )
        }
    }

    private fun formatTransportModeLabel(mode: TransportMode?): String = when (mode) {
        TransportMode.WALK -> activity.getString(R.string.mode_walk)
        TransportMode.TRANSIT -> activity.getString(R.string.mode_transit)
        TransportMode.DRIVE -> activity.getString(R.string.mode_drive)
        null -> "-"
    }
}

