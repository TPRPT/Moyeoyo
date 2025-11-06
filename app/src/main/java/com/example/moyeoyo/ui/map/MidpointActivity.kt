package com.example.moyeoyo.ui.map

// MidpointActivity: 그룹 멤버의 위치로 중간지점을 계산하고 지도에 표시
// - ViewModel을 통해 가중중심 계산 및 Distance Matrix 결과 표시
// - 지도 마커로 멤버 위치와 중간지점을 시각화

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moyeoyo.R
import com.example.moyeoyo.data.model.InputLocation
import com.example.moyeoyo.data.model.LatLngData
import com.example.moyeoyo.data.model.TransportMode
import com.example.moyeoyo.data.repository.MapRepository
import com.example.moyeoyo.databinding.ActivityMidpointBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class MidpointActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMidpointBinding
    private lateinit var viewModel: MapViewModel
    private lateinit var map: GoogleMap
    private lateinit var adapter: MemberDistanceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMidpointBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // VM
        // - Repository 주입 및 ViewModel 생성
        val repo = MapRepository(this)
        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return MapViewModel(repo) as T
            }
        })[MapViewModel::class.java]

        // 지도
        // - GoogleMap 초기화 콜백 등록
        val mapFrag = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFrag.getMapAsync(this)

        // 리스트
        // - 멤버별 소요 시간/거리 표시용 RecyclerView
        adapter = MemberDistanceAdapter()
        binding.rvDistances.layoutManager = LinearLayoutManager(this)
        binding.rvDistances.adapter = adapter

        // 그룹 ID로 Firestore에서 멤버 입력 위치 로드
        val groupId = intent.getStringExtra("groupId")
        if (groupId != null) {
            viewModel.loadGroupMembers(groupId)
        }

        observe()
    }

    private fun observe() {
        viewModel.state.observe(this) { s ->
            // 로딩 패널
            binding.panelLoading.visibility = if (s.isLoading) View.VISIBLE else View.GONE

            // 지도/중간지점 표시
            s.weightedCenter?.let { center ->
                binding.txtCenterCoord.text = "위도: %.6f, 경도: %.6f".format(center.lat, center.lng)
                if (::map.isInitialized) showMarkersOnMap(s.members, center)
                // Distance Matrix (중간지점 기준 멤버별 시간/거리)
                viewModel.computeDistances(s.members, center)
            }

            // 멤버별 소요시간 표시
            if (s.distanceByMember.isNotEmpty()) {
                adapter.submitList(s.distanceByMember)
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
}