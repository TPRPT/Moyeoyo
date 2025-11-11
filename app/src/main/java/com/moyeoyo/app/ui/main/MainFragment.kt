package com.moyeoyo.app.ui.main

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

/**
 * 메인 화면 (내 그룹 리스트 + 프로필 정보 + AppBar)
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

        /** -------------------------------
         * ✅ 1. AppBar (툴바) 설정
         * ------------------------------- */
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_notifications -> {
                    Toast.makeText(requireContext(), "알림 클릭됨", Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_settings -> {
                    Toast.makeText(requireContext(), "설정 클릭됨", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        /** -------------------------------
         * ✅ 2. 프로필 카드 관련
         * ------------------------------- */
        val cardProfile = view.findViewById<CardView>(R.id.cardProfile)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)
        val tvName = view.findViewById<TextView>(R.id.tvName)
        val tvEmail = view.findViewById<TextView>(R.id.tvEmail)

        // 임시 표시 (나중에 Firebase Auth 데이터로 대체)
        tvName.text = "김모여 (서울특별시 공릉역사거리)"
        tvEmail.text = "moyeoyo52@gmail.com"

        // 프로필 카드 클릭 → 프로필 설정 페이지 이동 예정
        cardProfile.setOnClickListener {
            Toast.makeText(requireContext(), "프로필 설정 페이지로 이동 예정", Toast.LENGTH_SHORT).show()
            // findNavController().navigate(R.id.action_mainFragment_to_profileFragment)
        }

        // 로그아웃 버튼
        btnLogout.setOnClickListener {
            Toast.makeText(requireContext(), "로그아웃 클릭됨", Toast.LENGTH_SHORT).show()
            // FirebaseAuth.getInstance().signOut() 예정
        }

        /** -------------------------------
         * ✅ 3. 상단 버튼 (새 그룹 만들기)
         * ------------------------------- */
        val btnNewGroup = view.findViewById<Button>(R.id.btnNewGroup)

        btnNewGroup.setOnClickListener {
            Toast.makeText(requireContext(), "새 그룹 만들기 클릭됨", Toast.LENGTH_SHORT).show()
            // findNavController().navigate(R.id.action_mainFragment_to_createGroupFragment)
        }

        /** -------------------------------
         * ✅ 4. RecyclerView 초기화
         * ------------------------------- */
        val recyclerView = view.findViewById<RecyclerView>(R.id.groupRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = GroupAdapter(dummyGroups) { clickedGroup ->
            val args = bundleOf(
                "groupId" to clickedGroup.id,
                "groupName" to clickedGroup.name,
                "memberCount" to clickedGroup.memberCount,
                "statusLabel" to clickedGroup.statusLabel,
                "dateText" to clickedGroup.dateText,
                "locationText" to clickedGroup.locationText
            )
            findNavController().navigate(
                R.id.action_mainFragment_to_groupDetailFragment,
                args
            )
        }

        /** -------------------------------
         * ✅ 5. 참여중인 그룹 수 표시
         * ------------------------------- */
        val subTitle = view.findViewById<TextView>(R.id.subTitle)
        subTitle.text = "참여중인 그룹 ${dummyGroups.size}개"
    }
}

/**
 * UI 표시용 그룹 데이터 클래스
 */
data class GroupUi(
    val id: String,
    val name: String,
    val memberCount: Int,
    val statusLabel: String,
    val dateText: String,
    val locationText: String
)

/**
 * RecyclerView 어댑터
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

            // 상태 배지 스타일
            badge.text = group.statusLabel
            if (group.statusLabel == "투표중") {
                badge.setBackgroundResource(R.drawable.bg_badge_orange)
                badge.setTextColor(Color.parseColor("#E07A27"))
            } else {
                badge.setBackgroundResource(R.drawable.bg_badge_blue)
                badge.setTextColor(Color.parseColor("#2F6FED"))
            }

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
