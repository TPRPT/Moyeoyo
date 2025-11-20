package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.MapRepository
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.ui.groups.adapter.MemberListAdapter
import com.moyeoyo.app.ui.place.FinalCandidate
import com.moyeoyo.app.ui.place.FinalCandidateAdapter
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import com.moyeoyo.app.ui.location.LocationInputActivity
import com.moyeoyo.app.ui.time.TimeVoteActivity
import com.moyeoyo.app.ui.time.FinalTimeVoteActivity
import com.moyeoyo.app.ui.time.FinalTimeAdapter
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.ui.vote.ConfirmActivity
import com.moyeoyo.app.map.MemberDistanceAdapter
import com.moyeoyo.app.map.MemberDistanceItem
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.data.model.TransportMode
import com.moyeoyo.app.data.model.InputLocation
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.data.model.LatLngData
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.moyeoyo.app.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.moyeoyo.app.ui.groups.GroupManageActivity


@AndroidEntryPoint
class GroupDetailActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGroupDetailBinding

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository
    @Inject lateinit var timeVoteRepository: TimeVoteRepository
    @Inject lateinit var mapRepository: MapRepository

    private val auth = FirebaseAuth.getInstance()
    private val voteViewModel: FinalVoteViewModel by viewModels()

    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var currentUid: String

    // 최종 위치 지도 관련 변수
    private var layoutFinalPlaceMap: View? = null
    private var recyclerMemberDistances: RecyclerView? = null
    private var memberDistanceAdapter: MemberDistanceAdapter? = null
    private var googleMap: GoogleMap? = null
    private var mapFragment: SupportMapFragment? = null

    // 멤버 탭 관련 변수
    private lateinit var recyclerMemberList: RecyclerView
    private lateinit var btnInviteMember: View
    private var isMemberTabInitialized = false
    private var pendingMemberState: PendingMemberState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Unknown Group"
        currentUid = auth.currentUser?.uid ?: return finish()

        binding.tvGroupName.text = groupName

        setupToolbar()
        setupButtons()
        setupTabs()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupButtons() {
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteGroupConfirmationDialog()
        }
