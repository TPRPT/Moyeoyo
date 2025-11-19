package com.moyeoyo.app.ui.place

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.databinding.ItemFinalCandidateBinding
import com.moyeoyo.app.data.model.NearbyPlace
import java.util.Locale

/**
 * 최종 투표 후보 리스트 어댑터
 */
data class FinalCandidate(
    val place: NearbyPlace,
    val totalScore: Int,
    val isSelected: Boolean = false
)

class FinalCandidateAdapter(
    private val onCandidateClick: (FinalCandidate) -> Unit
) : ListAdapter<FinalCandidate, FinalCandidateAdapter.CandidateViewHolder>(CandidateDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val binding = ItemFinalCandidateBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CandidateViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val candidate = getItem(position)
        holder.bind(candidate)
    }

    inner class CandidateViewHolder(
        private val binding: ItemFinalCandidateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(candidate: FinalCandidate) {
            binding.tvPlaceName.text = candidate.place.name
            binding.tvCategory.text = candidate.place.categories.firstOrNull() ?: "장소"
            binding.tvTotalScore.text = "총점: ${candidate.totalScore}점"

            // 평점 표시
            if (candidate.place.rating != null) {
                binding.tvRating.text = "★ ${String.format(Locale.getDefault(), "%.1f", candidate.place.rating)}"
            } else {
                binding.tvRating.text = "★ -"
            }

            // 거리 표시
            val distanceKm = candidate.place.distanceMeters / 1000.0
            binding.tvDistance.text = String.format(Locale.getDefault(), "%.1fkm", distanceKm)

            // 선택된 표시
            binding.tvSelected.visibility = if (candidate.isSelected) View.VISIBLE else View.GONE

            // 클릭 이벤트
            binding.root.setOnClickListener {
                onCandidateClick(candidate)
            }
        }
    }

    private class CandidateDiffCallback : DiffUtil.ItemCallback<FinalCandidate>() {
        override fun areItemsTheSame(oldItem: FinalCandidate, newItem: FinalCandidate): Boolean {
            return oldItem.place.placeId == newItem.place.placeId
        }

        override fun areContentsTheSame(oldItem: FinalCandidate, newItem: FinalCandidate): Boolean {
            return oldItem == newItem
        }
    }
}

