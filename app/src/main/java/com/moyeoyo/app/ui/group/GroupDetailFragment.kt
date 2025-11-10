package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.radiobutton.MaterialRadioButton
import com.moyeoyo.app.R

class GroupDetailFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_group_detail, container, false)

        // -----------------------------
        // ✅ 전달받은 데이터 (MainFragment → Detail)
        // -----------------------------
        val groupName = arguments?.getString("groupName") ?: "모임 이름 없음"
        val memberCount = arguments?.getInt("memberCount") ?: 0
        val statusLabel = arguments?.getString("statusLabel") ?: "투표중"
        val dateText = arguments?.getString("dateText") ?: "미정"
        val locationText = arguments?.getString("locationText") ?: "미정"

        // -----------------------------
        // ✅ 툴바 (뒤로가기)
        // -----------------------------
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // -----------------------------
        // ✅ 텍스트 세팅
        // -----------------------------
        view.findViewById<TextView>(R.id.tvGroupName).text = groupName
        view.findViewById<TextView>(R.id.tvMemberCount).text = "멤버 ${memberCount}명"
        view.findViewById<TextView>(R.id.tvMeetingDateAndTime).text = dateText
        view.findViewById<TextView>(R.id.tvMeetingLocation).text = locationText

        // -----------------------------
        // ✅ 카드 전환 로직
        // -----------------------------
        val nextMeetingCard = view.findViewById<View>(R.id.nextMeetingCard)
        val votingStatusCard = view.findViewById<View>(R.id.votingStatusCard)

        if (statusLabel.contains("투표중")) {
            nextMeetingCard.visibility = View.GONE
            votingStatusCard.visibility = View.VISIBLE
        } else {
            nextMeetingCard.visibility = View.VISIBLE
            votingStatusCard.visibility = View.GONE
        }

        // -----------------------------
        // ✅ 탭 & ViewPager 연결
        // -----------------------------
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tabGroup = view.findViewById<ViewGroup>(R.id.tabGroup)
        val tabPlace = view.findViewById<MaterialRadioButton>(R.id.tabPlace)
        val tabMember = view.findViewById<MaterialRadioButton>(R.id.tabMember)

        viewPager.adapter = GroupTabAdapter(this)

        // 탭 클릭 시 ViewPager 전환
        tabPlace.setOnClickListener { viewPager.currentItem = 0 }
        tabMember.setOnClickListener { viewPager.currentItem = 1 }

        // ViewPager 스와이프 시 탭 상태 반영
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                when (position) {
                    0 -> tabPlace.isChecked = true
                    1 -> tabMember.isChecked = true
                }
            }
        })

        // -----------------------------
        // ✅ 투표 현황 더미 데이터
        // -----------------------------
        val tvTimeVoteCount = view.findViewById<TextView>(R.id.tvTimeVoteCount)
        val tvPlaceVoteCount = view.findViewById<TextView>(R.id.tvPlaceVoteCount)
        val timeProgress = view.findViewById<android.widget.ProgressBar>(R.id.timeVoteProgress)
        val placeProgress = view.findViewById<android.widget.ProgressBar>(R.id.placeVoteProgress)

        val totalMembers = memberCount
        val timeVoted = 3
        val placeVoted = 4

        tvTimeVoteCount.text = "${timeVoted}/${totalMembers}명 참여"
        tvPlaceVoteCount.text = "${placeVoted}/${totalMembers}명 참여"

        timeProgress.progress = (timeVoted * 100 / totalMembers)
        placeProgress.progress = (placeVoted * 100 / totalMembers)


        // -----------------------------
        // ✅ "추천 장소" 위쪽의 시간 버튼 클릭 시 이동
        // -----------------------------
        val btnFilterTime = view.findViewById<View>(R.id.btnFilterTime)
        val navController = requireActivity()
            .supportFragmentManager
            .findFragmentById(R.id.nav_host)
            ?.findNavController()

        btnFilterTime?.setOnClickListener {
            navController?.navigate(R.id.action_groupDetailFragment_to_timeVoteFragment)
        }


        // -----------------------------
// ✅ Firestore에서 시간 투표 현황 불러오기 (실시간 반영)
// -----------------------------
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val groupId = "test-group" // Firestore Emulator에 만든 문서 ID

        db.collection("groups")
            .document(groupId)
            .collection("timeVotes")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.e("Firestore", "투표 현황 리스너 오류", e)
                    return@addSnapshotListener
                }

                // 현재 투표된 사용자 수
                val votedCount = snapshot?.size() ?: 0

                // 총 멤버 수 (이미 위에서 memberCount로 받아왔음)
                val totalMembers = memberCount

                // UI 업데이트
                val tvTimeVoteCount = view.findViewById<TextView>(R.id.tvTimeVoteCount)
                val timeProgress = view.findViewById<android.widget.ProgressBar>(R.id.timeVoteProgress)

                tvTimeVoteCount.text = "${votedCount}/${totalMembers}명 참여"
                if (totalMembers > 0) {
                    timeProgress.progress = (votedCount * 100 / totalMembers)
                } else {
                    timeProgress.progress = 0
                }

                // 디버깅용 로그
                android.util.Log.d("Firestore", "현재 투표자 수: $votedCount")
            }




        return view
    }
}
