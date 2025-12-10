package com.moyeoyo.app.ui.place

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.PlaceCategory
import com.moyeoyo.app.data.model.RankedPlace
import com.moyeoyo.app.databinding.ActivityRecommendedPlaceBinding
import com.moyeoyo.app.databinding.DialogRankSelectionBinding
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RecommendedPlaceFragment : Fragment(), OnMapReadyCallback {

    private var _binding: ActivityRecommendedPlaceBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: RecommendedPlaceViewModel by viewModels()
    // ⭐ 최종 후보 생성을 위해 FinalVoteViewModel도 사용
    private val finalVoteViewModel: FinalVoteViewModel by viewModels()
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private lateinit var adapter: RecommendedPlaceAdapter
    private var googleMap: GoogleMap? = null
    private var isMapReady = false
    private var selectedLocation: LatLngData? = null
    private var isRankMode = false
    private var hasConfirmedRanking = false
    private var hasShownAllCompletedDialog = false
    // 💡 사용자가 의도적으로 최종 투표를 보류했는지 기억하는 플래그
    private var userDeferredFinalVote = false
    private val selectedRanks = mutableMapOf<String, Int>()
    private var currentRank = 1

    private val args: RecommendedPlaceFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityRecommendedPlaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 그룹 ID 받기
        val groupId = args.groupId
        if (groupId.isNotEmpty()) {
            viewModel.setGroupId(groupId)
        }

        // 중간값 위치 받기
        val centerLat = args.centerLat
        val centerLng = args.centerLng
        selectedLocation = if (centerLat != 0.0f && centerLng != 0.0f) {
            LatLngData(centerLat.toDouble(), centerLng.toDouble())
        } else {
            LatLngData(37.5665, 126.9780)
        }

        setupViews()
        setupRecyclerView()
        setupCategoryFilters()
        observeViewModel()

        // 지도 초기화
        (childFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment)
            ?.getMapAsync(this)

        // 중간값 위치 기준으로 주변 장소 로드
        selectedLocation?.let {
            viewModel.loadNearbyPlaces(it)
        }
        
        hasConfirmedRanking = false
        viewModel.checkUserRankingStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!hasConfirmedRanking) {
            viewModel.checkUserRankingStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupViews() {
        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.btnRankMode.setOnClickListener {
            toggleRankMode()
        }

        binding.btnStartVote.setOnClickListener {
            toggleRankMode()
        }

        binding.btnProceedToVote.setOnClickListener {
            if (!binding.btnProceedToVote.isEnabled) {
                showWaitingDialog()
                return@setOnClickListener
            }
            
            // 💡 모든 그룹원이 완료한 상태에서 버튼을 누른 경우 (나중에를 눌렀다가 다시 돌아온 경우)
            if (hasConfirmedRanking && viewModel.allUsersCompleted.value == true) {
                // 최종 후보를 생성하고 최종 투표 화면으로 이동
                android.util.Log.d("RecommendedPlaceFragment", 
                    "🔘 최종 투표 버튼 클릭 - 모든 그룹원 완료 상태, 최종 후보 생성 후 이동")
                navigateToFinalVoteWithCandidateCreation()
                return@setOnClickListener
            }
            
            // 순위 선택 중인 경우
            if (selectedRanks.size == 3) {
                showConfirmRankingDialog()
            } else {
                Toast.makeText(requireContext(), "3개 장소를 모두 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = RecommendedPlaceAdapter(
            onPlaceClick = { place ->
                if (isRankMode) {
                    showRankSelectionDialog(place)
                } else {
                    val transitTimes = viewModel.transitTimes.value ?: emptyMap()
                    if (!transitTimes.containsKey(place.placeId)) {
                        viewModel.calculateTransitTimeForPlace(place)
                    }
                }
            }
        )
        binding.rvPlaces.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPlaces.adapter = adapter
        
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
            val button = Button(requireContext()).apply {
                text = category.displayName
                textSize = 14f
                setPadding(32, 16, 32, 16)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_input_rounded)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                
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
                button.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_transport_selected)
                button.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            } else {
                button.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_input_rounded)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }
    }

    private fun observeViewModel() {
        viewModel.filteredPlaces.observe(viewLifecycleOwner) { places ->
            adapter.submitList(places)
            updateMapMarkers(places)
        }

        viewModel.groupMembers.observe(viewLifecycleOwner) { members ->
            updateMapMarkers(viewModel.filteredPlaces.value ?: emptyList())
        }

        viewModel.transitTimes.observe(viewLifecycleOwner) { transitTimes ->
            adapter.updateTransitTimes(transitTimes)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            // 로딩 상태 표시 (필요시)
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.rankingSaveSuccess.observe(viewLifecycleOwner) { success ->
            if (success) {
                Toast.makeText(requireContext(), "순위 지정이 완료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        viewModel.hasUserRanking.observe(viewLifecycleOwner) { hasRanking ->
            android.util.Log.d("RecommendedPlaceFragment", 
                "🔍 hasUserRanking 변경 - hasRanking: $hasRanking, hasConfirmedRanking: $hasConfirmedRanking")
            
            // 💡 로직 단순화: 서버에 랭킹이 있으면(hasRanking), UI를 '확정 대기' 상태로 만든다.
            if (hasRanking) {
                // UI를 '순위 확정 후 다른 멤버 대기 중' 상태로 강제 고정
                binding.tvSelectedCount.text = "다른 그룹원의 순위 확정을 기다리는 중..."
                binding.btnRankMode.isEnabled = false // 순위 선택 모드 진입 불가
                binding.btnRankMode.alpha = 0.5f
                binding.layoutBottomButton.visibility = View.VISIBLE // 하단 버튼 레이아웃은 보여주되
                binding.btnProceedToVote.isEnabled = false // '진행' 버튼은 비활성화
                binding.btnProceedToVote.alpha = 0.5f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
                
                isRankMode = false // 랭크 모드 강제 종료
                adapter.updateRankMode(false, emptyMap())
            } else {
                // 서버에 내 랭킹이 없으면, 모든 것을 초기 상태로 돌림
                binding.tvSelectedCount.text = "0/3개 장소 선택됨"
                binding.btnRankMode.isEnabled = true
                binding.btnRankMode.alpha = 1.0f
                binding.layoutBottomButton.visibility = View.GONE
            }
        }

        viewModel.allUsersCompleted.observe(viewLifecycleOwner) { allCompleted ->
            android.util.Log.d("RecommendedPlaceFragment", 
                "🔍 allUsersCompleted 변경 - allCompleted: $allCompleted, hasConfirmedRanking: $hasConfirmedRanking, userDeferredFinalVote: $userDeferredFinalVote")
            
            // 💡 분기 조건: 모든 유저가 완료했고, 내가 직접 순위를 확정했을 때
            if (allCompleted && hasConfirmedRanking) {
                if (!userDeferredFinalVote) {
                    // '보류' 상태가 아니면 버튼 활성화 및 다이얼로그 표시
                    binding.btnProceedToVote.isEnabled = true
                    binding.btnProceedToVote.alpha = 1.0f
                    binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary)
                    
                    if (view != null && !isRemoving) {
                        if (!hasShownAllCompletedDialog) {
                            hasShownAllCompletedDialog = true
                            android.util.Log.d("RecommendedPlaceFragment", 
                                "✅ 모든 사용자 완료! 확인 팝업 표시 및 화면 전환 준비")
                            showAllCompletedDialog()
                        }
                    }
                } else {
                    // '보류' 상태이면 버튼만 활성화 (다이얼로그는 표시하지 않음)
                    binding.btnProceedToVote.isEnabled = true
                    binding.btnProceedToVote.alpha = 1.0f
                    binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary)
                    android.util.Log.d("RecommendedPlaceFragment", 
                        "⏸️ 모든 사용자 완료 상태이지만 '보류' 상태 - 버튼만 활성화, 다이얼로그는 표시하지 않음")
                }
            } else {
                // 다른 조건이 만족되지 않으면 '진행' 버튼은 비활성화 상태로 유지
                binding.btnProceedToVote.isEnabled = false
                binding.btnProceedToVote.alpha = 0.5f
                binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        isMapReady = true
        
        map.uiSettings.isZoomControlsEnabled = true

        map.setOnMapClickListener { latLng ->
            selectedLocation = LatLngData(latLng.latitude, latLng.longitude)
            updateMapMarkers(emptyList())
            viewModel.loadNearbyPlaces(selectedLocation!!)
        }

        selectedLocation?.let {
            val location = LatLng(it.lat, it.lng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
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

        selectedLocation?.let {
            val location = LatLng(it.lat, it.lng)
            map.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("중간 지점")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        }

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
        // 💡 방어 코드 추가: 이미 순위를 확정했다면, 더 이상 모드를 변경할 수 없다.
        if (hasConfirmedRanking) {
            Toast.makeText(requireContext(), "이미 순위를 확정하여 변경할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRankMode = !isRankMode
        
        if (isRankMode) {
            binding.btnRankMode.text = "순위 선택 취소"
            binding.btnRankMode.visibility = View.VISIBLE
            selectedRanks.clear() // 순위 선택 모드를 처음 켤 때만 초기화
            currentRank = 1
            binding.layoutBottomButton.visibility = View.VISIBLE
            binding.btnProceedToVote.isEnabled = false
            binding.btnProceedToVote.alpha = 0.5f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
            updateSelectedCount() // 버튼 상태 업데이트를 위해 추가
        } else {
            binding.btnRankMode.text = "순위 선택"
            binding.btnRankMode.visibility = View.VISIBLE
            selectedRanks.clear() // 취소할 때도 초기화
            currentRank = 1
            if (!hasConfirmedRanking) {
                binding.layoutBottomButton.visibility = View.GONE
            }
        }

        adapter.updateRankMode(isRankMode, selectedRanks)
        if (isRankMode) {
            updateSelectedCount()
        }
    }

    private fun showRankSelectionDialog(place: NearbyPlace) {
        if (!isRankMode) return

        if (selectedRanks.containsKey(place.placeId)) {
            Toast.makeText(requireContext(), "이미 선택된 장소입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (selectedRanks.size >= 3) {
            Toast.makeText(requireContext(), "최대 3개 장소만 선택할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogRankSelectionBinding.inflate(layoutInflater)
        dialogBinding.tvPlaceName.text = place.name
        dialogBinding.tvMessage.text = "이 장소를 ${currentRank}순위로 등록하시겠습니까?"

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnConfirm.setOnClickListener {
            selectedRanks[place.placeId] = currentRank
            currentRank++
            adapter.updateRankMode(isRankMode, selectedRanks)
            updateSelectedCount()
            dialog.dismiss()
            
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
        
        if (isRankMode && count == 3) {
            binding.btnProceedToVote.isEnabled = true
            binding.btnProceedToVote.alpha = 1.0f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary)
        } else if (isRankMode) {
            binding.btnProceedToVote.isEnabled = false
            binding.btnProceedToVote.alpha = 0.5f
            binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
        }
    }

    private fun showConfirmRankingDialog() {
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
        
        AlertDialog.Builder(requireContext())
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
        val allPlaces = viewModel.places.value
        if (allPlaces.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "장소 정보를 불러올 수 없습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        
        val rankedPlaces = mutableListOf<RankedPlace>()
        
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
            Toast.makeText(requireContext(), "선택된 3개 장소 정보를 찾을 수 없습니다. 다시 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.saveUserRankings(rankedPlaces.sortedBy { it.rank })
        hasConfirmedRanking = true
        isRankMode = false
        binding.btnRankMode.text = "순위 선택"
        adapter.updateRankMode(false, emptyMap())
        selectedRanks.clear()
        currentRank = 1
        binding.layoutBottomButton.visibility = View.VISIBLE
        binding.btnProceedToVote.isEnabled = false
        binding.btnProceedToVote.alpha = 0.5f
        binding.btnProceedToVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
        binding.tvSelectedCount.text = "다른 그룹원의 순위 확정을 기다리는 중..."
    }

    private fun showWaitingDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("대기 중")
            .setMessage("현재 다른 그룹원의 최종 순위 확정을 기다리는 중입니다.\n모든 그룹원이 순위를 확정하면 최종 투표를 진행할 수 있습니다.")
            .setPositiveButton("확인", null)
            .show()
    }

    private fun showAllCompletedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("모든 그룹원 완료")
            .setMessage("모든 그룹원이 순위를 확정했습니다.\n최종 투표를 진행하시겠습니까?")
            .setPositiveButton("진행하기") { _, _ ->
                // 💡 '진행'을 누르면 '보류' 상태를 리셋하고 최종 후보를 생성한 후 이동
                userDeferredFinalVote = false
                hasShownAllCompletedDialog = false // 다시 팝업이 뜰 수 있도록 리셋
                android.util.Log.d("RecommendedPlaceFragment", 
                    "✅ '진행하기' 선택 - 보류 상태 리셋, 최종 후보 생성 후 최종 투표 화면으로 이동")
                navigateToFinalVoteWithCandidateCreation()
            }
            .setNegativeButton("나중에") { dialog, _ ->
                // 💡 '나중에'를 누르면 '보류' 상태를 true로 설정
                userDeferredFinalVote = true
                hasShownAllCompletedDialog = false // 다시 다이얼로그가 표시될 수 있도록 리셋
                android.util.Log.d("RecommendedPlaceFragment", 
                    "⏸️ '나중에' 선택 - 보류 상태로 설정, 투표 기록은 Firestore에 유지됨")
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 최종 후보를 생성하고 최종 투표 화면으로 이동
     * ⭐ '진행하기' 버튼이나 '최종 투표 버튼'을 눌렀을 때만 호출됨
     */
    private fun navigateToFinalVoteWithCandidateCreation() {
        val groupId = args.groupId
        if (groupId.isEmpty()) {
            Toast.makeText(requireContext(), "그룹 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                // ⭐ 최종 후보 생성 (FinalVoteViewModel 사용)
                android.util.Log.d("RecommendedPlaceFragment", 
                    "📥 최종 후보 생성 시작 - groupId: $groupId")
                finalVoteViewModel.loadAllUserRankingsAndCreateCandidates(groupId)
                
                // 그룹 상태 업데이트
                val currentGroup = groupRepository.getGroupDetail(groupId)
                if (currentGroup?.status == "LOCATION_DONE" || currentGroup?.status == "PLACE_RANKING") {
                    val success = groupRepository.updateGroupStatus(groupId, "PLACE_RANKING")
                    if (success) {
                        android.util.Log.d("RecommendedPlaceFragment", 
                            "✅ 그룹 상태 변경: ${currentGroup.status} → PLACE_RANKING")
                    }
                }
                
                // 약간의 지연 후 최종 투표 화면으로 이동 (Firestore 저장 완료 대기)
                kotlinx.coroutines.delay(500)
                
                android.util.Log.d("RecommendedPlaceFragment", 
                    "🚀 최종 투표 화면으로 이동 - groupId: $groupId")
                
                val action = RecommendedPlaceFragmentDirections.actionRecommendedPlaceFragmentToFinalVoteFragment(
                    groupId = groupId
                )
                findNavController().navigate(action)
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceFragment", 
                    "❌ 최종 후보 생성 및 화면 이동 중 오류: ${e.message}", e)
                Toast.makeText(requireContext(), "최종 후보 생성 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 그룹 상태만 업데이트하고 화면 이동 (더 이상 사용하지 않음)
     */
    @Deprecated("최종 후보 생성을 포함한 navigateToFinalVoteWithCandidateCreation을 사용하세요")
    private fun navigateToFinalVote() {
        navigateToFinalVoteWithCandidateCreation()
    }
}

