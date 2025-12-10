package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.LatLngData
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.map.MemberDistanceAdapter
import com.moyeoyo.app.map.MemberDistanceItem
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class VoteTabFragment : Fragment(), OnMapReadyCallback {

    private val groupId: String by lazy {
        arguments?.getString("groupId") ?: ""
    }

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var mapRepository: MapRepository
    @Inject lateinit var timeVoteRepository: TimeVoteRepository

    private val auth = FirebaseAuth.getInstance()
    private val voteViewModel: FinalVoteViewModel by viewModels()

    // 최종 위치 지도 관련 변수
    private var layoutFinalPlaceMap: View? = null
    private var recyclerMemberDistances: RecyclerView? = null
    private var memberDistanceAdapter: MemberDistanceAdapter? = null
    private var googleMap: GoogleMap? = null
    private var mapFragment: SupportMapFragment? = null

    // 그룹 상태 실시간 리스너
    private var groupStatusListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.view_vote_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 최종 위치 지도 UI
        layoutFinalPlaceMap = view.findViewById<View>(R.id.layoutFinalPlaceMap)
        recyclerMemberDistances = view.findViewById<RecyclerView>(R.id.rvMemberDistances)
        recyclerMemberDistances?.layoutManager = LinearLayoutManager(requireContext())
        memberDistanceAdapter = MemberDistanceAdapter()
        recyclerMemberDistances?.adapter = memberDistanceAdapter

        // 지도 프래그먼트 초기화
        mapFragment = childFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // 시간 버튼 → 시간 최종투표 화면으로 이동
        val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
        btnFilterTime.setOnClickListener {
            startFinalTimeVote()
        }

        // 위치 버튼 → 장소 최종투표 화면으로 이동
        val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)
        btnFilterLocation.setOnClickListener {
            startFinalPlaceVote()
        }

        // 투표 시작 버튼 (방장만 표시)
        val btnStartVoting = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartVoting)
        btnStartVoting.setOnClickListener {
            showStartVotingConfirmationDialog()
        }

        // 초기 상태 확인 및 UI 업데이트
        updateVoteTabUI(view)
        checkPlaceVoteStatus()
        
        // 그룹 상태 실시간 감지 시작
        startMonitoringGroupStatus(view)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 리스너 정리
        groupStatusListener?.remove()
        groupStatusListener = null
    }

    /**
     * 그룹 상태를 실시간으로 감지하여 UI 자동 업데이트
     */
    private fun startMonitoringGroupStatus(view: View) {
        if (groupStatusListener != null) {
            return // 이미 리스너가 등록되어 있음
        }

        val db = FirebaseFirestore.getInstance()
        val groupRef = db.collection("groups").document(groupId)

        groupStatusListener = groupRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("VoteTabFragment", "그룹 상태 리스너 오류: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                return@addSnapshotListener
            }

            val currentStatus = snapshot.getString("status")
            
            // 상태가 변경되면 UI 업데이트
            if (currentStatus != null) {
                android.util.Log.d("VoteTabFragment", "📊 그룹 상태 변경 감지: $currentStatus")
                updateVoteTabUI(view)
            }
        }
    }

    private fun updateVoteTabUI(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val confirmedTime = group?.confirmedTime
            val confirmedPlace = group?.confirmedPlace
            val status = group?.status

            val layoutInitialButtons = view.findViewById<View>(R.id.layoutInitialButtons)
            val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
            val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)

            // ⭐ 투표 시작 전 (GROUP_CREATED)이면 투표 버튼 비활성화
            val isVotingStarted = status != null && status != "GROUP_CREATED"

            // 초기 상태: 시간과 장소 모두 확정되지 않은 경우
            val isInitialState = confirmedTime == null && confirmedPlace == null

            if (isInitialState) {
                // 초기 상태: 큰 버튼들 모두 표시
                layoutInitialButtons?.visibility = View.VISIBLE
                btnFilterTime?.visibility = View.VISIBLE
                btnFilterLocation?.visibility = View.VISIBLE

                // ⭐ 투표 시작 전이면 버튼 비활성화
                if (!isVotingStarted) {
                    btnFilterTime?.isEnabled = false
                    btnFilterTime?.alpha = 0.5f
                    btnFilterLocation?.isEnabled = false
                    btnFilterLocation?.alpha = 0.5f
                } else {
                    btnFilterTime?.isEnabled = true
                    btnFilterTime?.alpha = 1.0f
                    btnFilterLocation?.isEnabled = false
                    btnFilterLocation?.alpha = 0.5f
                }

                // 후보 리스트와 투표 버튼은 숨김
                view.findViewById<RecyclerView>(R.id.rvFinalCandidates)?.visibility = View.GONE
                view.findViewById<Button>(R.id.btnSubmitVote)?.visibility = View.GONE
            } else if (confirmedTime != null && confirmedPlace == null) {
                // 시간만 확정된 경우: 시간 버튼만 숨기고 장소 버튼은 표시
                layoutInitialButtons?.visibility = View.VISIBLE
                btnFilterTime?.visibility = View.GONE
                btnFilterLocation?.visibility = View.VISIBLE

                // ⭐ 투표 시작 전이면 장소 버튼도 비활성화
                if (!isVotingStarted) {
                    btnFilterLocation?.isEnabled = false
                    btnFilterLocation?.alpha = 0.5f
                } else {
                    btnFilterLocation?.isEnabled = true
                    btnFilterLocation?.alpha = 1.0f
                }
            } else {
                // 둘 다 확정된 경우: 초기 버튼 모두 숨기기
                layoutInitialButtons?.visibility = View.GONE
            }

            // 투표 시작 버튼 표시 여부 (방장만)
            val currentUid = auth.currentUser?.uid ?: return@launch
            val isHost = group?.hostUid == currentUid
            val btnStartVoting = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnStartVoting)
            btnStartVoting?.visibility = if (isHost && status == "GROUP_CREATED") {
                View.VISIBLE
            } else {
                View.GONE
            }
        }
    }

    private fun startFinalTimeVote() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                val memberUids = group?.memberUids ?: emptyList()

                if (memberUids.isEmpty()) {
                    Toast.makeText(requireContext(), "멤버 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 현재 주의 모든 날짜 확인
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

                val datesToCheck = mutableListOf<String>()
                for (i in 0 until 7) {
                    val dateStr = dateFormat.format(calendar.time)
                    datesToCheck.add(dateStr)
                    calendar.add(Calendar.DAY_OF_MONTH, 1)
                }

                // 모든 멤버가 투표한 날짜 찾기
                var dateWithAllVoted: String? = null
                for (dateStr in datesToCheck) {
                    val allVoted = timeVoteRepository.checkAllMembersVoted(groupId, dateStr, memberUids)
                    if (allVoted) {
                        dateWithAllVoted = dateStr
                        break
                    }
                }

                if (dateWithAllVoted == null) {
                    // 아직 모든 멤버가 투표하지 않았으면 TimeVoteFragment로 이동
                    // TODO: Safe Args가 생성되면 Directions 사용
                    findNavController().navigate(
                        com.moyeoyo.app.R.id.action_groupDetailFragment_to_timeVoteFragment,
                        Bundle().apply {
                            putString("groupId", groupId)
                        }
                    )
                    return@launch
                }

                // 최종 시간 투표 화면으로 이동
                // TODO: Safe Args가 생성되면 Directions 사용
                findNavController().navigate(
                    com.moyeoyo.app.R.id.action_groupDetailFragment_to_finalTimeVoteFragment,
                    Bundle().apply {
                        putString("groupId", groupId)
                        putString("date", dateWithAllVoted)
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startFinalPlaceVote() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                when (group?.status) {
                    "LOCATION_INPUT_REQUIRED" -> {
                        // 위치 입력 화면으로 이동
                        // TODO: Safe Args가 생성되면 Directions 사용
                        findNavController().navigate(
                            com.moyeoyo.app.R.id.action_groupDetailFragment_to_locationInputFragment,
                            Bundle().apply {
                                putString("groupId", groupId)
                            }
                        )
                    }
                    "LOCATION_DONE", "PLACE_RANKING", "FINAL_PLACE_VOTE" -> {
                        // 장소 최종 투표 화면으로 이동
                        // TODO: Safe Args가 생성되면 Directions 사용
                        findNavController().navigate(
                            com.moyeoyo.app.R.id.action_groupDetailFragment_to_finalVoteFragment,
                            Bundle().apply {
                                putString("groupId", groupId)
                            }
                        )
                    }
                    else -> {
                        Toast.makeText(requireContext(), "현재 장소 투표를 진행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showStartVotingConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("투표 시작")
            .setMessage("투표를 시작하시겠습니까?\n\n투표가 시작되면 새로운 멤버 초대가 불가능합니다.")
            .setPositiveButton("시작") { _, _ ->
                startVoting()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startVoting() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = groupRepository.startVoting(groupId)
                if (success) {
                    Toast.makeText(requireContext(), "투표가 시작되었습니다.", Toast.LENGTH_SHORT).show()
                    // UI 업데이트
                    view?.let { updateVoteTabUI(it) }
                } else {
                    Toast.makeText(requireContext(), "투표 시작에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPlaceVoteStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val confirmedPlace = group?.confirmedPlace
            val confirmedTime = group?.confirmedTime

            // finalize된 그룹 (시간과 장소 모두 확정)인 경우
            if (confirmedPlace != null && confirmedTime != null) {
                val placeName = confirmedPlace["name"] as? String ?: ""
                val placeAddress = confirmedPlace["address"] as? String
                val placeLat = (confirmedPlace["latitude"] as? Number)?.toDouble()
                    ?: (confirmedPlace["lat"] as? Number)?.toDouble() ?: 0.0
                val placeLng = (confirmedPlace["longitude"] as? Number)?.toDouble()
                    ?: (confirmedPlace["lng"] as? Number)?.toDouble() ?: 0.0

                if (placeLat != 0.0 && placeLng != 0.0) {
                    val place = NearbyPlace(
                        placeId = confirmedPlace["placeId"] as? String ?: "",
                        name = placeName,
                        address = placeAddress,
                        latLng = LatLngData(placeLat, placeLng),
                        categories = emptyList(),
                        rating = null,
                        distanceMeters = 0.0
                    )
                    showFinalPlaceMap(place)

                    // 투표 UI 숨기기
                    view?.findViewById<View>(R.id.layoutVoteContainer)?.visibility = View.GONE
                }
            }
        }
    }

    private fun showFinalPlaceMap(place: NearbyPlace) {
        layoutFinalPlaceMap?.visibility = View.VISIBLE

        // 지도에 마커 표시
        googleMap?.let { map ->
            map.clear()

            val builder = LatLngBounds.Builder()
            var hasPoint = false

            // 모든 멤버의 입력 위치 가져와서 지도에 표시
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val inputLocations = mapRepository.getInputLocations(groupId)

                    // 멤버 위치 마커 표시 (주황색)
                    inputLocations.forEach { inputLocation ->
                        val position = LatLng(inputLocation.latLng.lat, inputLocation.latLng.lng)
                        val displayName = inputLocation.nickname ?: inputLocation.uid.take(8)
                        map.addMarker(
                            MarkerOptions()
                                .position(position)
                                .title(displayName)
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                        )
                        builder.include(position)
                        hasPoint = true
                    }

                    // 최종 확정된 장소 마커 표시 (빨간색)
                    val finalPosition = LatLng(place.latLng.lat, place.latLng.lng)
                    map.addMarker(
                        MarkerOptions()
                            .position(finalPosition)
                            .title(place.name)
                            .snippet(place.address ?: "최종 확정된 장소")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    builder.include(finalPosition)
                    hasPoint = true

                    // 모든 마커가 보이도록 카메라 조정
                    if (hasPoint) {
                        val bounds = builder.build()
                        val padding = 100 // 패딩 (픽셀)
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
                    } else {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(finalPosition, 15f))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VoteTabFragment", "지도 마커 표시 중 오류: ${e.message}", e)
                    // 오류 발생 시 최종 장소만 표시
                    val finalPosition = LatLng(place.latLng.lat, place.latLng.lng)
                    map.addMarker(
                        MarkerOptions()
                            .position(finalPosition)
                            .title(place.name)
                            .snippet(place.address ?: "최종 확정된 장소")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    )
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(finalPosition, 15f))
                }
            }
        }

        // 사용자별 소요시간 계산 및 표시
        calculateMemberDistances(place)
    }

    private fun calculateMemberDistances(place: NearbyPlace) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 모든 멤버의 입력 위치 가져오기
                val inputLocations = mapRepository.getInputLocations(groupId)

                if (inputLocations.isEmpty()) {
                    memberDistanceAdapter?.submitList(emptyList())
                    return@launch
                }

                // Distance Matrix API를 사용하여 실제 소요시간 계산
                val distanceResults = mapRepository.fetchDistanceMatrix(
                    origins = inputLocations,
                    destination = place.latLng
                )

                // DistanceResult를 MemberDistanceItem으로 변환
                val distances = distanceResults.map { result ->
                    val inputLocation = inputLocations.firstOrNull { it.uid == result.uid }
                    MemberDistanceItem(
                        uid = result.uid,
                        displayName = inputLocation?.nickname ?: result.uid.take(8),
                        transportMode = inputLocation?.transportMode ?: TransportMode.TRANSIT,
                        distanceMeters = result.distanceMeters,
                        durationSeconds = result.durationSeconds
                    )
                }

                memberDistanceAdapter?.submitList(distances)
            } catch (e: Exception) {
                android.util.Log.e("VoteTabFragment", "거리 계산 중 오류: ${e.message}", e)
                memberDistanceAdapter?.submitList(emptyList())
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        // 지도 확대/축소 버튼 활성화
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isZoomGesturesEnabled = true
        checkPlaceVoteStatus()
    }
}

