package com.moyeoyo.app.ui.group

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.databinding.FragmentTimeVoteBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class TimeVoteFragment : Fragment(R.layout.fragment_time_vote) {

    private var _binding: FragmentTimeVoteBinding? = null
    private val binding get() = _binding!!
    private val repository = TimeVoteRepository()
    private val auth = FirebaseAuth.getInstance()

    private val groupId = "group_1"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private var selectedDate: Date = Date()
    private val calendar = Calendar.getInstance()

    private val selectedTimes = mutableSetOf<String>()
    private var selectedDayButton: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTimeVoteBinding.bind(view)

        setupToolbar()
        setupWeekHeader()
        setupTimeGrid()
        setupButton()
        observeFirestoreVotes()

        // Firestore 테스트용 더미 데이터 (한 번만 실행)
        repository.seedDummyVotes(groupId, dateFormat.format(selectedDate))
    }

    // 상단 툴바
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    // 주차 + 요일 헤더 생성
    private fun setupWeekHeader() {
        updateWeekTitle()

        // 이전/다음 주 버튼
        binding.btnPrevWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, -1)
            setupWeekDays()
            updateWeekTitle()
        }

        binding.btnNextWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, 1)
            setupWeekDays()
            updateWeekTitle()
        }

        setupWeekDays()
    }

    // “11월 3째주” 표시
    private fun updateWeekTitle() {
        val month = calendar.get(Calendar.MONTH) + 1
        val weekOfMonth = calendar.get(Calendar.WEEK_OF_MONTH)
        binding.tvWeekTitle.text = "${month}월 ${weekOfMonth}째주"
    }

    // 주차별 날짜 버튼 (월~일) 동적 생성
    private fun setupWeekDays() {
        val layout = binding.layoutWeekDays
        layout.removeAllViews()

        val tempCal = calendar.clone() as Calendar
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        val dayFormat = SimpleDateFormat("MM월 dd일", Locale.KOREA)
        val weekDays = listOf("월", "화", "수", "목", "금", "토", "일")

        for (i in 0 until 7) {
            val date = tempCal.time
            val dayLabel = weekDays[i]
            val button = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
                setPadding(4, 8, 4, 8)
                setOnClickListener {
                    selectedDate = date
                    highlightSelectedDay(this)
                    observeFirestoreVotes()
                }
            }

            val dayText = TextView(requireContext()).apply {
                text = dayLabel
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                textSize = 13f
            }

            val dateText = TextView(requireContext()).apply {
                text = dayFormat.format(date)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                textSize = 12f
            }

            button.addView(dayText)
            button.addView(dateText)

            layout.addView(button)

            // 오늘 날짜 기본 선택
            if (isSameDay(date, Date())) {
                selectedDate = date
                highlightSelectedDay(button)
            }

            tempCal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    // 선택된 요일 강조
    private fun highlightSelectedDay(selectedButton: View) {
        selectedDayButton?.background = null
        selectedButton.setBackgroundResource(R.drawable.bg_light_gray_outline)
        selectedDayButton = selectedButton
    }

    // 같은 날짜인지 비교
    private fun isSameDay(d1: Date, d2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = d1 }
        val cal2 = Calendar.getInstance().apply { time = d2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    // 시간대 버튼 (0~23시)
    private fun setupTimeGrid() {
        val grid = binding.gridTimeSlots
        grid.removeAllViews()
        val times = (0..23).map { String.format(Locale.KOREA, "%02d시", it) }

        times.forEach { time ->
            val button = MaterialButton(requireContext()).apply {
                text = time
                tag = false
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(
                        android.widget.GridLayout.UNDEFINED,
                        1f
                    )
                    setMargins(12, 12, 12, 12)
                }
                setPadding(0, 24, 0, 24)
                setBackgroundResource(R.drawable.bg_time_slot_card)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.black))

                setOnClickListener {
                    val isSelected = tag as Boolean
                    tag = !isSelected
                    if (!isSelected) {
                        selectedTimes.add(time)
                        backgroundTintList =
                            ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                    } else {
                        selectedTimes.remove(time)
                        backgroundTintList =
                            ContextCompat.getColorStateList(requireContext(), R.color.white)
                        setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                    }
                    updateButtonState()
                }
            }
            grid.addView(button)
        }
    }

    //  Firestore 실시간 반영 (날짜 변경 시 다시 로드)
    private fun observeFirestoreVotes() {
        val uid = auth.currentUser?.uid ?: "sample_uid_1"
        val dateStr = dateFormat.format(selectedDate)
        lifecycleScope.launch {
            repository.observeVotes(groupId, dateStr).collectLatest { data ->
                updateGridFromFirestore(data, uid)
            }
        }
    }

    //  Firestore 기반 시간대 표시 갱신
    private fun updateGridFromFirestore(data: Map<String, List<String>>, uid: String) {
        val grid = binding.gridTimeSlots
        for (i in 0 until grid.childCount) {
            val button = grid.getChildAt(i) as MaterialButton
            val time = button.text.toString().replace("시", ":00")
            val voters = data[time] ?: emptyList()
            val isMyVote = voters.contains(uid)

            if (isMyVote) {
                button.tag = true
                button.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                selectedTimes.add(time)
            } else {
                button.tag = false
                button.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.white)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                selectedTimes.remove(time)
            }
        }
        updateButtonState()
    }

    //  하단 버튼 상태
    private fun updateButtonState() {
        val count = selectedTimes.size
        binding.btnCompleteVote.text = "투표 완료 (${count}개)"
        val enabled = count > 0
        binding.btnCompleteVote.isEnabled = enabled
        binding.btnCompleteVote.backgroundTintList =
            ContextCompat.getColorStateList(
                requireContext(),
                if (enabled) R.color.black else R.color.light_gray
            )
    }

    private fun setupButton() {
        binding.btnCompleteVote.setOnClickListener {
            if (selectedTimes.isEmpty()) {
                Toast.makeText(requireContext(), "선택된 시간이 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dateStr = dateFormat.format(selectedDate)
            android.util.Log.d("FIRESTORE", "버튼 클릭 - date=$dateStr, times=${selectedTimes.joinToString()}")

            lifecycleScope.launch {
                selectedTimes.forEach { time ->
                    val formattedTime = time.replace("시", ":00")
                    android.util.Log.d("FIRESTORE", "voteTime 호출: $formattedTime")
                    repository.voteTime(groupId, dateStr, formattedTime)
                }
                android.util.Log.d("FIRESTORE", "모든 voteTime 완료")
                Toast.makeText(requireContext(), "투표가 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
