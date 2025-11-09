package com.moyeoyo.app.ui.group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.databinding.ItemTimeSlotBinding

// 🔹 각 시간대 데이터 모델
data class TimeSlot(
    val time: String,
    val joinedCount: Int,
    val totalCount: Int,
    val members: List<String>
)

// 🔹 어댑터
class TimeSlotAdapter(
    private val items: List<TimeSlot>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<TimeSlotAdapter.ViewHolder>() {

    // 현재 선택된 항목 위치를 저장
    private val selectedPositions = mutableSetOf<Int>()

    inner class ViewHolder(val binding: ItemTimeSlotBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemTimeSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val binding = holder.binding

        // 텍스트 및 진행률 설정
        binding.txtTime.text = item.time
        binding.txtMembers.text = item.members.joinToString(", ")
        binding.txtCount.text = "${item.joinedCount}/${item.totalCount}명"
        binding.progressBar.progress = (item.joinedCount * 100) / item.totalCount

        // 선택 상태 반영 (파란 테두리)
        val isSelected = selectedPositions.contains(position)
        binding.root.isSelected = isSelected

        // 카드 클릭 시 선택/해제
        binding.root.setOnClickListener {
            if (isSelected) selectedPositions.remove(position)
            else selectedPositions.add(position)

            notifyItemChanged(position)
            onSelectionChanged(selectedPositions.size)
        }
    }

    override fun getItemCount() = items.size

    // ✅ 외부에서 선택된 시간대 설정 (SharedPreferences / Firestore 불러오기용)
    fun setSelectedTimes(times: List<String>) {
        selectedPositions.clear()
        times.forEach { time ->
            val index = items.indexOfFirst { it.time == time }
            if (index != -1) selectedPositions.add(index)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
    }

    // ✅ 현재 선택된 시간대 반환 (저장용)
    fun getSelectedTimes(): List<String> {
        return selectedPositions.map { items[it].time }
    }
}
