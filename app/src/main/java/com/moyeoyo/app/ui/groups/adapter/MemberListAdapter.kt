package com.moyeoyo.app.ui.groups.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moyeoyo.app.R

data class MemberUiModel(
    val uid: String,
    val nickname: String,
    val photoUrl: String?   // 앱에서 업로드한 프사
)


// members: List<Pair<uid, nickname>>
class MemberListAdapter(
    private val members: List<MemberUiModel>,
    private val hostUid: String,
    private val currentUid: String,
    private val isHost: Boolean,
    private val onKick: (uid: String, nickname: String) -> Unit
) : RecyclerView.Adapter<MemberListAdapter.MemberViewHolder>() {

    inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val profileImage: ImageView = view.findViewById(R.id.member_profile)
        val nameText: TextView = view.findViewById(R.id.member_name)
        val statusText: TextView = view.findViewById(R.id.member_status)
        val kickButton: Button = view.findViewById(R.id.btn_kick_member)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_member_list, parent, false)
        return MemberViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        val member = members[position]

        // 닉네임 표시
        holder.nameText.text = member.nickname

        // 프로필 이미지 로딩
        Glide.with(holder.itemView)
            .load(member.photoUrl)
            .placeholder(R.drawable.default_user)
            .circleCrop()
            .into(holder.profileImage)

        // 방장 여부 표시
        holder.statusText.text = if (member.uid == hostUid) "(방장)" else ""

        // 강퇴 버튼 표시 조건
        val canKick = isHost && member.uid != currentUid

        if (canKick) {
            holder.kickButton.visibility = View.VISIBLE
            holder.kickButton.setOnClickListener {
                onKick(member.uid, member.nickname)
            }
        } else {
            holder.kickButton.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = members.size
}
