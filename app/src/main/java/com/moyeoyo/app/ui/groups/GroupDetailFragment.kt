package com.moyeoyo.app.ui.groups

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityGroupDetailBinding
import com.moyeoyo.app.ui.place.FinalVoteViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class GroupDetailFragment : Fragment() {

    private val args: GroupDetailFragmentArgs by navArgs()
    private val groupId: String get() = args.groupId
    private val groupName: String get() = args.groupName

    private var _binding: ActivityGroupDetailBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var groupRepository: GroupRepository

    private val auth = FirebaseAuth.getInstance()
    private val voteViewModel: FinalVoteViewModel by viewModels()

    private lateinit var currentUid: String

    // 그룹 상태 실시간 리스너
    private var groupStatusListener: ListenerRegistration? = null
    private var previousStatus: String? = null
    private var hasShownVotingStartedDialog = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityGroupDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentUid = auth.currentUser?.uid ?: run {
            findNavController().navigateUp()
            return
        }

        binding.tvGroupName.text = groupName

        setupToolbar()
        setupButtons()
        setupTabs()
        startMonitoringGroupStatus()
    }

    override fun onResume() {
        super.onResume()
        loadGroupData()
        // 그룹 상태 리스너 재시작 (이미 시작되어 있으면 중복 방지)
        if (groupStatusListener == null) {
            startMonitoringGroupStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        groupStatusListener?.remove()
        groupStatusListener = null
        _binding = null
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> {
                    findNavController().navigate(
                        R.id.action_groupDetailFragment_to_shareMeetingFragment,
                        Bundle().apply {
                            putString("groupId", groupId)
                            putString("groupName", groupName)
                        }
                    )
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteGroupConfirmationDialog()
        }
    }

    // ---------------------------
    //  ViewPager2 / 탭 설정
    // ---------------------------
    private fun setupTabs() {
        binding.viewPager.adapter = GroupDetailPagerFragmentAdapter(this, groupId)

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

    /**
     * 그룹 상태를 실시간으로 감지하여 투표 시작 알림 표시
     */
    private fun startMonitoringGroupStatus() {
        if (groupStatusListener != null) {
            return // 이미 리스너가 등록되어 있음
        }

        val db = FirebaseFirestore.getInstance()
        val groupRef = db.collection("groups").document(groupId)

        groupStatusListener = groupRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                android.util.Log.e("GroupDetailFragment", "그룹 상태 리스너 오류: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                return@addSnapshotListener
            }

            val currentStatus = snapshot.getString("status")
            val previousStatusValue = previousStatus

            // 상태가 변경되었는지 확인
            if (previousStatusValue != null && currentStatus != previousStatusValue) {
                android.util.Log.d("GroupDetailFragment",
                    "📊 그룹 상태 변경 감지: $previousStatusValue → $currentStatus")

                // GROUP_CREATED에서 TIME_VOTE_REQUIRED로 변경된 경우 (투표 시작)
                if (previousStatusValue == "GROUP_CREATED" && currentStatus == "TIME_VOTE_REQUIRED") {
                    // 방장이 아닌 경우에만 팝업 표시
                    viewLifecycleOwner.lifecycleScope.launch {
                        val group = groupRepository.getGroupById(groupId)
                        val isHost = group?.hostUid == currentUid

                        if (!isHost && !hasShownVotingStartedDialog) {
                            hasShownVotingStartedDialog = true
                            showVotingStartedDialog()
                        }
                    }
                }

                // 상태 변경 시 UI 업데이트
                viewLifecycleOwner.lifecycleScope.launch {
                    loadGroupData()
                }
            }

            previousStatus = currentStatus
        }
    }

    /**
     * 투표 시작 알림 다이얼로그
     */
    private fun showVotingStartedDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("투표가 시작되었습니다")
            .setMessage("방장이 투표를 시작했습니다.\n이제 시간 투표를 진행할 수 있습니다.")
            .setPositiveButton("확인") { _, _ ->
                // 투표 탭으로 이동
                binding.viewPager.currentItem = 0
                binding.tabGroup.check(R.id.tabPlace)
            }
            .setCancelable(false)
            .show()
    }

    private fun loadGroupData() {
        binding.tvMemberCount.text = "로딩 중..."

        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)

            if (group != null) {
                binding.tvGroupName.text = group.groupName

                val isHost = (group.hostUid == currentUid)
                binding.tvMemberCount.text = "${group.memberUids.size}명"

                // 이전 상태 저장 (첫 로드 시)
                if (previousStatus == null) {
                    previousStatus = group.status
                }

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
            } else {
                Toast.makeText(
                    requireContext(),
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
            // 다음 모임 카드 표시
            binding.nextMeetingCard.visibility = View.VISIBLE
            binding.tvMeetingDateAndTime.text = formatTimestamp(confirmedTime)
            binding.tvMeetingLocation.text =
                confirmedPlace["name"] as? String ?: "장소 없음"

            binding.btnAddToCalendarWrapper.setOnClickListener {
                val beginTime = confirmedTime.toDate().time
                val endTime = beginTime + 60 * 60 * 1000  // 1시간

                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, groupName)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                    // ⭐ 장소명을 우선 사용하고, 없으면 주소 사용
                    putExtra(
                        CalendarContract.Events.EVENT_LOCATION,
                        confirmedPlace["name"] as? String ?: confirmedPlace["address"] as? String
                    )
                }

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

    private fun navigateToGroupManageScreen(groupId: String, groupName: String) {
        // GroupManageFragment로 Navigation 사용
        findNavController().navigate(
            R.id.action_groupDetailFragment_to_groupManageFragment,
            Bundle().apply {
                putString("groupId", groupId)
            }
        )
    }

    private fun showDeleteGroupConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("그룹 삭제")
            .setMessage("정말로 '$groupName' 그룹을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showLeaveGroupConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("그룹 나가기")
            .setMessage("'$groupName' 그룹을 나가시겠습니까?")
            .setPositiveButton("나가기") { _, _ ->
                leaveGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = groupRepository.deleteGroup(
                context = requireContext(),
                groupId = groupId
            )

            if (success) {
                Toast.makeText(
                    requireContext(),
                    "'$groupName' 그룹을 삭제했습니다.",
                    Toast.LENGTH_LONG
                ).show()
                findNavController().navigateUp()
            } else {
                Toast.makeText(
                    requireContext(),
                    "그룹 삭제에 실패했습니다.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun leaveGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = groupRepository.leaveGroup(groupId)

            if (success) {
                Toast.makeText(requireContext(), "'$groupName' 그룹을 나왔습니다.", Toast.LENGTH_LONG).show()
                findNavController().navigateUp()
            } else {
                Toast.makeText(requireContext(), "그룹 나가기에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * ViewPager2에서 Fragment를 사용하기 위한 Adapter
 */
class GroupDetailPagerFragmentAdapter(
    fragment: Fragment,
    private val groupId: String
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                VoteTabFragment().apply {
                    arguments = Bundle().apply {
                        putString("groupId", groupId)
                    }
                }
            }
            1 -> {
                MemberTabFragment().apply {
                    arguments = Bundle().apply {
                        putString("groupId", groupId)
                    }
                }
            }
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}

