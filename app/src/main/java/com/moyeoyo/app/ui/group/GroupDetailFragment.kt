package com.moyeoyo.app.ui.group

import GroupTabAdapter
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 그룹 상세 화면
 * - 그룹 정보 / 멤버 수
 * - 투표 상태 / 다음 모임 정보
 * - 탭(추천 장소 / 멤버)
 * - 캘린더 추가
 * - 상단 공유 아이콘 → 약속 공유 화면
 */
class GroupDetailFragment : Fragment() {

    private val auth = FirebaseAuth.getInstance()
    private val groupRepository = GroupRepository()
    private lateinit var firestore: FirebaseFirestore

    private var groupId: String? = null
    private var groupName: String? = null
    private var memberCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        firestore = FirebaseFirestore.getInstance()

        // MainFragment → GroupDetailFragment 에서 전달된 값
        groupId = arguments?.getString("groupId")
        groupName = arguments?.getString("groupName")

        val tvGroupName = view.findViewById<TextView>(R.id.tvGroupName)
        val tvMemberCount = view.findViewById<TextView>(R.id.tvMemberCount)
        val btnDelete = view.findViewById<Button>(R.id.btnDeleteGroup)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)

        val tabGroup = view.findViewById<RadioGroup>(R.id.tabGroup)
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)

        val tvMeetingDateAndTime = view.findViewById<TextView>(R.id.tvMeetingDateAndTime)
        val tvMeetingLocation = view.findViewById<TextView>(R.id.tvMeetingLocation)

        val btnAddToCalendarWrapper = view.findViewById<View>(R.id.btnAddToCalendarWrapper)
        val btnAddToCalendar = view.findViewById<View>(R.id.btnAddToCalendar)

        tvGroupName.text = groupName ?: "그룹 이름 없음"

        // ✅ 뒤로가기 (왼쪽 화살표)
        toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        // ✅ 상단 공유 아이콘 클릭 → 약속 공유 화면으로 이동
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> {
                    val action = GroupDetailFragmentDirections
                        .actionGroupDetailFragmentToShareMeetingFragment(
                            groupId = groupId ?: "",
                            groupName = groupName ?: "",
                            meetingTime = tvMeetingDateAndTime.text?.toString(),
                            meetingPlace = tvMeetingLocation.text?.toString(),
                            memberCount = memberCount
                        )
                    findNavController().navigate(action)
                    true
                }
                else -> false
            }
        }

        // ✅ ViewPager2 + 탭 연결
        val adapter = GroupTabAdapter(this, groupId ?: "", groupName ?: "")
        viewPager.adapter = adapter

        tabGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.tabPlace -> viewPager.currentItem = 0
                R.id.tabMember -> viewPager.currentItem = 1
            }
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> tabGroup.check(R.id.tabPlace)
                    1 -> tabGroup.check(R.id.tabMember)
                }
            }
        })

        // ✅ 캘린더 추가 (카드 전체 + 텍스트 둘 다 클릭)
        val calendarClick: (View) -> Unit = calendarClick@{
            val (startMillis, title, location) = buildCalendarEventData(
                groupName = groupName ?: "모임",
                dateTimeText = tvMeetingDateAndTime.text?.toString(),
                placeText = tvMeetingLocation.text?.toString(),
                finalMeetingAt = null // 확정 시간이 Long 으로 있을 경우 여기로 전달
            )

            if (startMillis == null) {
                Toast.makeText(requireContext(), "모임 시간 정보를 확인할 수 없어요", Toast.LENGTH_SHORT).show()
                return@calendarClick
            }

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 60 * 60 * 1000)
                putExtra(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Seoul")
            }
            startActivity(intent)
        }
        btnAddToCalendarWrapper.setOnClickListener(calendarClick)
        btnAddToCalendar.setOnClickListener(calendarClick)

        // ✅ Firestore 실시간 그룹 데이터 감시
        loadGroupData(tvMemberCount, btnDelete)
        btnDelete.setOnClickListener { showDeleteDialog() }
    }

    /**
     * Firestore에서 그룹 상세 데이터 로드 (실시간)
     */
    private fun loadGroupData(memberText: TextView, deleteBtn: Button) {
        val votingCard = view?.findViewById<View>(R.id.votingStatusCard)
        val nextMeetingCard = view?.findViewById<View>(R.id.nextMeetingCard)
        val tvMeetingDateAndTime = view?.findViewById<TextView>(R.id.tvMeetingDateAndTime)
        val tvMeetingLocation = view?.findViewById<TextView>(R.id.tvMeetingLocation)

        firestore.collection("groups")
            .document(groupId ?: return)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) {
                    memberText.text = "그룹 정보 불러오기 실패"
                    return@addSnapshotListener
                }

                val memberUids = snapshot.get("memberUids") as? List<String> ?: emptyList()
                val votedMembers = snapshot.get("votedMembers") as? List<String> ?: emptyList()

                memberCount = memberUids.size
                memberText.text = "${memberUids.size}명 참여 중"

                // 삭제 버튼 권한
                val isHost = snapshot.getString("hostUid") == auth.currentUser?.uid
                deleteBtn.isEnabled = isHost
                deleteBtn.alpha = if (isHost) 1f else 0.5f

                // 🔹 투표 완료 여부
                if (memberUids.isNotEmpty() && votedMembers.size >= memberUids.size) {
                    // ✅ 모든 멤버 투표 완료 → 다음 모임 카드
                    votingCard?.visibility = View.GONE
                    nextMeetingCard?.visibility = View.VISIBLE

                    val finalAt = snapshot.getLong("finalMeetingAt")
                    val finalPlace = snapshot.getString("finalMeetingPlace")

                    finalAt?.let {
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"), Locale.KOREA)
                        cal.timeInMillis = it
                        val fmt = SimpleDateFormat("yyyy년 M월 d일 (E) HH:mm", Locale.KOREA)
                        tvMeetingDateAndTime?.text = fmt.format(cal.time)
                    }

                    finalPlace?.let {
                        tvMeetingLocation?.text = it
                    }
                } else {
                    // 🟧 투표 진행 중
                    nextMeetingCard?.visibility = View.GONE
                    votingCard?.visibility = View.VISIBLE
                }
            }
    }

    /**
     * 그룹 삭제 다이얼로그
     */
    private fun showDeleteDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("그룹 삭제")
            .setMessage("정말로 '$groupName' 그룹을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> deleteGroup() }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * Firestore에서 그룹 삭제
     */
    private fun deleteGroup() {
        lifecycleScope.launch {
            val success = groupRepository.deleteGroup(groupId ?: return@launch)
            if (success) {
                Toast.makeText(requireContext(), "그룹이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            } else {
                Toast.makeText(requireContext(), "삭제 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 캘린더 인텐트 데이터 구성 헬퍼
     */
    private fun buildCalendarEventData(
        groupName: String,
        dateTimeText: String?,
        placeText: String?,
        finalMeetingAt: Long?
    ): Triple<Long?, String, String> {

        val title = "다음 모임 - $groupName"
        val location = placeText?.takeIf { it.isNotBlank() } ?: "장소 미정"

        // 1️⃣ Firestore timestamp 우선
        finalMeetingAt?.let { return Triple(it, title, location) }

        // 2️⃣ TextView 파싱: "2025년 10월 20일 (일) 18:00"
        val txt = dateTimeText?.trim().orEmpty()
        if (txt.isEmpty()) return Triple(null, title, location)

        val fmt = SimpleDateFormat("yyyy년 M월 d일 (E) HH:mm", Locale.KOREA)
        return try {
            val date = fmt.parse(txt)
            Triple(date?.time, title, location)
        } catch (e: Exception) {
            try {
                val alt = SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREA).parse(txt)
                Triple(alt?.time, title, location)
            } catch (_: Exception) {
                Triple(null, title, location)
            }
        }
    }
}
