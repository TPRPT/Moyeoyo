package com.moyeoyo.app.ui.time

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.ItemFinalTimeBinding

data class FinalTimeItem(
    val time: String,
    val date: String
)

class FinalTimeAdapter(
    private val onTimeClick: (String) -> Unit
) : ListAdapter<FinalTimeItem, FinalTimeAdapter.ViewHolder>(TimeDiffCallback()) {

    private var selectedTime: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFinalTimeBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun updateSelection(time: String?) {
        val previousSelected = selectedTime
        selectedTime = time
        
        // 이전 선택 항목과 현재 선택 항목 업데이트
        currentList.forEachIndexed { index, item ->
            if (item.time == previousSelected || item.time == selectedTime) {
                notifyItemChanged(index)
            }
        }
    }

    inner class ViewHolder(
        private val binding: ItemFinalTimeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FinalTimeItem) {
            // 날짜 포맷팅
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            val dateDisplayFormat = java.text.SimpleDateFormat("yyyy년 MM월 dd일 (E)", java.util.Locale.KOREA)
            val dateObj = dateFormat.parse(item.date)
            val formattedDate = dateObj?.let { dateDisplayFormat.format(it) } ?: item.date
            
            // 날짜와 시간을 함께 표시
            val timeDisplay = item.time.replace(":00", "시")
            binding.tvTime.text = "$formattedDate $timeDisplay"
            
            val isSelected = item.time == selectedTime
            binding.root.isSelected = isSelected
            
            // 선택 상태에 따라 배경색 및 체크 아이콘 변경
            if (isSelected) {
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.brand_blue)
                )
                binding.tvTime.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.white)
                )
                binding.ivCheck.visibility = View.VISIBLE
            } else {
                binding.root.setBackgroundColor(
                    ContextCompat.getColor(binding.root.context, R.color.white)
                )
                binding.tvTime.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.black)
                )
                binding.ivCheck.visibility = View.GONE
            }
            
            binding.root.setOnClickListener {
                onTimeClick(item.time)
            }
        }
    }

    private class TimeDiffCallback : DiffUtil.ItemCallback<FinalTimeItem>() {
        override fun areItemsTheSame(oldItem: FinalTimeItem, newItem: FinalTimeItem): Boolean {
            return oldItem.time == newItem.time && oldItem.date == newItem.date
        }

        override fun areContentsTheSame(oldItem: FinalTimeItem, newItem: FinalTimeItem): Boolean {
            return oldItem == newItem
        }
    }
}

