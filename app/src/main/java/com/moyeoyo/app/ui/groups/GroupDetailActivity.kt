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
import com.google.firebase.Timestamp
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.ui.groups.adapter.MemberListAdapter
import com.moyeoyo.app.ui.place.FinalCandidate
import com.moyeoyo.app.ui.place.FinalCandidateAdapter
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import com.moyeoyo.app.ui.location.LocationInputActivity
import com.moyeoyo.app.ui.vote.ConfirmActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository


    @Inject
    lateinit var groupRepository: GroupRepository

    @Inject
    lateinit var friendRepository: FriendRepository

    private val auth = FirebaseAuth.getInstance()

    private lateinit var groupId: String
    private lateinit var groupName: String
    private lateinit var currentUid: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("GROUP_ID") ?: return finish()
        groupName = intent.getStringExtra("GROUP_NAME") ?: "Unknown Group"
        currentUid = auth.currentUser?.uid ?: return finish()

        binding.tvGroupName.text = groupName

        setupToolbar()
        setupTabs()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
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
        recyclerFinalCandidates = view.findViewById(R.id.rvFinalCandidates)
        tvVoteStatus = view.findViewById(R.id.tvVoteStatus)
        btnSubmitVote = view.findViewById(R.id.btnSubmitVote)

        recyclerFinalCandidates.layoutManager = LinearLayoutManager(this)

        // 위치 버튼 → LocationInputActivity 이동
        val btnFilterLocation = view.findViewById<View>(R.id.btnFilterLocation)
        btnFilterLocation.setOnClickListener {
            val intent = Intent(this, LocationInputActivity::class.java)
            intent.putExtra("groupId", groupId)
        // 시간 투표 버튼 리스너
        binding.btnTimeVote.setOnClickListener {
            val intent = Intent(this, com.moyeoyo.app.ui.time.TimeVoteActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
        }

        // 장소 투표 버튼 리스너
        binding.btnPlaceVote.setOnClickListener {
            // LocationInputActivity로 이동하여 위치 선택 화면 표시
            val intent = Intent(this, LocationInputActivity::class.java).apply {
                putExtra("groupId", groupId)
            }
            startActivity(intent)
        }

        // 어댑터 설정
        finalVoteAdapter = FinalCandidateAdapter { candidate ->
            // 선택 토글
            selectedCandidate = if (selectedCandidate == candidate) null else candidate

            // UI에서 선택 상태 반영
            finalCandidates.forEachIndexed { index, item ->
                finalCandidates[index] = item.copy(
                    isSelected = (item.place.placeId == selectedCandidate?.place?.placeId)
                )
            }

            finalVoteAdapter.submitList(finalCandidates.toList())
            btnSubmitVote.visibility =
                if (selectedCandidate != null) View.VISIBLE else View.GONE

            // 선택된 장소의 대중교통 시간 계산
            selectedCandidate?.let {
                voteViewModel.calculateTransitTimeForPlace(it.place)
            }
        }

        recyclerFinalCandidates.adapter = finalVoteAdapter

        if (!isVoteTabInitialized) {
            observeVoteViewModel()
            startVoteLoading()
            isVoteTabInitialized = true
        }

        btnSubmitVote.setOnClickListener {
            val candidate = selectedCandidate
            if (candidate == null) {
                Toast.makeText(this, "장소를 선택해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            voteViewModel.submitVote(groupId, candidate.place.placeId)
        }
    }

    private fun startVoteLoading() {
        voteViewModel.startListeningToVoteStatus(groupId)
        voteViewModel.loadAllUserRankingsAndCreateCandidates(groupId)
    }

    private fun observeVoteViewModel() {
        // 최종 후보 리스트
        voteViewModel.finalCandidates.observe(this) { candidates ->
            finalCandidates.clear()
            finalCandidates.addAll(candidates)
            finalVoteAdapter.submitList(candidates.toList())
        }

        // 투표 상태
        voteViewModel.voteStatus.observe(this) { status ->
            tvVoteStatus.text = "투표 상태: ${status.completed}/${status.total} 명 완료"
        }

        // 대중교통 시간
        voteViewModel.transitTimes.observe(this) { transitTimes ->
            finalVoteAdapter.updateTransitTimes(transitTimes)
        }

        // 투표 성공
        voteViewModel.voteSuccess.observe(this) { success ->
            if (success) {
                Toast.makeText(this, "투표를 완료했습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        // 에러
        voteViewModel.error.observe(this) { msg ->
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        }

        // 승리 장소
        voteViewModel.winningPlace.observe(this) { place ->
            place?.let {
                AlertDialog.Builder(this)
                    .setTitle("최종 약속 장소 확정")
                    .setMessage("최종 약속 장소는 \"${it.name}\"로 결정되었습니다.")
                    .setPositiveButton("확인", null)
                    .show()
            }
        }
    }

    // ---------------------------
    //  멤버 탭(view_member_tab.xml)
    // ---------------------------
    private fun bindMemberTab(view: View) {
        recyclerMemberList = view.findViewById(R.id.recyclerMemberList)
        recyclerMemberList.layoutManager = LinearLayoutManager(this)

        btnInviteMember = view.findViewById(R.id.btnInviteMember)
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
    }

    private fun loadGroupData() {
        binding.tvMemberCount.text = "로딩 중..."

        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                val isHost = (group.hostUid == currentUid)
                binding.tvMemberCount.text = "${group.memberUids.size}명"

                displayConfirmedSchedule(group)

                // 삭제/나가기 버튼
                binding.btnDeleteGroup.visibility = if (isHost) View.VISIBLE else View.GONE
                binding.btnLeaveGroup.visibility = if (!isHost) View.VISIBLE else View.GONE

                // 팀원 표시
                // 4. 상태에 따른 버튼 활성화 제어
                updateButtonsByStatus(group.status)

                // 5. 팀원 목록 표시
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

        if (confirmedTime != null) {
            // 시간이 확정된 경우: 섹션을 표시하고 텍스트를 업데이트
            val formattedTime = formatTimestamp(confirmedTime)
        if (confirmedTime != null && confirmedPlace != null) {
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
            binding.confirmedScheduleSection.visibility = View.VISIBLE
            binding.textConfirmedTime.text = "일시: $formattedTime"

            // 장소 정보가 있으면 표시
            if (confirmedPlace != null) {
                val placeName = confirmedPlace["name"] as? String ?: confirmedPlace["address"] as? String ?: "장소 정보 없음"
                binding.textConfirmedPlace.text = "장소: $placeName"
                binding.textConfirmedPlace.visibility = View.VISIBLE
            } else {
                binding.textConfirmedPlace.visibility = View.GONE
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

        when (status) {
            "GROUP_CREATED",
            "TIME_VOTE_REQUIRED" -> {
                // 시간 투표 단계
                android.util.Log.d("GroupDetailActivity", "✅ 시간 투표 단계 - 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            "TIME_FINALIZING" -> {
                // 시간 확정 단계 - 시간 투표만 활성화 (최종 시간 투표 진행 중)
                android.util.Log.d("GroupDetailActivity", "⏳ 시간 확정 단계 - 시간 투표만 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            "LOCATION_INPUT_REQUIRED",
            "LOCATION_DONE",
            "PLACE_RANKING",
            "FINAL_PLACE_VOTE",
            "FINALIZED" -> {
                // 장소 투표 단계 (시간 확정 완료)
                android.util.Log.d("GroupDetailActivity", "✅ 장소 투표 단계 - 장소 투표 활성화")
                binding.btnTimeVote.isEnabled = false
                binding.btnPlaceVote.isEnabled = true
            }
            null,
            "" -> {
                // 상태가 없거나 빈 문자열인 경우 - 기본적으로 시간 투표 활성화
                android.util.Log.w("GroupDetailActivity", "⚠️ 그룹 상태가 없음 - 기본값으로 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
            else -> {
                // 예상치 못한 상태 - 기본값으로 시간 투표 활성화
                android.util.Log.w("GroupDetailActivity", "⚠️ 예상치 못한 그룹 상태: $status - 기본값으로 시간 투표 활성화")
                binding.btnTimeVote.isEnabled = true
                binding.btnPlaceVote.isEnabled = false
            }
        }

        android.util.Log.d("GroupDetailActivity", "버튼 상태 - 시간 투표: ${binding.btnTimeVote.isEnabled}, 장소 투표: ${binding.btnPlaceVote.isEnabled}")
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
}
