package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

class MemberListFragment : Fragment() {

    private lateinit var progressVote: ProgressBar
    private lateinit var tvVoteCount: TextView
    private lateinit var recyclerView: RecyclerView

    // ✅ 더미 데이터 (나중에 Firestore로 교체 가능)
    private val memberList = listOf(
        Member("김철수", "강남구", true),
        Member("이영희", "서초구", true),
        Member("박민수", "송파구", true),
        Member("최지원", "마포구", false),
        Member("정수진", "종로구", false)
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_member_list, container, false)

        progressVote = view.findViewById(R.id.progressVote)
        tvVoteCount = view.findViewById(R.id.tvVoteCount)
        recyclerView = view.findViewById(R.id.recyclerMemberList)

        setupVoteStatus()
        setupRecyclerView()

        return view
    }

    // ✅ 투표 현황 자동 계산 및 표시
    private fun setupVoteStatus() {
        val totalMembers = memberList.size
        val votedMembers = memberList.count { it.voted }
        val notVotedMembers = totalMembers - votedMembers

        // 진행률 계산
        val progressPercent = ((votedMembers.toFloat() / totalMembers) * 100).toInt()

        progressVote.max = 100
        progressVote.progress = progressPercent

        // ✅ 텍스트 표시 (예: "3명 완료 / 2명 미완료")
        tvVoteCount.text = "${votedMembers}명 완료 / ${notVotedMembers}명 미완료"
    }

    // ✅ 멤버 목록 RecyclerView
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = MemberAdapter(memberList)
        recyclerView.isNestedScrollingEnabled = false
    }

    data class Member(
        val name: String,
        val region: String,
        val voted: Boolean
    )

    inner class MemberAdapter(private val members: List<Member>) :
        RecyclerView.Adapter<MemberAdapter.MemberViewHolder>() {

        inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvInitial = view.findViewById<TextView>(R.id.tvMemberInitial)
            val tvName = view.findViewById<TextView>(R.id.tvMemberName)
            val tvRegion = view.findViewById<TextView>(R.id.tvMemberRegion)
            val tvStatus = view.findViewById<TextView>(R.id.tvVoteStatusBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_member_status, parent, false)
            return MemberViewHolder(view)
        }

        override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
            val member = members[position]

            holder.tvInitial.text = member.name.first().toString()
            holder.tvName.text = member.name
            holder.tvRegion.text = member.region

            if (member.voted) {
                holder.tvStatus.text = "투표 완료"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_badge_green)
                holder.tvStatus.setTextColor(resources.getColor(R.color.badge_green_text, null))
            } else {
                holder.tvStatus.text = "미완료"
                holder.tvStatus.setBackgroundResource(R.drawable.bg_light_gray_outline_box)
                holder.tvStatus.setTextColor(resources.getColor(R.color.badge_gray_text, null))
            }
        }

        override fun getItemCount(): Int = members.size
    }
}
