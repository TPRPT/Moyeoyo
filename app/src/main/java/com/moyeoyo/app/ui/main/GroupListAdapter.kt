package com.moyeoyo.app.ui.main

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Group

class GroupListAdapter(
    private val groups: List<Group>,
    private val onItemClick: (Group) -> Unit
) : RecyclerView.Adapter<GroupListAdapter.GroupViewHolder>() {

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvGroupName: TextView = itemView.findViewById(R.id.groupNameText)
        private val tvMemberCount: TextView = itemView.findViewById(R.id.memberCountText)
        private val tvDate: TextView = itemView.findViewById(R.id.dateText)
        private val tvLocation: TextView = itemView.findViewById(R.id.locationText)
        private val tvStatus: TextView = itemView.findViewById(R.id.badgeStatus)
        private val statusIcon: ImageView = itemView.findViewById(R.id.chevron)

        fun bind(group: Group) {
            tvGroupName.text = group.name
            tvMemberCount.text = "멤버 ${group.memberCount}명"
            tvDate.text = group.date ?: "날짜 미정"
            tvLocation.text = group.location ?: "장소 미정"

            if (group.isVoting) {
                tvStatus.text = "투표중"
                tvStatus.setBackgroundResource(R.drawable.bg_badge_orange)
                tvStatus.setTextColor(Color.parseColor("#E07A27"))
            } else {
                tvStatus.text = group.dDay ?: ""
                tvStatus.setBackgroundResource(R.drawable.bg_badge_blue)
                tvStatus.setTextColor(Color.parseColor("#2F6FED"))
            }

            itemView.setOnClickListener { onItemClick(group) }
            statusIcon.setOnClickListener { onItemClick(group) }
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
