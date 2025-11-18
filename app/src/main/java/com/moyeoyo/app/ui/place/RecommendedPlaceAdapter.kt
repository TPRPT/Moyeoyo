package com.moyeoyo.app.ui.place

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.data.model.NearbyPlace
import com.moyeoyo.app.databinding.ItemRecommendedPlaceBinding
import java.util.Locale

/**
 * 장소 추천 리스트 어댑터
 */
class RecommendedPlaceAdapter(
    private val onPlaceClick: (NearbyPlace) -> Unit
) : ListAdapter<NearbyPlace, RecommendedPlaceAdapter.PlaceViewHolder>(PlaceDiffCallback()) {

    private var isRankMode: Boolean = false
    private var selectedRanks: Map<String, Int> = emptyMap() // placeId -> rank
    private var transitTimes: Map<String, Int> = emptyMap() // placeId -> 대중교통 소요시간 (초)

    /**
     * 소요시간 업데이트 (스크롤 위치 유지)
     */
    fun updateTransitTimes(newTransitTimes: Map<String, Int>) {
        val oldTransitTimes = transitTimes
        transitTimes = newTransitTimes
        
        // 변경된 아이템만 찾아서 업데이트
        for (i in 0 until itemCount) {
            val place = getItem(i)
            val oldTime = oldTransitTimes[place.placeId]
            val newTime = newTransitTimes[place.placeId]
            if (oldTime != newTime) {
                notifyItemChanged(i)
            }
        }
    }

    /**
     * 순위 모드 및 선택된 순위 업데이트 (스크롤 위치 유지)
     */
    fun updateRankMode(isRankMode: Boolean, selectedRanks: Map<String, Int>) {
        val oldIsRankMode = this.isRankMode
        val oldSelectedRanks = this.selectedRanks
        
        this.isRankMode = isRankMode
        this.selectedRanks = selectedRanks
        
        // 순위 모드가 변경되었거나, 선택된 순위가 변경된 아이템만 업데이트
        if (oldIsRankMode != isRankMode) {
            // 순위 모드가 변경되면 모든 아이템 업데이트
            notifyItemRangeChanged(0, itemCount)
        } else {
            // 순위 모드가 같으면 변경된 아이템만 업데이트
            val allPlaceIds = (oldSelectedRanks.keys + selectedRanks.keys).toSet()
            for (i in 0 until itemCount) {
                val place = getItem(i)
                if (place.placeId in allPlaceIds) {
                    val oldRank = oldSelectedRanks[place.placeId]
                    val newRank = selectedRanks[place.placeId]
                    if (oldRank != newRank) {
                        notifyItemChanged(i)
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemRecommendedPlaceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        val place = getItem(position)
        holder.bind(place)
    }

    inner class PlaceViewHolder(
        private val binding: ItemRecommendedPlaceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: NearbyPlace) {
            binding.tvPlaceName.text = place.name
            binding.tvCategory.text = place.categories.firstOrNull() ?: "장소"
            
            // 평점 표시
            if (place.rating != null) {
                binding.tvRating.text = "★ ${String.format(Locale.getDefault(), "%.1f", place.rating)}"
            } else {
                binding.tvRating.text = "★ -"
            }

            // 거리 표시
            val distanceKm = place.distanceMeters / 1000.0
            binding.tvDistance.text = String.format(Locale.getDefault(), "%.1fkm", distanceKm)

            // 대중교통 소요시간 표시
            val transitTimeSeconds = transitTimes[place.placeId]
            if (transitTimeSeconds != null) {
                val transitTimeMinutes = (transitTimeSeconds / 60.0).toInt().coerceAtLeast(1)
                binding.tvTransitTime.text = "· 대중교통 ${transitTimeMinutes}분"
                binding.tvTransitTime.visibility = View.VISIBLE
            } else {
                binding.tvTransitTime.visibility = View.GONE
            }

            // 순위 모드인 경우 순위 표시
            if (isRankMode) {
                val rank = selectedRanks[place.placeId]
                if (rank != null) {
                    binding.tvRankBadge.visibility = View.VISIBLE
                    binding.tvRankBadge.text = rank.toString()
                    // 순위별 색상 변경
                    binding.root.background = ContextCompat.getDrawable(
                        binding.root.context,
                        when (rank) {
                            1 -> R.drawable.bg_rank_badge
                            2 -> R.drawable.bg_rank_badge
                            3 -> R.drawable.bg_rank_badge
                            else -> R.drawable.bg_card
                        }
                    )
                    // 순위 배지 색상
                    binding.tvRankBadge.background = ContextCompat.getDrawable(
                        binding.root.context,
                        when (rank) {
                            1 -> R.drawable.bg_rank_badge // 1순위 - 파란색
                            2 -> R.drawable.bg_rank_badge // 2순위 - 초록색
                            3 -> R.drawable.bg_rank_badge // 3순위 - 노란색
                            else -> R.drawable.bg_rank_badge
                        }
                    )
                } else {
                    binding.tvRankBadge.visibility = View.GONE
                    binding.root.background = ContextCompat.getDrawable(
                        binding.root.context,
                        R.drawable.bg_card
                    )
                }
            } else {
                binding.tvRankBadge.visibility = View.GONE
            }

            // 클릭 이벤트
            binding.root.setOnClickListener {
                onPlaceClick(place)
            }
        }
    }

    private class PlaceDiffCallback : DiffUtil.ItemCallback<NearbyPlace>() {
        override fun areItemsTheSame(oldItem: NearbyPlace, newItem: NearbyPlace): Boolean {
            return oldItem.placeId == newItem.placeId
        }

        override fun areContentsTheSame(oldItem: NearbyPlace, newItem: NearbyPlace): Boolean {
            return oldItem == newItem
        }
    }
}

