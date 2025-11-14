package com.moyeoyo.app.ui.group

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Friend

class InviteFriendAdapter(
    private val friendList: List<Friend>,
    private val selectedFriends: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<InviteFriendAdapter.InviteFriendViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InviteFriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invite_friend, parent, false)
        return InviteFriendViewHolder(view)
    }

    override fun onBindViewHolder(holder: InviteFriendViewHolder, position: Int) {
        holder.bind(friendList[position])
    }

    override fun getItemCount() = friendList.size

    inner class InviteFriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkbox: CheckBox = itemView.findViewById(R.id.inviteCheckBox)
        private val tvInitial: TextView = itemView.findViewById(R.id.inviteInitial)
        private val tvName: TextView = itemView.findViewById(R.id.inviteName)
        private val tvEmail: TextView = itemView.findViewById(R.id.inviteEmail)

        fun bind(friend: Friend) {
            tvInitial.text = friend.name.firstOrNull()?.toString() ?: "?"
            tvName.text = friend.name
            tvEmail.text = friend.email

            checkbox.isChecked = selectedFriends.contains(friend.uid)

            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedFriends.add(friend.uid)
                else selectedFriends.remove(friend.uid)

                onSelectionChanged()
            }
        }
    }
}
