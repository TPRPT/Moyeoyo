package com.example.moyeoyo.ui.map

// MemberDistanceAdapter: Distance Matrix 결과(멤버별 시간/거리)를 리스트로 표시
// - UID를 키로 DiffUtil 비교, 분/킬로미터로 가공해 바인딩

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.moyeoyo.data.model.DistanceResult
import com.example.moyeoyo.databinding.ItemMemberDistanceBinding
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
            override fun areItemsTheSame(o: DistanceResult, n: DistanceResult) = o.originUid == n.originUid
            override fun areContentsTheSame(o: DistanceResult, n: DistanceResult) = o == n
        }
    }
}

class MemberDistanceVH(
    private val binding: ItemMemberDistanceBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(item: DistanceResult) {
        binding.txtUid.text = item.originUid
        val min = TimeUnit.SECONDS.toMinutes(item.durationSec.toLong()) // 초 → 분 변환
        val km = item.distanceMeter / 1000.0 // m → km 변환
        binding.txtDetail.text = "약 ${min}분, ${"%.1f".format(km)}km"
    }
}