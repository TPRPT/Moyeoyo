package com.moyeoyo.app.ui.group

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.ItemTimeSlotBinding

data class TimeSlot(
    val time: String,
    var joinedCount: Int,
    var totalCount: Int,
    var members: List<String>
)

class TimeSlotAdapter(
    private var items: List<TimeSlot>,
    private val onSelectionChanged: (Int) -> Unit,
    private val onTimeClicked: (String, Boolean) -> Unit // ✅ 클릭 시 Firestore 연동 콜백
) : RecyclerView.Adapter<TimeSlotAdapter.ViewHolder>() {

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
        val context = binding.root.context

        binding.txtTime.text = item.time
        binding.txtCount.text = "${item.joinedCount}/${item.totalCount}명"
        binding.txtMembers.text = item.members.joinToString(", ").ifEmpty { "아직 참여자 없음" }
        binding.progressBar.progress = (item.joinedCount * 100) / item.totalCount

        val isSelected = selectedPositions.contains(position)
        binding.root.background = ContextCompat.getDrawable(
            context,
            if (isSelected) R.drawable.bg_selected_outline_box else R.drawable.bg_time_slot_card
        )

        binding.root.setOnClickListener {
            if (isSelected) {
                selectedPositions.remove(position)
                onTimeClicked(item.time, false)
            } else {
                selectedPositions.add(position)
                onTimeClicked(item.time, true)
            }
            notifyItemChanged(position)
            onSelectionChanged(selectedPositions.size)
        }
    }

    override fun getItemCount() = items.size

    // Firestore에서 받은 데이터로 UI 업데이트
    fun updateVoteData(newData: Map<String, List<String>>, currentUserUid: String) {
        items = items.map { slot ->
            val voters = newData[slot.time] ?: emptyList()
            slot.copy(
                joinedCount = voters.size,
                members = voters,
                totalCount = 5 // 전체 인원 수는 임시로 5명
            )
        }
        selectedPositions.clear()
        newData.forEach { (time, list) ->
            val index = items.indexOfFirst { it.time == time }
            if (index != -1 && list.contains(currentUserUid)) selectedPositions.add(index)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedPositions.size)
    }

    fun getSelectedTimes(): List<String> {
        return selectedPositions.map { items[it].time }
    }
}
