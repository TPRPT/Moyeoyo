package com.moyeoyo.app.ui.group

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentTimeVoteBinding
import java.text.SimpleDateFormat
import java.util.*

class TimeVoteFragment : Fragment(R.layout.fragment_time_vote) {

    private var _binding: FragmentTimeVoteBinding? = null
    private val binding get() = _binding!!

    private val groupId = "group_1"
    private val userName = "김철수"

    private val selectedTimesByDate = mutableMapOf<String, MutableSet<Int>>() // 날짜별 시간 저장
    private var selectedDate: String = ""
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private val monthFormat = SimpleDateFormat("M", Locale.KOREA)

    private val currentWeekCalendar = Calendar.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTimeVoteBinding.bind(view)

        // 🔙 뒤로가기 버튼
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // 오늘 날짜 기본값
        selectedDate = dateFormat.format(Date())
        binding.tvTitle.text = "시간 투표"

        setupWeekHeader(currentWeekCalendar)
        setupTimeGrid()
        updateVoteButtonState()

        // 이전 / 다음 주
        binding.root.findViewById<ImageView>(R.id.btnPrevWeek).setOnClickListener {
            currentWeekCalendar.add(Calendar.WEEK_OF_MONTH, -1)
            setupWeekHeader(currentWeekCalendar)
            setupTimeGrid()
        }
        binding.root.findViewById<ImageView>(R.id.btnNextWeek).setOnClickListener {
            currentWeekCalendar.add(Calendar.WEEK_OF_MONTH, 1)
            setupWeekHeader(currentWeekCalendar)
            setupTimeGrid()
        }

        // 투표 완료 버튼 클릭 시
        binding.btnCompleteVote.setOnClickListener {
            saveUserVotesToLocal()
            Toast.makeText(requireContext(), "투표가 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
        }

        loadUserVotesFromLocal()
    }

    // 🔹 월 기준 주차 계산
    private fun getWeekOfMonth(calendar: Calendar): Int {
        val temp = calendar.clone() as Calendar
        return temp.get(Calendar.WEEK_OF_MONTH)
    }

    // 🔹 상단 주차 + 날짜 표시
    private fun setupWeekHeader(calendar: Calendar) {
        val month = monthFormat.format(calendar.time)
        val week = getWeekOfMonth(calendar)
        binding.tvWeekTitle.text = "${month}월 ${week}째주"

        val daysLayout = binding.layoutWeekDays
        daysLayout.removeAllViews()

        val tempCal = calendar.clone() as Calendar
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        val dayFormat = SimpleDateFormat("MM월 dd일", Locale.KOREA)
        val weekdayNames = listOf("월", "화", "수", "목", "금", "토", "일")

        for (i in 0 until 7) {
            val day = tempCal.clone() as Calendar
            day.add(Calendar.DAY_OF_MONTH, i)
            val dateStr = dateFormat.format(day.time)
            val isSelected = dateStr == selectedDate

            val dayText = TextView(requireContext()).apply {
                text = "${weekdayNames[i]}\n${dayFormat.format(day.time)}"
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(12, 8, 12, 8)
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (isSelected) R.drawable.bg_selected_outline_box else R.drawable.bg_light_gray_outline
                )
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isSelected) R.color.brand_blue else R.color.black
                    )
                )
                setOnClickListener {
                    selectedDate = dateStr
                    setupWeekHeader(calendar)
                    setupTimeGrid()
                }
            }

            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            dayText.layoutParams = params
            daysLayout.addView(dayText)
        }
    }

    // 🔹 시간 선택 (0~23시)
    private fun setupTimeGrid() {
        val grid = binding.gridTimeSlots
        grid.removeAllViews()

        val selectedHours = selectedTimesByDate[selectedDate] ?: mutableSetOf()

        for (hour in 0..23) {
            val timeButton = Button(requireContext()).apply {
                text = "${hour}시"
                textSize = 14f
                setPadding(8, 12, 8, 12)
                setBackgroundResource(
                    if (selectedHours.contains(hour))
                        R.drawable.bg_selected_outline_box
                    else
                        R.drawable.bg_light_gray_outline
                )
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (selectedHours.contains(hour)) R.color.brand_blue else R.color.black
                    )
                )

                setOnClickListener {
                    if (selectedHours.contains(hour)) selectedHours.remove(hour)
                    else selectedHours.add(hour)
                    selectedTimesByDate[selectedDate] = selectedHours
                    setupTimeGrid()
                    updateVoteButtonState()
                }
            }

            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = android.widget.GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
            timeButton.layoutParams = params
            grid.addView(timeButton)
        }
    }

    // 🔹 버튼 상태 업데이트 (색상 + 텍스트)
    private fun updateVoteButtonState() {
        val totalSelectedCount = selectedTimesByDate.values.sumOf { it.size }

        binding.btnCompleteVote.text = "투표 완료 (${totalSelectedCount}개)"

        if (totalSelectedCount > 0) {
            binding.btnCompleteVote.isEnabled = true
            binding.btnCompleteVote.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.black)
        } else {
            binding.btnCompleteVote.isEnabled = false
            binding.btnCompleteVote.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.light_gray)
        }
    }

    // 🔹 로컬 저장
    private fun saveUserVotesToLocal() {
        val prefs = requireContext().getSharedPreferences("time_votes", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val keyPrefix = "${groupId}_${userName}_"

        selectedTimesByDate.forEach { (date, hours) ->
            editor.putString(keyPrefix + date, hours.joinToString(","))
        }
        editor.apply()
    }

    // 🔹 로컬 불러오기
    private fun loadUserVotesFromLocal() {
        val prefs = requireContext().getSharedPreferences("time_votes", Context.MODE_PRIVATE)
        val keyPrefix = "${groupId}_${userName}_"
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(keyPrefix)) {
                val date = key.removePrefix(keyPrefix)
                val hours = (value as? String)?.split(",")?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
                selectedTimesByDate[date] = hours
            }
        }
        updateVoteButtonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
