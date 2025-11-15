package com.moyeoyo.app.ui.friend

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
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
        holder.bind(friendList[position])
    }

    inner class FriendViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val ivProfile: ImageView = itemView.findViewById(R.id.ivProfile)
        private val tvInitial: TextView = itemView.findViewById(R.id.tvInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvFriendName)
        private val tvEmail: TextView = itemView.findViewById(R.id.tvFriendEmail)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btnDeleteFriend)

        fun bind(friend: Friend) {

            // 🔥 프로필 사진 로딩 + fallback
            if (!friend.profileImageUrl.isNullOrEmpty()) {
                ivProfile.visibility = View.VISIBLE
                tvInitial.visibility = View.GONE

                Glide.with(itemView)
                    .load(friend.profileImageUrl)
                    .circleCrop()
                    .into(ivProfile)

            } else {
                ivProfile.visibility = View.GONE
                tvInitial.visibility = View.VISIBLE
                tvInitial.text = friend.name.firstOrNull()?.toString() ?: "?"
            }

            tvName.text = friend.name
            tvEmail.text = friend.email

            btnDelete.setOnClickListener { onDeleteClick(friend) }
        }
    }
}
