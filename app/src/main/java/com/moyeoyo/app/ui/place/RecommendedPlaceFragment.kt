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
import com.moyeoyo.app.databinding.FragmentRecommendedPlaceBinding
import com.moyeoyo.app.databinding.DialogRankSelectionBinding
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@AndroidEntryPoint
class RecommendedPlaceFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentRecommendedPlaceBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: RecommendedPlaceViewModel by viewModels()
    // ⭐ 최종 후보 생성을 위해 FinalVoteViewModel도 사용
    private val finalVoteViewModel: FinalVoteViewModel by viewModels()
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    @Inject
    lateinit var voteRepository: com.moyeoyo.app.data.repository.VoteRepository
    
    @Inject
    lateinit var mapRepository: com.moyeoyo.app.data.repository.MapRepository
    
    private lateinit var adapter: RecommendedPlaceAdapter
    private var googleMap: GoogleMap? = null
    private var isMapReady = false
    private var selectedLocation: LatLngData? = null
    private var isRankMode = false
    private var hasConfirmedRanking = false
    private var hasShownAllCompletedDialog = false
    private var isShowingAllCompletedDialog = false // ⭐ 다이얼로그가 현재 표시 중인지 확인하는 플래그
    // 💡 사용자가 의도적으로 최종 투표를 보류했는지 기억하는 플래그
    private var userDeferredFinalVote = false
    // ⭐ 모든 멤버 순위 설정 완료 여부 (최종 투표 버튼 표시용)
    private var allMembersRanked = false
    // ⭐ 저장된 순위 (읽기 전용 표시용)
    private var savedRanks: Map<String, Int> = emptyMap()
    private val selectedRanks = mutableMapOf<String, Int>()
    private var currentRank = 1

    private val args: RecommendedPlaceFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecommendedPlaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ⭐ 상태바 설정 (다른 화면과 동일하게)
        setupStatusBar()

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
        
        hasShownAllCompletedDialog = false // ⭐ Fragment 재생성 시 플래그 초기화
        // hasConfirmedRanking은 checkUserRankingStatus()에서 설정됨
        viewModel.checkUserRankingStatus()
    }
    
    /**
     * 상태바 설정 (앱 배경색과 동일하게)
     */
    private fun setupStatusBar() {
        activity?.window?.let { window ->
            window.statusBarColor = requireContext().getColor(R.color.white)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // ⭐ 항상 사용자 랭킹 상태 확인 (후보군 투표 후에도 화면 재진입 가능하도록)
        viewModel.checkUserRankingStatus()
        
        // 저장된 순위가 있으면 진행상황 업데이트
        if (hasConfirmedRanking) {
            updateVotingProgress()
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

        // ⭐ 장소 투표 버튼이 토글 방식으로 동작 (장소 투표 <-> 선택 취소)
        binding.btnStartVote.setOnClickListener {
            toggleRankMode()
        }

        // 저장 버튼 클릭 리스너 설정
        binding.btnSaveRanking.setOnClickListener {
            // ⭐ 모든 멤버 순위 설정 완료 시 최종 투표 화면으로 이동
            if (hasConfirmedRanking && allMembersRanked) {
                navigateToFinalVote()
                return@setOnClickListener
            }
            
            if (hasConfirmedRanking) {
                Toast.makeText(requireContext(), "이미 저장된 순위는 수정할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // 순위 선택 중인 경우
            if (selectedRanks.size == 3) {
                showConfirmRankingDialog()
            } else {
                Toast.makeText(requireContext(), "3개 장소를 선택해주세요.", Toast.LENGTH_SHORT).show()
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
                // hasConfirmedRanking이 이미 true이면 중복 업데이트 방지
                if (!hasConfirmedRanking) {
                    hasConfirmedRanking = true
                }
                
                // ⭐ 저장된 순위 로드 및 표시
                loadAndDisplaySavedRanks()
                
                // UI를 '순위 확정 후 다른 멤버 대기 중' 상태로 강제 고정
                // ⭐ 장소 투표 버튼 비활성화
                binding.btnStartVote.isEnabled = false
                binding.btnStartVote.alpha = 0.5f
                binding.btnStartVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
                binding.btnStartVote.text = "장소 투표"
                
                // 하단 버튼 레이아웃 표시
                binding.layoutBottomButton.visibility = View.VISIBLE
                
                isRankMode = false // 랭크 모드 강제 종료
                adapter.updateRankMode(false, emptyMap())
                
                // ⭐ 버튼 상태 업데이트 (모든 멤버 완료 여부 확인)
                updateSelectedCount()
                
                // 다른 멤버 진행상황 확인 및 표시
                updateVotingProgress()
            } else {
                // 서버에 내 랭킹이 없으면, 모든 것을 초기 상태로 돌림
                // 단, 사용자가 현재 화면에서 저장한 경우가 아니면 초기화하지 않음
                if (!hasConfirmedRanking) {
                    hasConfirmedRanking = false
                    // ⭐ 장소 투표 버튼 활성화
                    binding.btnStartVote.isEnabled = true
                    binding.btnStartVote.alpha = 1.0f
                    binding.btnStartVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary)
                    binding.btnStartVote.text = "장소 투표"
                    binding.layoutBottomButton.visibility = View.GONE
                }
            }
        }

        viewModel.allUsersCompleted.observe(viewLifecycleOwner) { allCompleted ->
            android.util.Log.d("RecommendedPlaceFragment", 
                "🔍 allUsersCompleted 변경 - allCompleted: $allCompleted, hasConfirmedRanking: $hasConfirmedRanking, userDeferredFinalVote: $userDeferredFinalVote")
            
            // ⭐ 모든 멤버 순위 설정 완료 여부 업데이트
            allMembersRanked = allCompleted && hasConfirmedRanking
            
            // 버튼 상태 업데이트
            updateSelectedCount()
            
            // 모든 멤버가 완료했으면 진행상황 업데이트
            if (allCompleted && hasConfirmedRanking) {
                binding.tvVotingProgress.visibility = View.GONE
                
                // 모든 그룹원이 완료했을 때 다이얼로그 표시 (한 번만, 그리고 현재 표시 중이 아닐 때만)
                if (!hasShownAllCompletedDialog && !isShowingAllCompletedDialog && !userDeferredFinalVote) {
                    hasShownAllCompletedDialog = true
                    isShowingAllCompletedDialog = true
                    showAllCompletedDialog()
                    android.util.Log.d("RecommendedPlaceFragment", 
                        "✅ 모든 사용자 완료! 다이얼로그 표시")
                }
            } else if (hasConfirmedRanking) {
                // 저장된 순위가 있으면 진행상황 업데이트
                updateVotingProgress()
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
            // ⭐ 순위 선택 모드 진입: 버튼 텍스트를 "선택 취소"로 변경
            binding.btnStartVote.text = "선택 취소"
            selectedRanks.clear() // 순위 선택 모드를 처음 켤 때만 초기화
            currentRank = 1
            binding.layoutBottomButton.visibility = View.VISIBLE
            updateSelectedCount() // 버튼 상태 업데이트를 위해 추가
        } else {
            // ⭐ 순위 선택 모드 취소: 버튼 텍스트를 "장소 투표"로 변경
            binding.btnStartVote.text = "장소 투표"
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

    /**
     * 저장된 순위 로드 및 표시
     */
    private fun loadAndDisplaySavedRanks() {
        val groupId = args.groupId
        if (groupId.isEmpty()) return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val userRankings = mapRepository.getUserRankings(
                    groupId,
                    uid,
                    com.google.firebase.firestore.Source.SERVER
                )
                
                // 저장된 순위를 Map으로 변환 (placeId -> rank)
                savedRanks = userRankings.associate { rankedPlace ->
                    rankedPlace.place.placeId to rankedPlace.rank
                }
                
                // 어댑터에 저장된 순위 전달
                adapter.updateSavedRanks(savedRanks)
                
                android.util.Log.d("RecommendedPlaceFragment", 
                    "✅ 저장된 순위 로드 완료: ${savedRanks.size}개")
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceFragment", 
                    "❌ 저장된 순위 로드 실패: ${e.message}", e)
            }
        }
    }

    private fun updateSelectedCount() {
        // ⭐ 모든 멤버 순위 설정 완료 시 최종 투표로 이동하기 버튼 표시
        if (hasConfirmedRanking && allMembersRanked) {
            binding.btnSaveRanking.isEnabled = true
            binding.btnSaveRanking.text = "최종 투표로 이동하기"
            binding.btnSaveRanking.alpha = 1.0f
            return
        }
        
        // ⭐ 저장된 순위가 있지만 모든 멤버가 투표하지 않은 경우
        if (hasConfirmedRanking) {
            binding.btnSaveRanking.isEnabled = false
            binding.btnSaveRanking.text = "저장 완료"
            binding.btnSaveRanking.alpha = 0.5f
            return
        }
        
        val count = selectedRanks.size
        
        // 저장 버튼 텍스트 업데이트
        val buttonText = if (count > 0) {
            "저장 (${count}개 장소 선택됨)"
        } else {
            "저장"
        }
        binding.btnSaveRanking.text = buttonText
        
        // 저장 버튼 활성화/비활성화 (3개 선택했을 때만 활성화)
        binding.btnSaveRanking.isEnabled = count == 3 && !hasConfirmedRanking
        binding.btnSaveRanking.alpha = if (count == 3 && !hasConfirmedRanking) 1.0f else 0.5f
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

        // ⭐ 저장 전에 즉시 UI 업데이트
        hasConfirmedRanking = true
        isRankMode = false
        adapter.updateRankMode(false, emptyMap())
        selectedRanks.clear()
        currentRank = 1
        
        // 장소 투표 버튼 비활성화
        binding.btnStartVote.isEnabled = false
        binding.btnStartVote.alpha = 0.5f
        binding.btnStartVote.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_button_primary_disabled)
        binding.btnStartVote.text = "장소 투표"
        
        // 하단 버튼 레이아웃 표시
        binding.layoutBottomButton.visibility = View.VISIBLE
        
        // 저장 실행
        viewModel.saveUserRankings(rankedPlaces.sortedBy { it.rank })
        
        // ⭐ 저장 후 버튼 상태 업데이트 (모든 멤버 완료 여부 확인)
        // allUsersCompleted observer에서 자동으로 업데이트되지만, 즉시 확인도 수행
        updateSelectedCount()
        
        // 다른 멤버 진행상황 확인 및 표시
        updateVotingProgress()
    }
    
    /**
     * 다른 멤버들의 투표 진행상황 업데이트
     */
    private fun updateVotingProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupDetail(args.groupId)
                val memberUids = group?.memberUids ?: emptyList()
                
                if (memberUids.isEmpty()) {
                    binding.tvVotingProgress.visibility = View.GONE
                    return@launch
                }
                
                // VoteRepository를 통해 rankedUsers 확인
                val voteSnapshot = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("groups")
                    .document(args.groupId)
                    .collection("placeVote")
                    .document("placeVote")
                    .get()
                    .await()
                val rankedUsers = voteSnapshot.get("rankedUsers") as? List<*> ?: emptyList<Any>()
                
                val missingCount = memberUids.count { it !in rankedUsers }
                
                if (missingCount > 0) {
                    binding.tvVotingProgress.text = "⏳ 아직 ${missingCount}명의 그룹원이 순위를 확정하지 않았습니다"
                    binding.tvVotingProgress.visibility = View.VISIBLE
                } else {
                    // 모든 멤버가 완료
                    binding.tvVotingProgress.visibility = View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.e("RecommendedPlaceFragment", "투표 진행상황 업데이트 중 오류: ${e.message}", e)
            }
        }
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
                isShowingAllCompletedDialog = false // 다이얼로그 닫힘 표시
                // ⚠️ '진행하기'를 누르면 더 이상 다이얼로그를 표시하지 않음 (hasShownAllCompletedDialog는 true 유지)
                android.util.Log.d("RecommendedPlaceFragment", 
                    "✅ '진행하기' 선택 - 보류 상태 리셋, 최종 후보 생성 후 최종 투표 화면으로 이동")
                navigateToFinalVoteWithCandidateCreation()
            }
            .setNegativeButton("나중에") { dialog, _ ->
                // 💡 '나중에'를 누르면 '보류' 상태를 true로 설정
                userDeferredFinalVote = true
                hasShownAllCompletedDialog = false // 다시 다이얼로그가 표시될 수 있도록 리셋
                isShowingAllCompletedDialog = false // 다이얼로그 닫힘 표시
                android.util.Log.d("RecommendedPlaceFragment", 
                    "⏸️ '나중에' 선택 - 보류 상태로 설정, 투표 기록은 Firestore에 유지됨")
                dialog.dismiss()
            }
            .setOnDismissListener {
                isShowingAllCompletedDialog = false // 다이얼로그가 닫힐 때 플래그 리셋
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
                
                // ⭐ 최종 후보 생성이 완료될 때까지 대기 (순위 데이터가 비어있을 수 있으므로 약간의 지연)
                kotlinx.coroutines.delay(1000)
                
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
                // ⚠️ 순위 데이터가 비어있을 때는 정상적인 상황이므로 오류 메시지를 표시하지 않음
                val errorMessage = e.message ?: ""
                if (errorMessage.contains("순위 지정 데이터가 아직 없습니다") || 
                    errorMessage.contains("최종 후보를 생성할 수 없습니다")) {
                    android.util.Log.d("RecommendedPlaceFragment", 
                        "ℹ️ 순위 데이터가 아직 없음 (정상적인 상황): ${e.message}")
                    // 정상적인 상황이므로 그냥 진행
                } else {
                    android.util.Log.e("RecommendedPlaceFragment", 
                        "❌ 최종 후보 생성 및 화면 이동 중 오류: ${e.message}", e)
                    // 실제 오류인 경우에만 Toast 표시
                    if (isAdded && view != null) {
                        Toast.makeText(requireContext(), "최종 후보 생성 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
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

