package com.moyeoyo.app.ui.group

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentTimeVoteBinding
import java.text.SimpleDateFormat
import java.util.*

class TimeVoteFragment : Fragment(R.layout.fragment_time_vote) {

    private var _binding: FragmentTimeVoteBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: TimeSlotAdapter

    private val groupId = "group_1" // 나중에 Firestore 연동 시 전달받을 값
    private val userName = "김철수" // 임시 사용자명

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private var selectedDate: String? = null

    // 날짜별로 선택된 시간 저장 (앱 내 메모리)
    private val selectedTimesByDate = mutableMapOf<String, List<String>>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTimeVoteBinding.bind(view)

        // 🔙 뒤로가기 버튼
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        // 🔹 시간대 RecyclerView 세팅
        adapter = TimeSlotAdapter(getDummyTimeSlots()) { selectedCount ->
            binding.tvSelectedCount.text = "${selectedCount}개 선택됨"
            binding.btnCompleteVote.text = "투표 완료 (${selectedCount}개)"
            updateButtonState(selectedCount > 0)
        }

        binding.recyclerTimeSlots.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTimeSlots.adapter = adapter

        // 🔹 로컬 저장된 투표 불러오기
        loadUserVotesFromLocal()

        // 🔹 날짜 선택 다이얼로그 설정
        setupCalendarDialog()

        // 🔹 투표 완료 버튼 클릭 시 저장
        binding.btnCompleteVote.setOnClickListener {
            saveCurrentDateSelection()
            saveUserVotesToLocal()
            Toast.makeText(requireContext(), "투표가 임시 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
        }

        updateButtonState(false)
    }

    // ---------------------- 🔹 날짜 선택 다이얼로그 ----------------------
    private fun setupCalendarDialog() {
        // 날짜 선택기 생성
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("날짜 선택")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        // 버튼 클릭 시 달력 표시
        binding.btnSelectDate.setOnClickListener {
            datePicker.show(parentFragmentManager, "DATE_PICKER")
        }

        // 날짜 선택 완료 시 동작
        datePicker.addOnPositiveButtonClickListener { selection ->
            val date = Date(selection)
            val dateStr = dateFormat.format(date)
            saveCurrentDateSelection() // 기존 날짜 상태 저장
            selectedDate = dateStr

            binding.tvSelectedDate.text = "선택된 날짜: $dateStr"

            // ✅ 새 날짜 선택 시 기존 투표 상태 복원
            val savedTimes = selectedTimesByDate[dateStr] ?: emptyList()
            adapter.setSelectedTimes(savedTimes)
        }
    }

    // ---------------------- 🔹 현재 날짜 선택 저장 ----------------------
    private fun saveCurrentDateSelection() {
        selectedDate?.let {
            val currentSelectedTimes = adapter.getSelectedTimes()
            selectedTimesByDate[it] = currentSelectedTimes
        }
    }

    // ---------------------- 🔹 SharedPreferences 저장 ----------------------
    private fun saveUserVotesToLocal() {
        saveCurrentDateSelection()

        val prefs = requireContext().getSharedPreferences("time_votes", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keyPrefix = "${groupId}_${userName}_"

        for ((date, times) in selectedTimesByDate) {
            editor.putString(keyPrefix + date, times.joinToString(","))
        }

        editor.apply()
    }

    // ---------------------- 🔹 SharedPreferences 불러오기 ----------------------
    private fun loadUserVotesFromLocal() {
        val prefs = requireContext().getSharedPreferences("time_votes", Context.MODE_PRIVATE)
        val keyPrefix = "${groupId}_${userName}_"

        prefs.all.forEach { entry ->
            val key = entry.key
            if (key.startsWith(keyPrefix)) {
                val date = key.removePrefix(keyPrefix)
                val times = (entry.value as? String)?.split(",") ?: emptyList()
                selectedTimesByDate[date] = times
            }
        }

        // 오늘 날짜를 기본으로 설정
        val today = dateFormat.format(Date())
        selectedDate = today
        binding.tvSelectedDate.text = "선택된 날짜: $today"

        val todayTimes = selectedTimesByDate[today] ?: emptyList()
        adapter.setSelectedTimes(todayTimes)
    }

    // ---------------------- 🔹 버튼 상태 처리 ----------------------
    private fun updateButtonState(enabled: Boolean) {
        binding.btnCompleteVote.isEnabled = enabled
        binding.btnCompleteVote.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(),
            if (enabled) R.color.brand_blue else R.color.light_gray
        )
    }

    // ---------------------- 🔹 더미 시간대 데이터 ----------------------
    private fun getDummyTimeSlots(): List<TimeSlot> {
        return listOf(
            TimeSlot("10:00", 2, 5, listOf("김철수", "이영희")),
            TimeSlot("12:00", 4, 5, listOf("김철수", "이영희", "박민수", "최지원")),
            TimeSlot("14:00", 3, 5, listOf("이영희", "박민수", "최지원")),
            TimeSlot("16:00", 1, 5, listOf("김철수")),
            TimeSlot("18:00", 5, 5, listOf("김철수", "이영희", "박민수", "최지원", "정수진")),
            TimeSlot("20:00", 2, 5, listOf("박민수", "최지원"))
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
