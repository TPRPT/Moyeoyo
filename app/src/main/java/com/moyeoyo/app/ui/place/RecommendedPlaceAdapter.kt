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
    private var savedRanks: Map<String, Int> = emptyMap() // placeId -> rank (저장된 순위, 읽기 전용)
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
        val oldSelectedRanks = this.selectedRanks.toMap() // 복사본 생성
        
        this.isRankMode = isRankMode
        this.selectedRanks = selectedRanks.toMap() // 복사본으로 저장
        
        // 순위 모드가 변경되었거나, 선택된 순위가 변경된 아이템만 업데이트
        if (oldIsRankMode != isRankMode) {
            // 순위 모드가 변경되면 모든 아이템 업데이트
            notifyItemRangeChanged(0, itemCount)
        } else {
            // 순위 모드가 같으면 변경된 아이템만 업데이트
            for (i in 0 until itemCount) {
                val place = getItem(i)
                val placeId = place.placeId
                val oldRank = oldSelectedRanks[placeId]
                val newRank = selectedRanks[placeId]
                // 순위가 변경되었거나 새로 선택/해제된 경우 업데이트
                if (oldRank != newRank) {
                    notifyItemChanged(i)
                }
            }
        }
    }

    /**
     * 저장된 순위 업데이트 (읽기 전용 표시용)
     */
    fun updateSavedRanks(savedRanks: Map<String, Int>) {
        val oldSavedRanks = this.savedRanks.toMap()
        this.savedRanks = savedRanks.toMap()
        
        // 저장된 순위가 변경된 아이템만 업데이트
        for (i in 0 until itemCount) {
            val place = getItem(i)
            val placeId = place.placeId
            val oldRank = oldSavedRanks[placeId]
            val newRank = savedRanks[placeId]
            if (oldRank != newRank) {
                notifyItemChanged(i)
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

            // ⭐ 순위 표시: 순위 모드 중 선택된 순위 또는 저장된 순위
            val rank = if (isRankMode) {
                // 순위 모드 중: 선택 중인 순위 표시
                selectedRanks[place.placeId]
            } else {
                // 순위 모드 아님: 저장된 순위 표시 (읽기 전용)
                savedRanks[place.placeId]
            }
            
            if (rank != null) {
                // 순위가 있는 장소: 선택 효과 배경 적용 (진한 파란색 배경 + 파란색 테두리)
                binding.cardContainer.setBackgroundResource(R.drawable.bg_card_selected)
                binding.tvRankBadge.visibility = View.VISIBLE
                binding.tvRankBadge.text = rank.toString()
                binding.tvRankBadge.setBackgroundResource(R.drawable.bg_rank_badge)
            } else {
                // 순위가 없는 장소: 기본 배경
                binding.cardContainer.setBackgroundResource(R.drawable.bg_card)
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

