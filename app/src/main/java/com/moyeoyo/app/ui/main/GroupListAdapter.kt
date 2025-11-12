package com.moyeoyo.app.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.GroupUi

class GroupListAdapter(
    private val groupUis: List<GroupUi>,
    private val onItemClick: (GroupUi) -> Unit
) : RecyclerView.Adapter<GroupListAdapter.GroupViewHolder>() {

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupName: TextView = itemView.findViewById(R.id.groupNameText)
        private val tvMemberCount: TextView = itemView.findViewById(R.id.memberCountText)
        private val tvDate: TextView = itemView.findViewById(R.id.dateText)
        private val tvLocation: TextView = itemView.findViewById(R.id.locationText)
        private val tvStatus: TextView = itemView.findViewById(R.id.badgeStatus)
        private val statusIcon: ImageView = itemView.findViewById(R.id.chevron)

        fun bind(groupUi: GroupUi) {
            tvGroupName.text = groupUi.name
            tvMemberCount.text = "멤버 ${groupUi.memberCount}명"
            tvDate.text = groupUi.date ?: "날짜 미정"
            tvLocation.text = groupUi.location ?: "장소 미정"

            // ✅ 상태 뱃지 표시 로직
            when {
                groupUi.isVoting -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "투표중"
                    tvStatus.setBackgroundResource(R.drawable.bg_badge_orange)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.badge_orange_text))
                }
                !groupUi.dDay.isNullOrEmpty() -> {
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = groupUi.dDay
                    tvStatus.setBackgroundResource(R.drawable.bg_badge_blue)
                    tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.badge_blue_text))
                }
                else -> {
                    tvStatus.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onItemClick(groupUi) }
            statusIcon.setOnClickListener { onItemClick(groupUi) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_card, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groupUis[position])
    }

    override fun getItemCount(): Int = groupUis.size
}
