package com.moyeoyo.app.map

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.data.model.DistanceResult
import com.moyeoyo.app.databinding.ItemMemberDistanceBinding
import java.util.concurrent.TimeUnit

class MemberDistanceAdapter
    : ListAdapter<DistanceResult, MemberDistanceVH>(DIFF) {

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
        val DIFF = object : DiffUtil.ItemCallback<DistanceResult>() {
            override fun areItemsTheSame(o: DistanceResult, n: DistanceResult) = o.uid == n.uid
            override fun areContentsTheSame(o: DistanceResult, n: DistanceResult) = o == n
        }
    }
}

class MemberDistanceVH(
    private val binding: ItemMemberDistanceBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: DistanceResult) {
        binding.txtUid.text = item.uid
        val min = TimeUnit.SECONDS.toMinutes(item.durationSeconds.toLong())
        val km = item.distanceMeters / 1000.0
        binding.txtDetail.text = "약 ${min}분, ${"%.1f".format(km)}km"
    }
}
