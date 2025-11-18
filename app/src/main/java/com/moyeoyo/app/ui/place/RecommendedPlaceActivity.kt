package com.moyeoyo.app.ui.place

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCategory
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.databinding.ActivityRecommendedPlaceBinding
import com.moyeoyo.app.databinding.DialogRankSelectionBinding
import com.moyeoyo.app.ui.place.RankedPlaceParcelable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecommendedPlaceActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityRecommendedPlaceBinding
    private val viewModel: RecommendedPlaceViewModel by viewModels()
    
    private lateinit var adapter: RecommendedPlaceAdapter
    private var googleMap: GoogleMap? = null
    private var isMapReady = false
    private var selectedLocation: LatLngData? = null
    private var isRankMode = false
    private val selectedRanks = mutableMapOf<String, Int>() // placeId -> rank (1, 2, 3)
    private var currentRank = 1 // 다음 선택할 순위

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecommendedPlaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 그룹 ID 받기
        val groupId = intent.getStringExtra("groupId") ?: ""
        if (groupId.isNotEmpty()) {
            viewModel.setGroupId(groupId)
        }

        // 중간값 위치 받기
        val centerLat = intent.getDoubleExtra("centerLat", 0.0)
        val centerLng = intent.getDoubleExtra("centerLng", 0.0)
        selectedLocation = if (centerLat != 0.0 && centerLng != 0.0) {
            LatLngData(centerLat, centerLng)
        } else {
            // 기본값: 서울
            LatLngData(37.5665, 126.9780)
        }

        setupViews()
        setupRecyclerView()
        setupCategoryFilters()
        observeViewModel()

        // 지도 초기화
        (supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment)
            ?.getMapAsync(this)

        // 중간값 위치 기준으로 주변 장소 로드
        // 사용자가 저장한 입력 위치(중간값 계산용) 기준으로 대중교통 소요시간 계산
        selectedLocation?.let {
            viewModel.loadNearbyPlaces(it)
        }
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRankMode.setOnClickListener {
            toggleRankMode()
        }

        binding.btnStartVote.setOnClickListener {
            // 순위 선택 모드로 전환
            toggleRankMode()
        }

        binding.btnProceedToVote.setOnClickListener {
            if (selectedRanks.size == 3) {
                // 최종 투표 화면으로 이동
                proceedToFinalVote()
            } else {
                Toast.makeText(this, "3개 장소를 모두 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = RecommendedPlaceAdapter(
            onPlaceClick = { place ->
                if (isRankMode) {
                    showRankSelectionDialog(place)
                } else {
                    // 순위 모드가 아닐 때는 소요시간 계산
                    val transitTimes = viewModel.transitTimes.value ?: emptyMap()
                    if (!transitTimes.containsKey(place.placeId)) {
                        viewModel.calculateTransitTimeForPlace(place)
                    }
                }
            }
        )
        binding.rvPlaces.layoutManager = LinearLayoutManager(this)
        binding.rvPlaces.adapter = adapter
        
        // 초기 데이터 설정
        viewModel.filteredPlaces.value?.let { adapter.submitList(it) }
        viewModel.transitTimes.value?.let { adapter.updateTransitTimes(it) }
        adapter.updateRankMode(isRankMode, selectedRanks)
    }

    private fun setupCategoryFilters() {
        val categories = listOf(
            PlaceCategory.ALL,
            PlaceCategory.CAFE,
            PlaceCategory.RESTAURANT,
            PlaceCategory.BAR
        )

        categories.forEach { category ->
            val button = Button(this).apply {
                text = category.displayName
                textSize = 14f
                setPadding(32, 16, 32, 16)
                background = ContextCompat.getDrawable(this@RecommendedPlaceActivity, R.drawable.bg_input_rounded)
                setTextColor(ContextCompat.getColor(this@RecommendedPlaceActivity, R.color.black))
                
                // 버튼 간격 추가
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8.dpToPx()
                }
                layoutParams = params
            }

            button.setOnClickListener {
                viewModel.filterPlacesByCategory(category)
                updateCategoryButtons(category)
            }

            binding.layoutCategoryFilters.addView(button)
        }

        // 기본 선택: 전체
        updateCategoryButtons(PlaceCategory.ALL)
    }

    private fun Int.dpToPx(): Int {
        val density = resources.displayMetrics.density
        return (this * density).toInt()
    }

    private fun updateCategoryButtons(selectedCategory: PlaceCategory) {
        for (i in 0 until binding.layoutCategoryFilters.childCount) {
            val button = binding.layoutCategoryFilters.getChildAt(i) as Button
            val category = when (button.text.toString()) {
                "전체" -> PlaceCategory.ALL
                "카페" -> PlaceCategory.CAFE
                "식당" -> PlaceCategory.RESTAURANT
                "술집" -> PlaceCategory.BAR
                else -> PlaceCategory.ALL
            }

            if (category == selectedCategory) {
                button.background = ContextCompat.getDrawable(this, R.drawable.bg_transport_selected)
                button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            } else {
                button.background = ContextCompat.getDrawable(this, R.drawable.bg_input_rounded)
                button.setTextColor(ContextCompat.getColor(this, R.color.black))
            }
        }
    }

    private fun observeViewModel() {
        viewModel.filteredPlaces.observe(this) { places ->
            adapter.submitList(places)
            updateMapMarkers(places)
        }

        viewModel.groupMembers.observe(this) { members ->
            // 그룹원들의 위치가 업데이트되면 지도 마커 업데이트
            updateMapMarkers(viewModel.filteredPlaces.value ?: emptyList())
        }

        viewModel.transitTimes.observe(this) { transitTimes ->
            // 소요시간 업데이트 시 어댑터 데이터만 업데이트 (스크롤 위치 유지)
            adapter.updateTransitTimes(transitTimes)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            // 로딩 상태 표시 (필요시)
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        isMapReady = true
        
        // 지도 확대/축소 버튼 활성화
        map.uiSettings.isZoomControlsEnabled = true

        // 지도 클릭 리스너 - 마커 표시
        map.setOnMapClickListener { latLng ->
            selectedLocation = LatLngData(latLng.latitude, latLng.longitude)
            updateMapMarkers(emptyList())
            
            // 선택한 위치 기준으로 주변 장소 다시 로드 (사용자 위치는 ViewModel에서 자동으로 가져옴)
            viewModel.loadNearbyPlaces(selectedLocation!!)
        }

        // 초기 위치로 이동
        selectedLocation?.let {
            val location = LatLng(it.lat, it.lng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
            
            // 중간값 마커 표시
            map.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("중간 지점")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        }
    }

    private fun updateMapMarkers(places: List<NearbyPlace>) {
        if (!isMapReady) return
        val map = googleMap ?: return
        map.clear()

        // 그룹원들의 위치 마커 표시 (주황색)
        viewModel.groupMembers.value?.forEach { member ->
            val location = LatLng(member.latLng.lat, member.latLng.lng)
            val displayName = member.nickname ?: member.label ?: member.uid
            map.addMarker(
                MarkerOptions()
                    .position(location)
                    .title(displayName)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        }

        // 중간값 마커 표시 (하늘색)
        selectedLocation?.let {
            val location = LatLng(it.lat, it.lng)
            map.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("중간 지점")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        }

        // 장소 마커 표시 (초록색)
        places.forEach { place ->
            val location = LatLng(place.latLng.lat, place.latLng.lng)
            map.addMarker(
                MarkerOptions()
                    .position(location)
                    .title(place.name)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
        }
    }

    private fun toggleRankMode() {
        isRankMode = !isRankMode
        
        if (isRankMode) {
            binding.btnRankMode.text = "순위 선택 취소"
            binding.btnRankMode.visibility = View.VISIBLE
            selectedRanks.clear()
            currentRank = 1
        } else {
            binding.btnRankMode.text = "순위 선택"
            selectedRanks.clear()
            currentRank = 1
        }

        // 어댑터 업데이트 (스크롤 위치 유지)
        adapter.updateRankMode(isRankMode, selectedRanks)
        
        // 하단 버튼 표시/숨김
        binding.layoutBottomButton.visibility = if (isRankMode) View.VISIBLE else View.GONE
        updateSelectedCount()
    }

    private fun showRankSelectionDialog(place: NearbyPlace) {
        if (!isRankMode) return

        // 이미 선택된 장소인지 확인
        if (selectedRanks.containsKey(place.placeId)) {
            Toast.makeText(this, "이미 선택된 장소입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 3개 초과 선택 방지
        if (selectedRanks.size >= 3) {
            Toast.makeText(this, "최대 3개 장소만 선택할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogRankSelectionBinding.inflate(layoutInflater)
        dialogBinding.tvPlaceName.text = place.name
        dialogBinding.tvMessage.text = "이 장소를 ${currentRank}순위로 등록하시겠습니까?"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnConfirm.setOnClickListener {
            // 순위 할당
            selectedRanks[place.placeId] = currentRank
            currentRank++
            
            // 어댑터 업데이트 (스크롤 위치 유지)
            adapter.updateRankMode(isRankMode, selectedRanks)
            
            updateSelectedCount()
            dialog.dismiss()
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateSelectedCount() {
        val count = selectedRanks.size
        binding.tvSelectedCount.text = "$count/3개 장소 선택됨"
        
        binding.btnProceedToVote.isEnabled = count == 3
    }

    private fun proceedToFinalVote() {
        // 선택된 장소들로 RankedPlace 생성
        val rankedPlaces = mutableListOf<RankedPlace>()
        
        viewModel.filteredPlaces.value?.forEach { place ->
            val rank = selectedRanks[place.placeId]
            if (rank != null) {
                val score = when (rank) {
                    1 -> 3
                    2 -> 2
                    3 -> 1
                    else -> 0
                }
                rankedPlaces.add(RankedPlace(place, rank, score))
            }
        }

        // 최종 투표 화면으로 이동
        val groupId = intent.getStringExtra("groupId") ?: ""
        val intent = android.content.Intent(this, FinalVoteActivity::class.java).apply {
            putExtra("groupId", groupId)
            putParcelableArrayListExtra("rankedPlaces", ArrayList(rankedPlaces.map { 
                RankedPlaceParcelable.from(it) 
            }))
        }
        startActivity(intent)
    }
}

