package com.moyeoyo.app.map.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.data.model.PlaceSuggestion
import com.moyeoyo.app.databinding.ItemSuggestionBinding
/**
 * 장소 추천(자동완성) 리스트용 어댑터
 * - PlaceSuggestion(placeId, label, address ...)
 * - 클릭 콜백으로 선택 반영
 */
class SuggestionAdapter(
    private val onClick: (PlaceSuggestion) -> Unit
) : ListAdapter<PlaceSuggestion, SuggestionAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<PlaceSuggestion>() {
        override fun areItemsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem.placeId == newItem.placeId

        override fun areContentsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem == newItem
    }

    inner class VH(private val binding: ItemSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaceSuggestion) = with(binding) {
            tvTitle.text = item.label
            tvSubtitle.text = item.address ?: ""
            root.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        val b = ItemSuggestionBinding.inflate(inf, parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
}