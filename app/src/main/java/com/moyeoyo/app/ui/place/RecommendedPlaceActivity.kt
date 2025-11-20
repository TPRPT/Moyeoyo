package com.moyeoyo.app.ui.place

import android.content.Intent
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
    private var hasConfirmedRanking = false // 순위를 확정했는지 여부
    private var hasShownAllCompletedDialog = false // 모든 사용자 완료 팝업 표시 여부 (중복 방지)
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
        
        // ⚠️ 중요: onCreate에서 hasConfirmedRanking은 항상 false로 시작
        // 이전 세션의 Firestore 데이터와 현재 세션의 상태를 구분하기 위함
        hasConfirmedRanking = false
        
        // Firestore에 이전 세션 데이터가 있는지만 확인 (hasUserRanking 설정용)
        // 하지만 hasConfirmedRanking은 현재 세션에서 실제로 확정할 때만 true가 됨
        viewModel.checkUserRankingStatus()
    }

    override fun onResume() {
        super.onResume()
        // ⚠️ 중요: onResume에서도 hasConfirmedRanking을 false로 유지
        // 현재 세션에서 실제로 확정하지 않았으면, Firestore에 데이터가 있어도 무시
        // (사용자가 다른 기기에서 확정했을 수 있으므로 checkUserRankingStatus는 호출하되,
        //  hasConfirmedRanking은 confirmAndSaveRankings()에서만 true로 설정됨)
        
        // 현재 사용자가 이미 순위를 확정했는지 다시 확인 (다른 기기에서 확정했을 수 있음)
        // 단, hasConfirmedRanking은 현재 세션에서 확정했을 때만 true이므로
        // 이전 세션 데이터가 있어도 현재 세션 상태를 유지
        if (!hasConfirmedRanking) {
            // 현재 세션에서 아직 확정하지 않았으면 Firestore 확인 (다른 기기에서 확정했을 수 있음)
            // 하지만 hasConfirmedRanking은 변경하지 않음
            viewModel.checkUserRankingStatus()
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
            if (!binding.btnProceedToVote.isEnabled) {
                // 버튼이 비활성화되어 있으면 (다른 그룹원 완료 대기 중)
                showWaitingDialog()
                return@setOnClickListener
            }
            
            if (selectedRanks.size == 3) {
                // 3순위까지 모두 지정했으면 확정 팝업 표시
                showConfirmRankingDialog()
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

        viewModel.rankingSaveSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "순위 지정이 완료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 1. 현재 "나"의 투표 상태만 관찰 (UI 업데이트용)
        viewModel.hasUserRanking.observe(this) { hasRanking ->
            // ⚠️ 핵심: hasUserRanking은 Firestore에 데이터가 있는지만 확인하는 용도
            // 여기서는 checkAllUsersCompleted()를 호출하지 않음!
            // checkAllUsersCompleted()는 오직 saveUserRankings() 내부에서만 호출됨
            
            android.util.Log.d("RecommendedPlaceActivity", 
                "🔍 hasUserRanking 변경 - hasRanking: $hasRanking, hasConfirmedRanking: $hasConfirmedRanking")
            
            // 내가 투표를 확정한 경우에만 UI 업데이트
            if (hasRanking && hasConfirmedRanking) {
                // 내가 확정했고, Firestore에도 저장된 상태
                binding.tvSelectedCount.text = "다른 그룹원의 순위 확정을 기다리는 중..."
                binding.btnProceedToVote.isEnabled = false // 다른 사람이 끝날 때까지 비활성화
                binding.btnProceedToVote.alpha = 0.5f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
                binding.btnRankMode.isEnabled = false // 순위 재선택 방지
                binding.btnRankMode.alpha = 0.5f
                
                android.util.Log.d("RecommendedPlaceActivity", 
                    "✅ 내 투표 확정 완료 - UI 업데이트 (버튼 비활성화)")
            } else {
                // 아직 확정하지 않았거나, Firestore에 데이터가 없는 경우
                android.util.Log.d("RecommendedPlaceActivity", 
                    "⏸️ 아직 확정하지 않음 - hasRanking: $hasRanking, hasConfirmedRanking: $hasConfirmedRanking")
                binding.btnProceedToVote.isEnabled = false
                binding.btnProceedToVote.alpha = 0.5f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
            }
        }

        // 2. "모든 사용자"의 완료 상태를 관찰 (화면 전환 트리거용)
        viewModel.allUsersCompleted.observe(this) { allCompleted ->
            android.util.Log.d("RecommendedPlaceActivity", 
                "🔍 allUsersCompleted 변경 - allCompleted: $allCompleted, hasConfirmedRanking: $hasConfirmedRanking")
            
            // ⚠️ 핵심 조건: 모든 사용자가 완료했고, "나 자신도" 투표를 확정한 상태일 때만!
            if (allCompleted && hasConfirmedRanking) {
                // 버튼 활성화
                binding.btnProceedToVote.isEnabled = true
                binding.btnProceedToVote.alpha = 1.0f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
                
                // ⚠️ 중요: Activity가 죽지 않았을 때만 팝업 표시
                if (!isFinishing && !isDestroyed) {
                    // 한 번만 팝업을 띄우기 위한 플래그
                    if (!hasShownAllCompletedDialog) {
                        hasShownAllCompletedDialog = true
                        android.util.Log.d("RecommendedPlaceActivity", 
                            "✅ 모든 사용자 완료! 확인 팝업 표시 및 화면 전환 준비")
                        showAllCompletedDialog()
                    }
                }
            } else {
                // 아직 모든 사용자가 완료하지 않았거나, 내가 확정하지 않은 경우
                android.util.Log.d("RecommendedPlaceActivity", 
                    "⏸️ 아직 완료되지 않음 - allCompleted: $allCompleted, hasConfirmedRanking: $hasConfirmedRanking")
                binding.btnProceedToVote.isEnabled = false
                binding.btnProceedToVote.alpha = 0.5f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
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
        // 이미 순위를 확정했다면 순위 선택 모드로 다시 들어갈 수 없음
        if (hasConfirmedRanking) {
            Toast.makeText(this, "이미 순위를 확정하셨습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRankMode = !isRankMode
        
        if (isRankMode) {
            binding.btnRankMode.text = "순위 선택 취소"
            binding.btnRankMode.visibility = View.VISIBLE
            selectedRanks.clear()
            currentRank = 1
            // 순위 선택 모드 시작 시 하단 버튼 표시
            binding.layoutBottomButton.visibility = View.VISIBLE
            binding.btnProceedToVote.isEnabled = false
            binding.btnProceedToVote.alpha = 0.5f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
        } else {
            binding.btnRankMode.text = "순위 선택"
            binding.btnRankMode.visibility = View.VISIBLE // 버튼은 계속 보이도록
            selectedRanks.clear()
            currentRank = 1
            // 순위 선택 모드 종료 시 하단 버튼 숨김 (확정하지 않은 상태에서만)
            if (!hasConfirmedRanking) {
                binding.layoutBottomButton.visibility = View.GONE
            }
        }

        // 어댑터 업데이트 (스크롤 위치 유지) - 선택된 순위 초기화
        adapter.updateRankMode(isRankMode, emptyMap())
        
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
            
            // 3순위까지 모두 지정했으면 확정 팝업 표시
            if (selectedRanks.size == 3) {
                showConfirmRankingDialog()
            }
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateSelectedCount() {
        val count = selectedRanks.size
        binding.tvSelectedCount.text = "$count/3개 장소 선택됨"
        
        // 3개 선택되었고, 아직 확정하지 않은 상태에서만 버튼 활성화
        // 확정 후에는 allUsersCompleted에 따라 활성화/비활성화
        if (isRankMode && count == 3) {
            // 순위 선택 모드 중이고 3개 선택했을 때만 활성화
            val allCompleted = viewModel.allUsersCompleted.value ?: false
            binding.btnProceedToVote.isEnabled = true
            binding.btnProceedToVote.alpha = 1.0f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary)
        } else if (isRankMode) {
            // 순위 선택 모드 중이지만 3개 미만 선택
            binding.btnProceedToVote.isEnabled = false
            binding.btnProceedToVote.alpha = 0.5f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
        }
        // isRankMode가 false일 때는 confirmAndSaveRankings에서 이미 설정함
    }

    private fun showConfirmRankingDialog() {
        // 선택된 장소 목록 만들기
        val allPlaces = viewModel.places.value ?: emptyList()
        val rankedPlacesList = mutableListOf<Pair<String, Int>>()
        
        allPlaces.forEach { place ->
            val rank = selectedRanks[place.placeId]
            if (rank != null) {
                rankedPlacesList.add(place.name to rank)
            }
        }
        
        val sortedByRank = rankedPlacesList.sortedBy { it.second }
        val message = sortedByRank.joinToString("\n") { (name, rank) ->
            "${rank}순위: $name"
        }
        
        AlertDialog.Builder(this)
            .setTitle("순위 확정")
            .setMessage("다음과 같이 순위를 지정하시겠습니까?\n\n$message\n\n확정하시면 다른 그룹원들이 순위 지정을 완료할 때까지 대기합니다.")
            .setPositiveButton("확정") { _, _ ->
                confirmAndSaveRankings()
            }
            .setNegativeButton("취소", null)
            .setCancelable(false)
            .show()
    }

    private fun confirmAndSaveRankings() {
        // 선택된 장소들로 RankedPlace 생성
        val allPlaces = viewModel.places.value
        if (allPlaces.isNullOrEmpty()) {
            Toast.makeText(this, "장소 정보를 불러올 수 없습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val rankedPlaces = mutableListOf<RankedPlace>()
        
        // 전체 장소 목록에서 사용자가 선택한 장소 찾기
        allPlaces.forEach { place ->
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

        if (rankedPlaces.size != 3) {
            Toast.makeText(this, "선택된 3개 장소 정보를 찾을 수 없습니다. 다시 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // Firestore에 저장 (모든 사용자 완료 확인은 ViewModel의 saveUserRankings 내부에서 처리)
        viewModel.saveUserRankings(rankedPlaces.sortedBy { it.rank })
        
        // 순위 확정 완료
        hasConfirmedRanking = true
        
        // 순위 선택 모드 해제 (버튼은 숨기지 않고 비활성화 상태 유지)
        isRankMode = false
        binding.btnRankMode.text = "순위 선택"
        adapter.updateRankMode(false, emptyMap())
        
        // 선택된 순위 초기화
        selectedRanks.clear()
        currentRank = 1
        
        // 하단 버튼은 계속 표시하되 비활성화 상태로 변경
        binding.layoutBottomButton.visibility = View.VISIBLE
        binding.btnProceedToVote.isEnabled = false
        binding.btnProceedToVote.alpha = 0.5f
        binding.btnProceedToVote.background = ContextCompat.getDrawable(this, R.drawable.bg_button_primary_disabled)
        binding.tvSelectedCount.text = "다른 그룹원의 순위 확정을 기다리는 중..."
        
        // checkAllUsersCompleted는 saveUserRankings 내부에서 호출되므로 여기서는 중복 호출 제거
    }

    private fun showWaitingDialog() {
        AlertDialog.Builder(this)
            .setTitle("대기 중")
            .setMessage("현재 다른 그룹원의 최종 순위 확정을 기다리는 중입니다.\n모든 그룹원이 순위를 확정하면 최종 투표를 진행할 수 있습니다.")
            .setPositiveButton("확인", null)
            .show()
    }

    /**
     * 모든 그룹원이 순위를 확정했을 때 자동으로 표시되는 확인 팝업
     */
    private fun showAllCompletedDialog() {
        AlertDialog.Builder(this)
            .setTitle("모든 그룹원 완료")
            .setMessage("모든 그룹원이 순위를 확정했습니다.\n최종 투표를 진행하시겠습니까?")
            .setPositiveButton("진행하기") { _, _ ->
                navigateToFinalVote()
            }
            .setNegativeButton("나중에", null)
            .setCancelable(false)
            .show()
    }

    /**
     * 최종 투표 화면으로 이동
     */
    private fun navigateToFinalVote() {
        // FinalVoteActivity는 Firestore에서 모든 사용자의 순위를 불러와서 집계하므로
        // groupId만 전달하면 됨 (더 안전하고 확실한 방식)
        val groupId = intent.getStringExtra("groupId") ?: ""
        if (groupId.isEmpty()) {
            Toast.makeText(this, "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        android.util.Log.d("RecommendedPlaceActivity", 
            "🚀 최종 투표 화면으로 이동 - groupId: $groupId")
        
        val intent = Intent(this, FinalVoteActivity::class.java).apply {
            putExtra("groupId", groupId)
            // 중복 실행 방지 플래그
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        // 현재 화면은 닫지 않음 (사용자가 뒤로가기를 눌러 돌아올 수 있도록)
    }

    private fun proceedToFinalVote() {
        // 모든 사용자가 완료했는지 확인
        val allCompleted = viewModel.allUsersCompleted.value ?: false
        if (!allCompleted) {
            showWaitingDialog()
            return
        }
        
        // 이미 모든 사용자가 완료한 상태이므로 바로 이동 (확인 팝업은 이미 표시됨)
        navigateToFinalVote()
    }
}

