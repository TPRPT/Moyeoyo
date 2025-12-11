package com.moyeoyo.app.ui.time

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.databinding.FragmentTimeVoteBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.math.pow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class TimeVoteFragment : Fragment() {

    private val args: TimeVoteFragmentArgs by navArgs()
    private val groupId: String get() = args.groupId

    private var _binding: FragmentTimeVoteBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var timeVoteRepository: TimeVoteRepository
    @Inject lateinit var groupRepository: GroupRepository

    private val viewModel: TimeVoteViewModel by viewModels()

    private val auth = FirebaseAuth.getInstance()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private var selectedDate: Date = Date()
    private val calendar = Calendar.getInstance()

    private var selectedDayButton: View? = null

    // 드래그용 시간 버튼 리스트
    private val timeButtons = mutableListOf<MaterialButton>()

    // 모든 멤버 투표 완료 확인 플래그 (중복 화면 전환 방지)
    private var hasNavigatedToFinalVote = false

    // 사용자가 이미 투표했는지 확인하는 플래그
    private var hasVoted = false

    // 드래그 중 처리된 버튼 추적 (깜빡임 방지)
    private val processedButtonsDuringDrag = mutableSetOf<MaterialButton>()
    private var dragStartState: Boolean? = null // 드래그 시작 시점의 선택 상태

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimeVoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupWeekHeader()
        setupTimeGrid()
        setupButton()
        observeFirestoreVotes()

        // ⭐ FinalTimeVoteFragment에서 리셋 신호를 받으면 플래그 리셋
        parentFragmentManager.setFragmentResultListener("final_vote_reset", viewLifecycleOwner) { _, bundle ->
            val shouldReset = bundle.getBoolean("should_reset_flag", false)
            if (shouldReset) {
                hasNavigatedToFinalVote = false
                android.util.Log.d("TimeVoteFragment", "✅ FinalTimeVoteFragment로부터 리셋 신호 수신, 플래그 리셋")
            }
        }

        // 초기화 시 모든 날짜에 대해 모든 멤버 투표 완료 확인
        viewLifecycleOwner.lifecycleScope.launch {
            checkAllDatesVoted()
            // 사용자가 이미 투표했는지 확인
            checkUserVotedStatus()
        }

        // 스크롤뷰 설정 & 드래그 리스너
        binding.scrollViewTimeSlots.isNestedScrollingEnabled = false
        binding.scrollViewTimeSlots.setOnTouchListener { _, event ->
            handleDragSelect(event)
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dismissWaitingDialog()
        finalVoteConfirmationDialog?.dismiss()
        finalVoteConfirmationDialog = null
        autoConfirmDialog?.dismiss()
        autoConfirmDialog = null
        currentVoteObserver?.cancel()
        _binding = null
    }

    // ----------------------- 드래그 선택 -----------------------

    private var isDragging = false
    private var initialDragSelectionState: Boolean? = null // 드래그 시작 시 첫 버튼의 선택 상태

    private fun handleDragSelect(event: MotionEvent) {
        // 이미 투표를 완료한 경우 드래그 비활성화
        if (hasVoted) return

        binding.scrollViewTimeSlots.requestDisallowInterceptTouchEvent(true)

        val x = event.rawX.toInt()
        val y = event.rawY.toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                initialDragSelectionState = null // 초기화
                processedButtonsDuringDrag.clear() // 초기화
                
                // 드래그 시작 지점의 버튼 찾기
                timeButtons.forEach { button ->
                    val rect = Rect()
                    button.getGlobalVisibleRect(rect)
                    if (rect.contains(x, y)) {
                        // 드래그 시작 시 첫 버튼의 상태를 기억
                        initialDragSelectionState = button.tag as? Boolean == true
                        selectTimeSlot(button)
                        processedButtonsDuringDrag.add(button)
                        return@forEach
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return
                
                // 드래그 중: 터치 위치에 있는 버튼 처리
                timeButtons.forEach { button ->
                    val rect = Rect()
                    button.getGlobalVisibleRect(rect)
                    
                    if (rect.contains(x, y)) {
                        // 이미 처리된 버튼이면 건너뛰기 (중복 처리 방지)
                        if (button in processedButtonsDuringDrag) {
                            return@forEach
                        }
                        
                        // 드래그 시작 상태에 따라 선택/해제 결정
                        val shouldSelect = initialDragSelectionState == false
                        val currentState = button.tag as? Boolean == true
                        
                        // 원하는 상태와 현재 상태가 다를 때만 변경
                        if (shouldSelect != currentState) {
                            selectTimeSlotForDrag(button, shouldSelect)
                        }
                        processedButtonsDuringDrag.add(button)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                initialDragSelectionState = null
                processedButtonsDuringDrag.clear()
                binding.scrollViewTimeSlots.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun selectTimeSlot(button: MaterialButton) {
        // 이미 투표를 완료한 경우 드래그 선택 비활성화
        if (hasVoted) return

        // ⚠️ tag를 Boolean으로 안전하게 캐스팅
        val isAlreadySelected = button.tag as? Boolean == true
        val time = button.text.toString()
        val dateStr = dateFormat.format(selectedDate)

        // ViewModel의 장바구니에 추가/제거 (토글)
        viewModel.toggleTimeSelection(dateStr, time)

        // 선택 상태 토글
        val newSelectedState = !isAlreadySelected
        button.tag = newSelectedState
        
        if (newSelectedState) {
            button.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        } else {
            button.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.white)
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        }

        updateButtonState()
    }

    /**
     * 드래그 중에 특정 상태로 설정하는 함수
     */
    private fun selectTimeSlotForDrag(button: MaterialButton, shouldSelect: Boolean) {
        // 이미 투표를 완료한 경우 드래그 선택 비활성화
        if (hasVoted) return

        val currentState = button.tag as? Boolean == true
        
        // 이미 원하는 상태면 변경하지 않음
        if (currentState == shouldSelect) return

        val time = button.text.toString()
        val dateStr = dateFormat.format(selectedDate)

        // ViewModel의 장바구니에 추가/제거
        if (shouldSelect) {
            // 선택되지 않은 상태에서 선택으로 변경
            if (!currentState) {
                viewModel.toggleTimeSelection(dateStr, time)
            }
        } else {
            // 선택된 상태에서 해제로 변경
            if (currentState) {
                viewModel.toggleTimeSelection(dateStr, time)
            }
        }

        // 선택 상태 설정
        button.tag = shouldSelect
        
        if (shouldSelect) {
            button.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        } else {
            button.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.white)
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
        }

        updateButtonState()
    }

    // ----------------------- 상단 UI -----------------------

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupWeekHeader() {
        updateWeekTitle()

        binding.btnPrevWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, -1)
            // ⭐ 주 변경 시 장바구니는 유지되고, UI만 업데이트
            updateWeekUI()
        }

        binding.btnNextWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, 1)
            // ⭐ 주 변경 시 장바구니는 유지되고, UI만 업데이트
            updateWeekUI()
        }

        setupWeekDays()
    }

    /**
     * 주 변경 시 UI만 업데이트 (장바구니는 유지)
     */
    private fun updateWeekUI() {
        val tempCal = calendar.clone() as Calendar
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        // 현재 선택된 날짜를 현재 주의 첫 번째 날짜(월요일)로 변경
        selectedDate = tempCal.time

        setupWeekDays()
        updateWeekTitle()
        setupTimeGrid()

        // 주 변경 시 모든 날짜 관찰 다시 시작 (Firestore에서 현재 주 데이터 로드)
        observeFirestoreVotes()
        
        // 주 변경 시 사용자 투표 상태 확인
        viewLifecycleOwner.lifecycleScope.launch {
            checkUserVotedStatus()
        }
    }

    private fun updateWeekTitle() {
        val month = calendar.get(Calendar.MONTH) + 1
        val weekOfMonth = calendar.get(Calendar.WEEK_OF_MONTH)
        binding.tvWeekTitle.text = "${month}월 ${weekOfMonth}째주"
    }

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
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(4, 8, 4, 8)
                setOnClickListener {
                    selectedDate = date
                    highlightSelectedDay(this)
                    hasNavigatedToFinalVote = false // 날짜 변경 시 플래그 리셋

                    // 날짜 변경 시 시간 그리드만 업데이트 (관찰은 이미 모든 날짜를 관찰 중)
                    setupTimeGrid()
                    updateButtonState() // 날짜 변경 시 버튼 상태 업데이트
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

            if (isSameDay(date, Date())) {
                selectedDate = date
                highlightSelectedDay(button)
            }

            tempCal.add(Calendar.DAY_OF_MONTH, 1)
        }
    }

    private fun highlightSelectedDay(selectedButton: View) {
        selectedDayButton?.background = null
        selectedButton.setBackgroundResource(R.drawable.bg_light_gray_outline)
        selectedDayButton = selectedButton
    }

    private fun isSameDay(d1: Date, d2: Date): Boolean {
        val cal1 = Calendar.getInstance().apply { time = d1 }
        val cal2 = Calendar.getInstance().apply { time = d2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    // ----------------------- 시간 버튼 -----------------------

    private fun setupTimeGrid() {
        val grid = binding.gridTimeSlots
        grid.removeAllViews()
        timeButtons.clear()

        // 현재 선택된 날짜의 선택된 시간 가져오기 (ViewModel의 장바구니에서)
        val dateStr = dateFormat.format(selectedDate)
        val currentDateSelectedTimes = viewModel.getSelectedTimesForDate(dateStr)

        val times = (0..23).map { String.format(Locale.KOREA, "%02d시", it) }

        times.forEach { time ->
            val isSelected = currentDateSelectedTimes.contains(time)

            val button = MaterialButton(requireContext()).apply {
                text = time
                tag = isSelected

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

                // 선택된 시간은 파란색으로 표시
                if (isSelected) {
                    backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                } else {
                    backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.white)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
                }

                // 터치 리스너로 드래그와 클릭 모두 처리
                var touchStartTime = 0L
                var touchStartX = 0f
                var touchStartY = 0f
                
                setOnTouchListener { v, event ->
                    if (hasVoted) {
                        false
                    } else {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                touchStartTime = System.currentTimeMillis()
                                touchStartX = event.rawX
                                touchStartY = event.rawY
                                // 드래그 핸들러에 이벤트 전달
                                handleDragSelect(event)
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                // 드래그 핸들러에 이벤트 전달
                                handleDragSelect(event)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val touchDuration = System.currentTimeMillis() - touchStartTime
                                val touchDistance = sqrt(
                                    (event.rawX - touchStartX).pow(2) + (event.rawY - touchStartY).pow(2)
                                )
                                
                                // 드래그 핸들러에 이벤트 전달
                                handleDragSelect(event)
                                
                                // 짧은 클릭이고 이동 거리가 작으면 클릭으로 처리
                                if (touchDuration < 200 && touchDistance < 50) {
                                    val wasSelected = tag as Boolean
                                    viewModel.toggleTimeSelection(dateStr, time)
                                    tag = !wasSelected
                                    if (!wasSelected) {
                                        backgroundTintList =
                                            ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
                                        setTextColor(
                                            ContextCompat.getColor(
                                                requireContext(),
                                                R.color.white
                                            )
                                        )
                                    } else {
                                        backgroundTintList =
                                            ContextCompat.getColorStateList(requireContext(), R.color.white)
                                        setTextColor(
                                            ContextCompat.getColor(
                                                requireContext(),
                                                R.color.black
                                            )
                                        )
                                    }
                                    updateButtonState()
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
            }

            grid.addView(button)
            timeButtons.add(button)
        }

        // 버튼 상태 업데이트
        updateButtonState()
        
        // 이미 투표한 경우 버튼 비활성화
        if (hasVoted) {
            disableTimeButtons()
        }
    }

    // ----------------------- Firestore 연동 -----------------------

    private var currentVoteObserver: kotlinx.coroutines.Job? = null

    private fun observeFirestoreVotes() {
        val uid = auth.currentUser?.uid ?: return

        // 이전 관찰자 취소
        currentVoteObserver?.cancel()

        // 현재 주의 모든 날짜를 관찰
        currentVoteObserver = viewLifecycleOwner.lifecycleScope.launch {
            val tempCal = calendar.clone() as Calendar
            tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

            val datesToObserve = mutableListOf<String>()
            for (i in 0 until 7) {
                val dateStr = dateFormat.format(tempCal.time)
                datesToObserve.add(dateStr)
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            // 모든 날짜의 Flow를 합쳐서 관찰
            val flows = datesToObserve.map { dateStr ->
                timeVoteRepository.observeVotes(groupId, dateStr)
            }

            // 모든 Flow를 병합하여 관찰
            combine(flows) { arrays ->
                arrays.map { it as Map<String, List<String>> }
            }
                .debounce(500) // 500ms debounce로 중복 호출 방지
                .collectLatest { allDatesData ->
                    // 모든 날짜의 Firestore 데이터를 selectedTimesByDate에 동기화
                    datesToObserve.forEachIndexed { index, dateStr ->
                        if (index < allDatesData.size) {
                            updateSelectedTimesFromFirestore(dateStr, allDatesData[index], uid)
                        }
                    }

                    // 현재 선택된 날짜의 데이터로 그리드 업데이트
                    val currentDateStr = dateFormat.format(selectedDate)
                    val currentDateIndex = datesToObserve.indexOf(currentDateStr)
                    if (currentDateIndex >= 0 && currentDateIndex < allDatesData.size) {
                        updateGridFromFirestore(allDatesData[currentDateIndex], uid)
                    }

                    // ⚠️ 실시간으로 모든 날짜에 대해 모든 멤버 투표 완료 확인
                    if (!hasNavigatedToFinalVote) {
                        try {
                            checkAllDatesVoted()
                        } catch (e: Exception) {
                            android.util.Log.e("TimeVoteFragment", "투표 확인 중 오류: ${e.message}", e)
                        }
                    }
                }
        }
    }

    /**
     * Firestore 데이터를 기반으로 ViewModel의 장바구니를 초기화 (모든 날짜에 대해)
     */
    private fun updateSelectedTimesFromFirestore(
        dateStr: String,
        data: Map<String, List<String>>,
        uid: String
    ) {
        // ViewModel의 장바구니에 Firestore 데이터 반영
        viewModel.initializeSelectionsFromFirestore(dateStr, data, uid)
    }

    /**
     * 현재 선택된 날짜의 그리드를 Firestore 데이터로 업데이트
     */
    private fun updateGridFromFirestore(data: Map<String, List<String>>, uid: String) {
        val grid = binding.gridTimeSlots
        val dateStr = dateFormat.format(selectedDate)

        // ViewModel의 장바구니에 Firestore 데이터 반영
        viewModel.initializeSelectionsFromFirestore(dateStr, data, uid)

        // UI 업데이트: ViewModel의 장바구니 상태를 반영
        val currentDateSelectedTimes = viewModel.getSelectedTimesForDate(dateStr)

        for (i in 0 until grid.childCount) {
            val button = grid.getChildAt(i) as MaterialButton
            val timeKey = button.text.toString().replace("시", ":00")
            val voters = data[timeKey] ?: emptyList()
            val isMyVote = voters.contains(uid)
            val timeText = button.text.toString()
            val isSelected = currentDateSelectedTimes.contains(timeText)

            // 장바구니에 있는 시간은 선택된 것으로 표시
            if (isSelected) {
                button.tag = true
                button.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.brand_blue)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                button.tag = false
                button.backgroundTintList =
                    ContextCompat.getColorStateList(requireContext(), R.color.white)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }

        updateButtonState()
    }

    // ----------------------- 하단 버튼 -----------------------

    private fun updateButtonState() {
        // 이미 투표를 완료한 경우 버튼 비활성화
        if (hasVoted) {
            binding.btnCompleteVote.text = "저장 완료"
            binding.btnCompleteVote.isEnabled = false
            binding.btnCompleteVote.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.light_gray)
            binding.btnReset.isEnabled = false
            binding.btnReset.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), R.color.light_gray)
            return
        }

        // ViewModel의 장바구니에서 모든 날짜의 선택된 시간 개수 계산
        val pendingSelections = viewModel.pendingSelections.value
        val totalCount = pendingSelections.values.sumOf { it.size }
        val dateCount = pendingSelections.count { it.value.isNotEmpty() }

        if (dateCount > 0) {
            binding.btnCompleteVote.text = "저장 (${dateCount}개 날짜, ${totalCount}개 시간)"
        } else {
            binding.btnCompleteVote.text = "저장"
        }

        val enabled = totalCount > 0
        binding.btnCompleteVote.isEnabled = enabled
        binding.btnCompleteVote.backgroundTintList =
            ContextCompat.getColorStateList(
                requireContext(),
                if (enabled) R.color.black else R.color.light_gray
            )
        
        // 초기화 버튼 상태 업데이트
        binding.btnReset.isEnabled = enabled
        binding.btnReset.backgroundTintList =
            ContextCompat.getColorStateList(
                requireContext(),
                if (enabled) R.color.white else R.color.light_gray
            )
    }

    /**
     * 사용자가 이미 투표했는지 확인
     */
    private suspend fun checkUserVotedStatus() {
        val uid = auth.currentUser?.uid ?: return

        try {
            // 현재 주의 모든 날짜 확인
            val tempCal = calendar.clone() as Calendar
            tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

            for (i in 0 until 7) {
                val dateStr = dateFormat.format(tempCal.time)
                val votes = timeVoteRepository.getVotes(groupId, dateStr)
                
                // 사용자가 이 날짜에 투표했는지 확인
                val userVoted = votes.values.any { it.contains(uid) }
                if (userVoted) {
                    hasVoted = true
                    android.util.Log.d("TimeVoteFragment", "✅ 사용자가 이미 투표함: $dateStr")
                    break
                }
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            // UI 업데이트
            if (hasVoted) {
                // 기존 투표 값을 ViewModel에 로드
                loadExistingVotes()
                // 버튼 상태 업데이트
                updateButtonState()
                // 시간 버튼 비활성화
                disableTimeButtons()
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeVoteFragment", "투표 상태 확인 중 오류: ${e.message}", e)
        }
    }

    /**
     * 기존 투표 값을 ViewModel에 로드
     */
    private suspend fun loadExistingVotes() {
        val uid = auth.currentUser?.uid ?: return

        try {
            val tempCal = calendar.clone() as Calendar
            tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

            for (i in 0 until 7) {
                val dateStr = dateFormat.format(tempCal.time)
                val votes = timeVoteRepository.getVotes(groupId, dateStr)
                
                // 사용자가 투표한 시간 찾기
                votes.forEach { (time, voters) ->
                    if (voters.contains(uid)) {
                        // 시간 형식 변환: "14:00" -> "14시"
                        val timeDisplay = time.split(":")[0].toIntOrNull()?.let { "${it}시" } ?: return@forEach
                        // ViewModel에 추가
                        viewModel.toggleTimeSelection(dateStr, timeDisplay)
                    }
                }
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeVoteFragment", "기존 투표 로드 중 오류: ${e.message}", e)
        }
    }

    /**
     * 시간 버튼 비활성화
     */
    private fun disableTimeButtons() {
        timeButtons.forEach { button ->
            button.isEnabled = false
            button.alpha = 0.6f // 약간 투명하게 표시
        }
    }

    private fun setupButton() {
        // 초기화 버튼
        binding.btnReset.setOnClickListener {
            // 이미 투표를 완료한 경우 초기화 비활성화
            if (hasVoted) {
                Toast.makeText(requireContext(), "이미 저장된 투표는 초기화할 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pendingSelections = viewModel.pendingSelections.value
            val totalCount = pendingSelections.values.sumOf { it.size }

            if (totalCount == 0) {
                Toast.makeText(requireContext(), "초기화할 선택 항목이 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 확인 다이얼로그 표시
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("선택 초기화")
                .setMessage("선택한 모든 시간을 초기화하시겠습니까?")
                .setPositiveButton("초기화") { _, _ ->
                    // ViewModel의 장바구니 초기화
                    viewModel.clearPendingSelections()
                    
                    // 현재 선택된 날짜의 그리드 UI 업데이트
                    setupTimeGrid()
                    
                    // 버튼 상태 업데이트
                    updateButtonState()
                    
                    Toast.makeText(requireContext(), "선택이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 저장 버튼
        binding.btnCompleteVote.setOnClickListener {
            // ViewModel의 장바구니에서 모든 날짜의 선택된 시간 확인
            val pendingSelections = viewModel.pendingSelections.value
            val datesWithTimes = pendingSelections.filter { it.value.isNotEmpty() }

            if (datesWithTimes.isEmpty()) {
                Toast.makeText(requireContext(), "선택된 시간이 없습니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dateDisplayFormat = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREA)

            // 여러 날짜 정보를 메시지로 구성 - 실제 시간 목록 표시
            val message = buildString {
                append("다음 날짜에 선택한 시간을 최종적으로 저장하시겠습니까?\n\n")
                datesWithTimes.forEach { (dateStr, times) ->
                    val date = dateFormat.parse(dateStr) ?: return@buildString
                    val dateDisplay = dateDisplayFormat.format(date)
                    append("• $dateDisplay:\n")
                    // 시간을 정렬하여 표시
                    val sortedTimes = times.sorted()
                    sortedTimes.forEach { time ->
                        append("  - $time\n")
                    }
                }
            }

            // 확인 팝업 표시
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("시간 저장 확인")
                .setMessage(message)
                .setPositiveButton("저장") { _, _ ->
                    saveAllSelectedTimes()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    /**
     * ViewModel의 장바구니에 담긴 모든 시간을 Firestore에 저장
     */
    private fun saveAllSelectedTimes() {
        val uid = auth.currentUser?.uid ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 중복 클릭 방지
                binding.btnCompleteVote.isEnabled = false

                val pendingSelections = viewModel.pendingSelections.value
                val dateCount = pendingSelections.count { it.value.isNotEmpty() }
                val totalCount = pendingSelections.values.sumOf { it.size }

                android.util.Log.d("TimeVoteFragment", "📝 저장 시작: ${dateCount}개 날짜의 시간 투표 저장")

                // ViewModel의 saveAllPendingSelections 호출
                val result = viewModel.saveAllPendingSelections(groupId, uid)

                if (result.isFailure) {
                    throw result.exceptionOrNull() ?: Exception("저장 실패")
                }

                android.util.Log.d(
                    "TimeVoteFragment",
                    "✅ 모든 시간 저장 작업 완료 (${dateCount}개 날짜, ${totalCount}개 시간). 이제 멤버 투표 완료 여부를 확인합니다."
                )

                // 그룹 상태 확인 및 업데이트
                val group = groupRepository.getGroupDetail(groupId)
                if (group != null && group.status == "GROUP_CREATED") {
                    // 첫 투표 시 상태를 TIME_VOTE_REQUIRED로 변경
                    groupRepository.updateGroupStatus(groupId, "TIME_VOTE_REQUIRED")
                }

                Toast.makeText(
                    requireContext(),
                    "${dateCount}개 날짜의 시간이 저장되었습니다 ✅",
                    Toast.LENGTH_SHORT
                ).show()

                // 저장 완료 후 투표 상태 업데이트
                hasVoted = true
                disableTimeButtons()
                updateButtonState()

                // Firestore 동기화를 위해 잠시 대기 (1초)
                delay(1000)

                // 저장 직후 모든 멤버 투표 완료 여부 확인
                val allVoted = checkAllDatesVotedSync()

                if (allVoted != null) {
                    dismissWaitingDialog()
                    hideWaitingMessageOnScreen()

                    if (allVoted.second.isEmpty()) {
                        // 모든 멤버가 투표했지만 겹치는 시간이 없음
                        android.util.Log.d(
                            "TimeVoteFragment",
                            "⚠️ 모든 멤버 투표 완료했지만 겹치는 시간이 없음"
                        )
                        showNoOverlappingTimeDialog()
                    } else if (allVoted.second.size == 1) {
                        // 겹치는 시간이 하나만 있으면 자동으로 최종 시간으로 확정
                        android.util.Log.d(
                            "TimeVoteFragment",
                            "✅ 겹치는 시간이 하나만 있음 - 자동 확정: ${allVoted.second[0]}"
                        )
                        autoConfirmFinalTime(allVoted.first, allVoted.second[0])
                    } else {
                        // 모든 멤버가 투표 완료하고 겹치는 시간이 여러 개 -> 바로 최종 투표 화면으로 이동
                        android.util.Log.d(
                            "TimeVoteFragment",
                            "✅ 저장 직후 모든 멤버 투표 완료 확인! 바로 최종 투표로 이동 (겹치는 시간: ${allVoted.second.size}개)"
                        )
                        navigateToFinalVote(allVoted.first)
                    }
                } else {
                    // 아직 다른 멤버가 남았다면 대기 다이얼로그 표시
                    android.util.Log.d("TimeVoteFragment", "⏳ 다른 멤버들의 투표를 기다리는 중...")
                    showWaitingForMembersDialog()
                }

            } catch (e: CancellationException) {
                // 사용자가 화면을 나가는 등 정상적인 취소는 오류로 보지 않음
                android.util.Log.w("TimeVoteFragment", "저장 작업이 취소되었습니다: ${e.message}")
                // CancellationException은 다시 throw하지 않음 (정상적인 취소)
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteFragment", "투표 저장 중 오류 발생: ${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    "투표 저장 중 오류 발생: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                // 작업이 끝나면 버튼 다시 활성화
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED)) {
                    binding.btnCompleteVote.isEnabled = true
                }
            }
        }
    }

    private var waitingDialog: androidx.appcompat.app.AlertDialog? = null
    private var finalVoteConfirmationDialog: androidx.appcompat.app.AlertDialog? = null
    private var autoConfirmDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * 다른 멤버 투표 대기 중 메시지 표시
     */
    private fun showWaitingForMembersDialog() {
        // 이미 표시 중이면 무시
        if (waitingDialog?.isShowing == true) {
            return
        }

        waitingDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("시간 투표 대기 중")
            .setMessage("다른 멤버의 시간 투표를 기다리는 중입니다...")
            .setPositiveButton("뒤로가기") { _, _ ->
                // 뒤로가기 버튼 클릭 시 팝업 닫고 화면에 메시지 표시
                dismissWaitingDialog()
                showWaitingMessageOnScreen()
            }
            .setCancelable(true)
            .setOnCancelListener {
                // 취소 시에도 화면에 메시지 표시
                showWaitingMessageOnScreen()
            }
            .create()

        waitingDialog?.show()
    }

    /**
     * 화면에 대기 메시지 표시 (미투표 멤버 수 포함)
     */
    private fun showWaitingMessageOnScreen() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val group = groupRepository.getGroupDetail(groupId)
                val memberUids = group?.memberUids ?: emptyList()
                
                if (memberUids.isEmpty()) {
                    binding.tvWaitingMessage.visibility = View.VISIBLE
                    binding.tvWaitingMessage.text = "⏳ 아직 그룹원이 시간 투표를 완료하지 않았습니다"
                    return@launch
                }
                
                // 현재 주의 모든 날짜에서 투표한 멤버 확인
                val tempCal = calendar.clone() as Calendar
                tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                
                val votedMembers = mutableSetOf<String>()
                for (i in 0 until 7) {
                    val dateStr = dateFormat.format(tempCal.time)
                    val votes = timeVoteRepository.getVotes(groupId, dateStr)
                    votes.values.forEach { voters ->
                        votedMembers.addAll(voters)
                    }
                    tempCal.add(Calendar.DAY_OF_MONTH, 1)
                }
                
                val missingUids = memberUids.filter { it !in votedMembers }
                val missingCount = missingUids.size
                
                val message = if (missingCount > 0) {
                    "⏳ 아직 ${missingCount}명의 그룹원이 시간 투표를 완료하지 않았습니다. 모든 멤버가 시간 투표를 완료하면 다음 단계로 진행할 수 있습니다."
                } else {
                    "⏳ 아직 그룹원이 시간 투표를 완료하지 않았습니다"
                }
                
                binding.tvWaitingMessage.text = message
                binding.tvWaitingMessage.visibility = View.VISIBLE
                android.util.Log.d("TimeVoteFragment", "화면에 대기 메시지 표시: $message")
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteFragment", "대기 메시지 업데이트 중 오류: ${e.message}", e)
                binding.tvWaitingMessage.visibility = View.VISIBLE
                binding.tvWaitingMessage.text = "⏳ 아직 그룹원이 시간 투표를 완료하지 않았습니다"
            }
        }
    }

    /**
     * 화면의 대기 메시지 숨기기
     */
    private fun hideWaitingMessageOnScreen() {
        binding.tvWaitingMessage.visibility = View.GONE
        android.util.Log.d("TimeVoteFragment", "화면의 대기 메시지 숨김")
    }

    /**
     * 대기 중 메시지 닫기
     */
    private fun dismissWaitingDialog() {
        waitingDialog?.dismiss()
        waitingDialog = null
    }

    /**
     * 모든 멤버가 투표했는지 동기적으로 확인 (저장 직후 확인용)
     * @return Triple<날짜, 겹치는 시간 목록, 모든 멤버 투표 완료 여부> 또는 null
     *         - null: 아직 모든 멤버가 투표하지 않음
     *         - Triple(date, emptyList(), true): 모든 멤버가 투표했지만 겹치는 시간이 없음
     *         - Triple(date, times, true): 모든 멤버가 투표했고 겹치는 시간이 있음
     */
    private suspend fun checkAllDatesVotedSync(): Triple<String, List<String>, Boolean>? {
        try {
            val group = try {
                groupRepository.getGroupDetail(groupId)
            } catch (e: CancellationException) {
                android.util.Log.w("TimeVoteFragment", "그룹 조회 취소됨: ${e.message}")
                return null
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteFragment", "그룹 조회 실패: ${e.message}", e)
                return null
            }

            val memberUids = group?.memberUids ?: emptyList()

            if (memberUids.isEmpty()) {
                return null
            }

            // 현재 주의 모든 날짜 확인 (월~일, 7일)
            val tempCal = calendar.clone() as Calendar
            tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

            val datesToCheck = mutableListOf<String>()
            for (i in 0 until 7) {
                val dateStr = dateFormat.format(tempCal.time)
                datesToCheck.add(dateStr)
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            // 모든 멤버가 투표한 날짜 찾기
            var dateWithAllVoted: String? = null
            for (dateStr in datesToCheck) {
                val allVoted = timeVoteRepository.checkAllMembersVoted(groupId, dateStr, memberUids)
                if (allVoted) {
                    dateWithAllVoted = dateStr
                    break
                }
            }

            // 모든 멤버가 투표한 날짜가 없으면 null 반환
            if (dateWithAllVoted == null) {
                return null
            }

            // 모든 멤버가 투표한 날짜에서 겹치는 시간 확인
            val overlapping = timeVoteRepository.getOverlappingTimes(
                groupId,
                dateWithAllVoted,
                memberUids
            )
            if (overlapping.isNotEmpty()) {
                // 시간 형식 변환: "14:00" -> "14시"
                val overlappingTimes = overlapping.keys.sorted().map { timeStr ->
                    val hour = timeStr.split(":")[0].toIntOrNull() ?: 0
                    "${hour}시"
                }
                return Triple(dateWithAllVoted, overlappingTimes, true)
            } else {
                // 모든 멤버가 투표했지만 겹치는 시간이 없음
                return Triple(dateWithAllVoted, emptyList(), true)
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeVoteFragment", "모든 날짜 투표 확인 중 오류: ${e.message}", e)
            return null
        }
    }

    /**
     * 겹치는 시간이 하나만 있을 때 자동으로 최종 시간 확정
     */
    private fun autoConfirmFinalTime(date: String, timeDisplay: String) {
        if (hasNavigatedToFinalVote) return
        hasNavigatedToFinalVote = true

        // 시간 형식 변환: "14시" -> "14:00"
        val timeFormatted = timeDisplay.replace("시", ":00")

        // 날짜 표시 형식 변환
        val dateDisplayFormat = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREA)
        val dateObj = dateFormat.parse(date)
        val dateDisplay = dateObj?.let { dateDisplayFormat.format(it) } ?: date

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 최종 시간 확정
                val success = groupRepository.setFinalTime(groupId, date, timeFormatted)

                if (success) {
                    android.util.Log.d(
                        "TimeVoteFragment",
                        "✅ 최종 시간 자동 확정 성공: $date $timeFormatted"
                    )

                    // 기존 다이얼로그가 있으면 닫기
                    autoConfirmDialog?.dismiss()

                    // 자동 확정 팝업 메시지 표시
                    autoConfirmDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("시간 자동 확정")
                        .setMessage("겹치는 시간이 한 개밖에 없어 자동으로 해당 시간으로 선택되었습니다.\n\n${dateDisplay} ${timeDisplay}")
                        .setPositiveButton("확인") { _, _ ->
                            // GroupDetailFragment로 돌아가기 (popBackStack 사용)
                            // Navigation Stack에서 TimeVoteFragment를 제거하고 GroupDetailFragment로 돌아감
                            if (!findNavController().popBackStack()) {
                                // 만약 popBackStack이 실패하면 (예: GroupDetailFragment가 스택에 없으면)
                                // MainFragment에서 GroupDetailFragment로 이동
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        val group = groupRepository.getGroupDetail(groupId)
                                        val groupName = group?.groupName ?: ""

                                        val action =
                                            com.moyeoyo.app.ui.main.MainFragmentDirections.actionMainFragmentToGroupDetailFragment(
                                                groupId = groupId,
                                                groupName = groupName
                                            )
                                        findNavController().navigate(action)
                                    } catch (e: Exception) {
                                        android.util.Log.e(
                                            "TimeVoteFragment",
                                            "그룹 정보 조회 실패: ${e.message}",
                                            e
                                        )
                                        // 그룹 이름 없이도 이동
                                        val action =
                                            com.moyeoyo.app.ui.main.MainFragmentDirections.actionMainFragmentToGroupDetailFragment(
                                                groupId = groupId,
                                                groupName = ""
                                            )
                                        findNavController().navigate(action)
                                    }
                                }
                            }
                        }
                        .setCancelable(false)
                        .setOnDismissListener { autoConfirmDialog = null }
                        .create()

                    autoConfirmDialog?.show()
                } else {
                    android.util.Log.e("TimeVoteFragment", "❌ 최종 시간 자동 확정 실패")
                    Toast.makeText(requireContext(), "시간 확정에 실패했습니다.", Toast.LENGTH_SHORT)
                        .show()
                    hasNavigatedToFinalVote = false
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "TimeVoteFragment",
                    "최종 시간 자동 확정 중 오류: ${e.message}",
                    e
                )
                Toast.makeText(requireContext(), "오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                hasNavigatedToFinalVote = false
            }
        }
    }

    /**
     * 겹치는 시간이 없을 때 팝업 표시
     */
    private fun showNoOverlappingTimeDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("겹치는 시간 없음")
            .setMessage("겹치는 시간이 없습니다.\n상의 후 투표를 다시 진행해주세요.")
            .setPositiveButton("확인") { _, _ ->
                // 확인 버튼 클릭 시 대기 다이얼로그 닫기
                dismissWaitingDialog()
                // 사용자가 다시 시간을 선택할 수 있도록 화면 유지
                android.util.Log.d("TimeVoteFragment", "겹치는 시간 없음 - 사용자가 다시 시간 선택 가능")
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 최종 투표 화면으로 바로 이동 (팝업 없이)
     */
    private fun navigateToFinalVote(dateWithAllVoted: String) {
        if (hasNavigatedToFinalVote) return
        hasNavigatedToFinalVote = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 그룹 상태를 TIME_FINALIZING으로 변경
                groupRepository.updateGroupStatus(groupId, "TIME_FINALIZING")

                // 최종 시간 투표 화면으로 이동
                // TODO: Safe Args가 생성되면 Directions 사용
                findNavController().navigate(
                    com.moyeoyo.app.R.id.action_timeVoteFragment_to_finalTimeVoteFragment,
                    Bundle().apply {
                        putString("groupId", groupId)
                        putString("date", dateWithAllVoted)
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteFragment", "최종 투표 화면 이동 중 오류: ${e.message}", e)
                Toast.makeText(requireContext(), "오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
                hasNavigatedToFinalVote = false // 오류 발생 시 플래그 리셋
            }
        }
    }


    /**
     * 모든 멤버가 투표했는지 확인 (현재 선택된 날짜 기준)
     * 최소 하나의 날짜에 모든 멤버가 투표했고, 겹치는 시간이 있으면 최종 투표 화면으로 이동
     */
    private suspend fun checkAllDatesVoted() {
        try {
            val group = try {
                groupRepository.getGroupDetail(groupId)
            } catch (e: CancellationException) {
                android.util.Log.w("TimeVoteFragment", "그룹 조회 취소됨: ${e.message}")
                return
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteFragment", "그룹 조회 실패: ${e.message}", e)
                return
            }

            val memberUids = group?.memberUids ?: emptyList()

            if (memberUids.isEmpty()) {
                return
            }

            // 현재 주의 모든 날짜 확인 (월~일, 7일)
            val tempCal = calendar.clone() as Calendar
            tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

            val datesToCheck = mutableListOf<String>()
            for (i in 0 until 7) {
                val dateStr = dateFormat.format(tempCal.time)
                datesToCheck.add(dateStr)
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            // 최소 하나의 날짜에 모든 멤버가 투표했는지 확인
            var dateWithAllVoted: String? = null
            var overlappingTimes: List<String> = emptyList()
            var hasAllVotedButNoOverlap = false

            for (dateStr in datesToCheck) {
                val allVoted = timeVoteRepository.checkAllMembersVoted(groupId, dateStr, memberUids)
                if (allVoted) {
                    dateWithAllVoted = dateStr
                    // 모든 멤버가 투표한 날짜에서 겹치는 시간 확인
                    val overlapping = timeVoteRepository.getOverlappingTimes(groupId, dateStr, memberUids)
                    if (overlapping.isNotEmpty()) {
                        // 시간 형식 변환: "14:00" -> "14시"
                        overlappingTimes = overlapping.keys.sorted().map { timeStr ->
                            val hour = timeStr.split(":")[0].toIntOrNull() ?: 0
                            "${hour}시"
                        }
                        break
                    } else {
                        // 모든 멤버가 투표했지만 겹치는 시간이 없음
                        hasAllVotedButNoOverlap = true
                    }
                }
            }

            // 모든 멤버가 투표한 날짜가 있는 경우 처리
            if (dateWithAllVoted != null && !hasNavigatedToFinalVote) {
                // 대기 중 메시지 닫기 및 화면 메시지 숨기기
                dismissWaitingDialog()
                hideWaitingMessageOnScreen()

                if (overlappingTimes.isNotEmpty()) {
                    if (overlappingTimes.size == 1) {
                        // 겹치는 시간이 하나만 있으면 자동으로 최종 시간으로 확정
                        android.util.Log.d(
                            "TimeVoteFragment",
                            "✅ 겹치는 시간이 하나만 있음 - 자동 확정: ${overlappingTimes[0]}"
                        )
                        autoConfirmFinalTime(dateWithAllVoted, overlappingTimes[0])
                    } else {
                        // 겹치는 시간이 여러 개 있으면 바로 최종 투표 화면으로 이동
                        android.util.Log.d(
                            "TimeVoteFragment",
                            "✅ 모든 멤버 투표 완료 확인! 바로 최종 투표로 이동. (날짜: $dateWithAllVoted, 겹치는 시간: ${overlappingTimes.size}개)"
                        )
                        navigateToFinalVote(dateWithAllVoted)
                    }
                } else if (hasAllVotedButNoOverlap) {
                    // 모든 멤버가 투표했지만 겹치는 시간이 없음
                    android.util.Log.d(
                        "TimeVoteFragment",
                        "⚠️ 모든 멤버 투표 완료했지만 겹치는 시간이 없음"
                    )
                    hideWaitingMessageOnScreen()
                    showNoOverlappingTimeDialog()
                }
            } else {
                // 아직 모든 멤버가 투표하지 않음 - 화면에 대기 메시지 표시
                if (waitingDialog?.isShowing != true) {
                    showWaitingMessageOnScreen()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeVoteFragment", "모든 날짜 투표 확인 중 오류: ${e.message}", e)
        }
    }
}

