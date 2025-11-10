package com.moyeoyo.app

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.databinding.ActivityMainBinding
import com.moyeoyo.app.map.MapState
import com.moyeoyo.app.map.MapViewModel
import com.moyeoyo.app.ui.NearbyPlacesAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private val mapViewModel: MapViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private val nearbyAdapter = NearbyPlacesAdapter()
    private var googleMap: GoogleMap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupViews()
        observeState()
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
        recyclerNearbyPlaces.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = nearbyAdapter
        }

        testButton.setOnClickListener {
            mapViewModel.loadGroupMembers("test-group-123")
        }

        buttonLoadNearby.setOnClickListener {
            mapViewModel.loadNearbyPlaces()
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

        buttonLoadNearby.isEnabled = state.weightedCenter != null && !state.isNearbyLoading
        progressNearby.isVisible = state.isNearbyLoading

        nearbyAdapter.submitList(state.nearbyPlaces)

        val membersText = if (state.members.isEmpty()) {
            getString(R.string.members_placeholder)
        } else {
            state.members.joinToString(separator = "\n") { member ->
                formatMemberLine(member)
            }
        }
        textMembers.text = membersText

        state.error?.let { Log.e("MainActivity", "상태 에러: $it") }

        updateMapMarkers(state.members, state.weightedCenter)
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

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isZoomControlsEnabled = true
            uiSettings.isMapToolbarEnabled = false
        }
    }
}

