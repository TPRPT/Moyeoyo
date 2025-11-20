package com.moyeoyo.app.ui.groups.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

// members: List<Pair<uid, nickname>>
class MemberListAdapter(
    private val members: List<Pair<String, String>>,
    private val hostUid: String,
    private val currentUid: String,
    private val isHost: Boolean,
    private val onKick: (uid: String, nickname: String) -> Unit
) : RecyclerView.Adapter<MemberListAdapter.MemberViewHolder>() {

    inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
        val (uid, nickname) = members[position]

        // 닉네임 표시
        holder.nameText.text = nickname

        // 방장 여부 표시
        holder.statusText.text = if (uid == hostUid) "(방장)" else ""

        // 강퇴 버튼 표시 조건
        val canKick = isHost && uid != currentUid

        if (canKick) {
            holder.kickButton.visibility = View.VISIBLE
            holder.kickButton.setOnClickListener {
                onKick(uid, nickname)
            }
        } else {
            holder.kickButton.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = members.size
}