//        binding.btnLeaveGroup.setOnClickListener {
//            showLeaveGroupConfirmationDialog()
//        }
    }

    // ---------------------------
    //  ViewPager2 / 탭 설정
    // ---------------------------
    private fun setupTabs() {
        val views = listOf(
            R.layout.view_vote_tab,      // 0: 투표 탭
            R.layout.view_member_tab     // 1: 멤버 탭
        )

        binding.viewPager.adapter = GroupDetailPagerAdapter(
            this,
            views
        ) { view, position ->
            if (position == 0) bindVoteTab(view)
            else bindMemberTab(view)
        }

        // 탭 전환
        binding.tabGroup.setOnCheckedChangeListener { _, checkedId ->
            binding.viewPager.currentItem =
                if (checkedId == R.id.tabPlace) 0 else 1
        }

        binding.viewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    binding.tabGroup.check(
                        if (position == 0) R.id.tabPlace else R.id.tabMember
                    )
                }
            }
        )
    }

    // ---------------------------
    //  투표 탭(view_vote_tab.xml)
    // ---------------------------
    private fun bindVoteTab(view: View) {
        // 최종 위치 지도 UI
        layoutFinalPlaceMap = view.findViewById<View>(R.id.layoutFinalPlaceMap)
        recyclerMemberDistances = view.findViewById<RecyclerView>(R.id.rvMemberDistances)
        recyclerMemberDistances?.layoutManager = LinearLayoutManager(this)
        memberDistanceAdapter = MemberDistanceAdapter()
        recyclerMemberDistances?.adapter = memberDistanceAdapter

        // 지도 프래그먼트 초기화
        mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
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

        // 초기 상태 확인 및 UI 업데이트
        updateVoteTabUI(view)
        checkPlaceVoteStatus()
    }
    
    private fun updateVoteTabUI(view: View) {
        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val confirmedTime = group?.confirmedTime
            val confirmedPlace = group?.confirmedPlace
            
            val layoutInitialButtons = view.findViewById<View>(R.id.layoutInitialButtons)
            val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
            val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)
            
            // 초기 상태: 시간과 장소 모두 확정되지 않은 경우
            val isInitialState = confirmedTime == null && confirmedPlace == null
            
            if (isInitialState) {
                // 초기 상태: 큰 버튼들 모두 표시
                layoutInitialButtons?.visibility = View.VISIBLE
                btnFilterTime?.visibility = View.VISIBLE
                btnFilterLocation?.visibility = View.VISIBLE
                // 후보 리스트와 투표 버튼은 숨김
                view.findViewById<RecyclerView>(R.id.rvFinalCandidates)?.visibility = View.GONE
                view.findViewById<Button>(R.id.btnSubmitVote)?.visibility = View.GONE
            } else if (confirmedTime != null && confirmedPlace == null) {
                // 시간만 확정된 경우: 시간 버튼만 숨기고 장소 버튼은 표시
                layoutInitialButtons?.visibility = View.VISIBLE
                btnFilterTime?.visibility = View.GONE
                btnFilterLocation?.visibility = View.VISIBLE
            } else {
                // 둘 다 확정된 경우: 초기 버튼 모두 숨기기
                layoutInitialButtons?.visibility = View.GONE
            }
        }
    }
    
    private fun startFinalTimeVote() {
        lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                val memberUids = group?.memberUids ?: emptyList()

                if (memberUids.isEmpty()) {
                    Toast.makeText(this@GroupDetailActivity, "멤버 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
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
                    // 아직 모든 멤버가 투표하지 않았으면 TimeVoteActivity로 이동
                    val intent = Intent(this@GroupDetailActivity, TimeVoteActivity::class.java).apply {
                        putExtra("groupId", groupId)
                    }
                    startActivity(intent)
                    return@launch
        }

                // 최종 시간 투표 화면으로 이동
                val intent = Intent(this@GroupDetailActivity, FinalTimeVoteActivity::class.java).apply {
                    putExtra("groupId", groupId)
                    putExtra("date", dateWithAllVoted)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@GroupDetailActivity, "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun startFinalPlaceVote() {
        lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupById(groupId)
                when (group?.status) {
                    "LOCATION_INPUT_REQUIRED" -> {
                        // 위치 입력 화면으로 이동
                        val intent = Intent(this@GroupDetailActivity, LocationInputActivity::class.java).apply {
                            putExtra("groupId", groupId)
                        }
                        startActivity(intent)
                    }
                    "LOCATION_DONE", "PLACE_RANKING", "FINAL_PLACE_VOTE" -> {
                        // 장소 최종 투표 화면으로 이동
                        val intent = Intent(this@GroupDetailActivity, com.moyeoyo.app.ui.place.FinalVoteActivity::class.java).apply {
                            putExtra("groupId", groupId)
                        }
                        startActivity(intent)
                    }
                    else -> {
                        Toast.makeText(this@GroupDetailActivity, "현재 장소 투표를 진행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this@GroupDetailActivity, "오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------
    //  멤버 탭(view_member_tab.xml)
    // ---------------------------
    private fun bindMemberTab(view: View) {
        recyclerMemberList = view.findViewById<RecyclerView>(R.id.recyclerMemberList)
        recyclerMemberList.layoutManager = LinearLayoutManager(this)

        btnInviteMember = view.findViewById<View>(R.id.btnInviteMember)
        btnInviteMember.setOnClickListener {
            navigateToInviteScreen()
        }

        // 멤버 탭이 이제 준비됨
        isMemberTabInitialized = true

        // 데이터가 먼저 로딩되어 pending된 경우 → 지금 적용
        pendingMemberState?.let { state ->
            applyMemberList(state)
            pendingMemberState = null
        }
    }

    // ---------------------------
    //  그룹 데이터 로딩
    // ---------------------------
    override fun onResume() {
        super.onResume()
        loadGroupData()
        // 투표 탭이 현재 표시되어 있으면 UI 업데이트
        val currentPosition = binding.viewPager.currentItem
        if (currentPosition == 0) {
            val voteTabView = binding.viewPager.getChildAt(0)
            voteTabView?.let { updateVoteTabUI(it) }
        }
    }

    private fun loadGroupData() {
        binding.tvMemberCount.text = "로딩 중..."

        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                val isHost = (group.hostUid == currentUid)
                binding.tvMemberCount.text = "${group.memberUids.size}명"

                displayConfirmedSchedule(group)

                // 버튼 표시/동작 분기
                if (isHost) {
                    // 호스트: "그룹 관리" 버튼만 보이게
                    binding.btnDeleteGroup.visibility = View.GONE
                    binding.btnLeaveGroup.visibility = View.VISIBLE
                    binding.btnLeaveGroup.text = "그룹 관리"
                    binding.btnLeaveGroup.setOnClickListener {
                        navigateToGroupManageScreen(group.id, group.groupName)
                    }
                } else {
                    // 일반 멤버: 기존처럼 "그룹 나가기"
                    binding.btnDeleteGroup.visibility = View.GONE
                    binding.btnLeaveGroup.visibility = View.VISIBLE
                    binding.btnLeaveGroup.text = "그룹 나가기"
                    binding.btnLeaveGroup.setOnClickListener {
                        showLeaveGroupConfirmationDialog()
                    }
                }

                // 상태에 따른 버튼 활성화 제어
                updateButtonsByStatus(group.status)

                // 팀원 목록 표시
                displayMemberList(group.memberUids, group.hostUid, isHost)
            } else {
                Toast.makeText(
                    this@GroupDetailActivity,
                    "그룹 정보를 불러올 수 없습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------------------------
    //  확정된 일정(다음 모임)
    // ---------------------------
    private fun displayConfirmedSchedule(group: Group) {
        val confirmedTime = group.confirmedTime
        val confirmedPlace = group.confirmedPlace

        // 장소가 확정되면 확정된 시간 블록 숨기기
        if (confirmedPlace != null) {
            binding.confirmedTimeSection.visibility = View.GONE
        } else if (confirmedTime != null) {
            // 시간만 확정된 경우
            binding.confirmedTimeSection.visibility = View.VISIBLE
            binding.textConfirmedTimeOnly.text = "일시: ${formatTimestamp(confirmedTime)}"
        } else {
            binding.confirmedTimeSection.visibility = View.GONE
        }

        // 장소 확정 블록 표시 (시간 확정 후에만 표시)
        if (confirmedTime != null && confirmedPlace != null) {
            // 다음 모임 카드 표시 (최종 확정된 일정 블록 대신)
            binding.nextMeetingCard.visibility = View.VISIBLE
            binding.tvMeetingDateAndTime.text = formatTimestamp(confirmedTime)
            binding.tvMeetingLocation.text =
                confirmedPlace["name"] as? String ?: "장소 없음"

            binding.btnAddToCalendarWrapper.setOnClickListener {
                val intent = Intent(this, ConfirmActivity::class.java)
                intent.putExtra("GROUP_ID", groupId)
                intent.putExtra("GROUP_NAME", groupName)
                startActivity(intent)
            }
        } else {
            binding.nextMeetingCard.visibility = View.GONE
        }
    }

    private fun displayConfirmedSchedule(confirmedTime: Timestamp?, confirmedPlace: Map<String, Any>?) {
        // 장소가 확정되면 확정된 시간 블록 숨기기
            if (confirmedPlace != null) {
            binding.confirmedTimeSection.visibility = View.GONE
        } else if (confirmedTime != null) {
            // 시간만 확정된 경우
            binding.confirmedTimeSection.visibility = View.VISIBLE
            binding.textConfirmedTimeOnly.text = "일시: ${formatTimestamp(confirmedTime)}"
            } else {
            binding.confirmedTimeSection.visibility = View.GONE
        }

        // 장소 확정 블록 표시
        if (confirmedTime != null && confirmedPlace != null) {
            // 다음 모임 카드 표시 (최종 확정된 일정 블록 대신)
            binding.nextMeetingCard.visibility = View.VISIBLE
            binding.tvMeetingDateAndTime.text = formatTimestamp(confirmedTime)
            binding.tvMeetingLocation.text =
                confirmedPlace["name"] as? String ?: "장소 없음"

            binding.btnAddToCalendarWrapper.setOnClickListener {
                val intent = Intent(this, ConfirmActivity::class.java)
                intent.putExtra("GROUP_ID", groupId)
                intent.putExtra("GROUP_NAME", groupName)
                startActivity(intent)
            }
        } else {
            binding.nextMeetingCard.visibility = View.GONE
        }
    }

    private fun formatTimestamp(timestamp: Timestamp): String {
        val sdf = SimpleDateFormat("yyyy년 M월 d일 (E) a h:mm", Locale.getDefault())
        return sdf.format(timestamp.toDate())
    }

    // ---------------------------
    //  멤버 리스트 (데이터 로딩)
    // ---------------------------
    /**
     * 그룹 상태에 따라 버튼 활성화 제어
     */
    private fun updateButtonsByStatus(status: String?) {
        android.util.Log.d("GroupDetailActivity", "📊 그룹 상태: $status")

        // 시간/장소 투표 버튼은 제거되었으므로 이 함수는 더 이상 사용하지 않음
        // 대신 시간/위치 필터 버튼의 활성화를 제어
        val btnFilterTime = findViewById<View>(R.id.btnFilterTime)
        val btnFilterLocation = findViewById<View>(R.id.btnFilterLocation)

        when (status) {
            "GROUP_CREATED",
            "TIME_VOTE_REQUIRED",
            "TIME_FINALIZING" -> {
                // 시간 투표 단계 - 시간 버튼만 활성화
                btnFilterTime?.isEnabled = true
                btnFilterLocation?.isEnabled = false
                btnFilterLocation?.alpha = 0.5f
            }
            "LOCATION_INPUT_REQUIRED",
            "LOCATION_DONE",
            "PLACE_RANKING",
            "FINAL_PLACE_VOTE",
            "FINALIZED" -> {
                // 장소 투표 단계 (시간 확정 완료) - 위치 버튼 활성화
                btnFilterTime?.isEnabled = false
                btnFilterTime?.alpha = 0.5f
                btnFilterLocation?.isEnabled = true
            }
            else -> {
                // 기본값 - 시간 버튼 활성화
                btnFilterTime?.isEnabled = true
                btnFilterLocation?.isEnabled = false
                btnFilterLocation?.alpha = 0.5f
            }
        }
    }

    /**
     * 팀원 목록을 표시하고 방장에게 강퇴 버튼을 제공합니다.
     */
    private fun displayMemberList(memberUids: List<String>, hostUid: String, isHost: Boolean) {
        lifecycleScope.launch {
            val nicknames = memberUids.map { uid ->
                async { uid to (friendRepository.getUserNickname(uid) ?: uid.take(8)) }
            }.awaitAll()

            val state = PendingMemberState(
                members = nicknames,
                hostUid = hostUid,
                isHost = isHost
            )

            // 멤버 탭이 아직 초기화되지 않았다면 → 나중에 적용
            if (!isMemberTabInitialized || !this@GroupDetailActivity::recyclerMemberList.isInitialized) {
                pendingMemberState = state
                return@launch
            }

            // 이미 탭이 준비된 상태라면 바로 적용
            applyMemberList(state)
        }
    }

    // ---------------------------
    //  멤버 리스트 실제 UI 반영
    // ---------------------------
    private fun applyMemberList(state: PendingMemberState) {
        if (!this::recyclerMemberList.isInitialized) return

        recyclerMemberList.adapter = MemberListAdapter(
            state.members,
            state.hostUid,
            currentUid,
            state.isHost
        ) { uid, name ->
            showKickConfirmationDialog(uid, name)
        }
    }

    // ---------------------------
    //  초대 화면 이동
    // ---------------------------
    private fun navigateToInviteScreen() {
        val intent = Intent(this, GroupInviteActivity::class.java)
        intent.putExtra("GROUP_ID", groupId)
        intent.putExtra("GROUP_NAME", groupName)
        startActivity(intent)
    }

    // ---------------------------
    //  그룹 관리 화면 이동 (호스트 전용)
    // ---------------------------
    private fun navigateToGroupManageScreen(groupId: String, groupName: String) {
        val intent = Intent(this, GroupManageActivity::class.java).apply {
            putExtra("GROUP_ID", groupId)
            putExtra("GROUP_NAME", groupName)
        }
        startActivity(intent)
    }

    // ---------------------------
    //  강퇴 처리
    // ---------------------------
    private fun showKickConfirmationDialog(uid: String, nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("강퇴")
            .setMessage("${nickname} 님을 강퇴할까요?")
            .setPositiveButton("강퇴") { _, _ ->
                lifecycleScope.launch {
                    if (groupRepository.removeMember(groupId, uid)) {
                        Toast.makeText(this@GroupDetailActivity, "강퇴 완료", Toast.LENGTH_SHORT).show()
                        loadGroupData()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showDeleteGroupConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 삭제")
            .setMessage("정말로 '$groupName' 그룹을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showLeaveGroupConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 나가기")
            .setMessage("'$groupName' 그룹을 나가시겠습니까?")
            .setPositiveButton("나가기") { _, _ ->
                leaveGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteGroup() {
        lifecycleScope.launch {
            val success = groupRepository.deleteGroup(groupId)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "'$groupName' 그룹을 삭제했습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun leaveGroup() {
        lifecycleScope.launch {
            val success = groupRepository.leaveGroup(groupId)

            if (success) {
                Toast.makeText(this@GroupDetailActivity, "'$groupName' 그룹을 나왔습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupDetailActivity, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this@GroupDetailActivity, "그룹 나가기에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startLocationInput() {
        val intent = Intent(this, LocationInputActivity::class.java).apply {
            putExtra("groupId", groupId)
        }
        startActivity(intent)
    }

    private fun checkPlaceVoteStatus() {
        lifecycleScope.launch {
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
                    val voteTabView = binding.viewPager.getChildAt(0)
                    voteTabView?.findViewById<View>(R.id.layoutVoteContainer)?.visibility = View.GONE
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
            lifecycleScope.launch {
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
                    android.util.Log.e("GroupDetailActivity", "지도 마커 표시 중 오류: ${e.message}", e)
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
        lifecycleScope.launch {
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
                android.util.Log.e("GroupDetailActivity", "거리 계산 중 오류: ${e.message}", e)
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

// 멤버 목록 상태를 저장하는 데이터 클래스
private data class PendingMemberState(
    val members: List<Pair<String, String>>, // (uid, nickname) 리스트
    val hostUid: String,
    val isHost: Boolean
)
