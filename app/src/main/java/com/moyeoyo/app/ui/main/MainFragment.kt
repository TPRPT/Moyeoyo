package com.moyeoyo.app.ui.main

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

/**
 * 메인 화면 (내 그룹 리스트)
 * - 그룹 카드 클릭 시 GroupDetailFragment로 이동
 * - 향후 Firebase 데이터와 연동 가능하도록 구조화됨
 */
class MainFragment : Fragment(R.layout.fragment_main) {

    // ✅ 임시 더미 데이터 (나중에 Firebase Firestore 연결 예정)
    private val dummyGroups = listOf(
        GroupUi(
            id = "g1",
            name = "대학 동기들",
            memberCount = 5,
            statusLabel = "D-5",
            dateText = "2025년 10월 20일 (일)",
            locationText = "강남역 근처"
        ),
        GroupUi(
            id = "g2",
            name = "회사 동료",
            memberCount = 8,
            statusLabel = "투표중",
            dateText = "투표 진행중",
            locationText = "장소 선택중"
        ),
        GroupUi(
            id = "g3",
            name = "고등학교 친구들",
            memberCount = 6,
            statusLabel = "투표중",
            dateText = "투표 진행중",
            locationText = "장소 선택중"
        )
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ✅ RecyclerView 초기화
        val recyclerView = view.findViewById<RecyclerView>(R.id.groupRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = GroupAdapter(dummyGroups) { clickedGroup ->
            // ✅ 클릭 시 전달할 데이터
            val args = bundleOf(
                "groupId" to clickedGroup.id,
                "groupName" to clickedGroup.name,
                "memberCount" to clickedGroup.memberCount,
                "statusLabel" to clickedGroup.statusLabel,
                "dateText" to clickedGroup.dateText,
                "locationText" to clickedGroup.locationText
            )

            // ✅ GroupDetailFragment로 이동
            findNavController().navigate(
                R.id.action_mainFragment_to_groupDetailFragment,
                args
            )
        }

        // ✅ 상단 "참여중인 그룹 n개"
        val subTitle = view.findViewById<TextView>(R.id.subTitle)
        subTitle.text = "참여중인 그룹 ${dummyGroups.size}개"
    }
}

/**
 * UI 표시용 그룹 데이터 클래스
 * (Firebase 모델과 별도로 화면 표시 전용으로 사용)
 */
data class GroupUi(
    val id: String,
    val name: String,
    val memberCount: Int,
    val statusLabel: String, // 예: "투표중" 또는 "D-5"
    val dateText: String,
    val locationText: String
)

/**
 * RecyclerView 어댑터
 * 그룹 카드를 표시하고 클릭 이벤트 전달
 */
class GroupAdapter(
    private val groups: List<GroupUi>,
    private val onClick: (GroupUi) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.groupNameText)
        val member: TextView = view.findViewById(R.id.memberCountText)
        val badge: TextView = view.findViewById(R.id.badgeStatus)
        val date: TextView = view.findViewById(R.id.dateText)
        val location: TextView = view.findViewById(R.id.locationText)
        val chevron: ImageView = view.findViewById(R.id.chevron)

        fun bind(group: GroupUi) {
            name.text = group.name
            member.text = "${group.memberCount}명"
            date.text = group.dateText
            location.text = group.locationText

            // ✅ 상태 배지 디자인 적용
            badge.text = group.statusLabel
            if (group.statusLabel == "투표중") {
                badge.setBackgroundResource(R.drawable.bg_badge_orange)
                badge.setTextColor(Color.parseColor("#E07A27"))
            } else {
                badge.setBackgroundResource(R.drawable.bg_badge_blue)
                badge.setTextColor(Color.parseColor("#2F6FED"))
            }

            // ✅ 클릭 이벤트
            itemView.setOnClickListener { onClick(group) }
            chevron.setOnClickListener { onClick(group) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_card, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position])
    }

    override fun getItemCount(): Int = groups.size
}
