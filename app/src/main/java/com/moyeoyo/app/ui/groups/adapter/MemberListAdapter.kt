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
    private val isVotingStarted: Boolean,
    private val onKick: (uid: String, nickname: String) -> Unit,
    private val onLeave: () -> Unit
) : RecyclerView.Adapter<MemberListAdapter.MemberViewHolder>() {

    inner class MemberViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.member_name)
        val statusText: TextView = view.findViewById(R.id.member_status)
        val kickButton: Button = view.findViewById(R.id.btn_kick_member)
        val leaveButton: Button = view.findViewById(R.id.btn_leave_group)
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

        // 투표가 시작되면 모든 버튼 숨김
        if (isVotingStarted) {
            holder.kickButton.visibility = View.GONE
            holder.leaveButton.visibility = View.GONE
            return
        }

        // 강퇴 버튼: 방장이 다른 멤버를 강퇴할 수 있음
        val canKick = isHost && uid != currentUid
        if (canKick) {
            holder.kickButton.visibility = View.VISIBLE
            holder.kickButton.setOnClickListener {
                onKick(uid, nickname)
            }
        } else {
            holder.kickButton.visibility = View.GONE
        }

        // 나가기 버튼: 방장이 아닌 경우 본인에게만 표시
        val canLeave = !isHost && uid == currentUid
        if (canLeave) {
            holder.leaveButton.visibility = View.VISIBLE
            holder.leaveButton.setOnClickListener {
                onLeave()
            }
        } else {
            holder.leaveButton.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = members.size
}
