package com.moyeoyo.app.ui.friend

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.model.Friend

class FriendListAdapter(
    private var friendList: MutableList<Friend>,
    private val onDeleteClick: (Friend) -> Unit
) : RecyclerView.Adapter<FriendListAdapter.FriendViewHolder>() {

    fun submitList(newList: List<Friend>) {
        friendList = newList.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FriendViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_friend, parent, false)
        return FriendViewHolder(view)
    }

    override fun getItemCount(): Int = friendList.size

    override fun onBindViewHolder(holder: FriendViewHolder, position: Int) {
        val friend = friendList[position]
        holder.bind(friend)
    }

    inner class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvInitial: TextView = itemView.findViewById(R.id.tvInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvFriendName)
        private val tvEmail: TextView = itemView.findViewById(R.id.tvFriendEmail)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteFriend)

        fun bind(friend: Friend) {
            val initial = friend.name.firstOrNull()?.toString() ?: "?"
            tvInitial.text = initial

            tvName.text = friend.name
            tvEmail.text = friend.email

            btnDelete.setOnClickListener {
                onDeleteClick(friend)
            }
        }
    }
}
