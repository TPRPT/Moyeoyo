package com.moyeoyo.app.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.ItemMemberDistanceBinding
import com.moyeoyo.app.data.model.TransportMode
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MemberDistanceItem(
    val uid: String,
    val displayName: String,
    val transportMode: TransportMode,
    val durationSeconds: Int,
    val distanceMeters: Int
)

class MemberDistanceAdapter
    : ListAdapter<MemberDistanceItem, MemberDistanceVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberDistanceVH {
        val binding = ItemMemberDistanceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MemberDistanceVH(binding)
    }

    override fun onBindViewHolder(holder: MemberDistanceVH, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MemberDistanceItem>() {
            override fun areItemsTheSame(o: MemberDistanceItem, n: MemberDistanceItem) = o.uid == n.uid
            override fun areContentsTheSame(o: MemberDistanceItem, n: MemberDistanceItem) = o == n
        }
    }
}

class MemberDistanceVH(
    private val binding: ItemMemberDistanceBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: MemberDistanceItem) {
        val context = binding.root.context
        binding.txtUid.text = item.displayName
        val minutes = TimeUnit.SECONDS.toMinutes(item.durationSeconds.toLong()).coerceAtLeast(1)
        val km = item.distanceMeters / 1000.0
        val distanceText = String.format(Locale.getDefault(), "%.1f", km)
        val modeLabel = when (item.transportMode) {
            TransportMode.WALK -> context.getString(R.string.mode_walk)
            TransportMode.TRANSIT -> context.getString(R.string.mode_transit)
            TransportMode.DRIVE -> context.getString(R.string.mode_drive)
        }
        binding.txtDetail.text = "$modeLabel · 약 ${minutes}분, ${distanceText}km"
    }
}
