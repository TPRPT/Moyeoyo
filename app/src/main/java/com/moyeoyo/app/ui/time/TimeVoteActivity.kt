package com.moyeoyo.app.ui.time

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.databinding.ActivityTimeVoteBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class TimeVoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimeVoteBinding
    
    @Inject
    lateinit var timeVoteRepository: TimeVoteRepository
    
    @Inject
    lateinit var groupRepository: GroupRepository
    
    private val auth = FirebaseAuth.getInstance()

    private lateinit var groupId: String
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
    private var selectedDate: Date = Date()
    private val calendar = Calendar.getInstance()

    // 날짜별로 선택된 시간 관리 (날짜 문자열 -> 시간 Set)
    private val selectedTimesByDate = mutableMapOf<String, MutableSet<String>>()
    private val selectedTimes: MutableSet<String>
        get() = selectedTimesByDate.getOrPut(dateFormat.format(selectedDate)) { mutableSetOf() }
    
    private var selectedDayButton: View? = null

    // 드래그용 시간 버튼 리스트
    private val timeButtons = mutableListOf<MaterialButton>()
    
    // 모든 멤버 투표 완료 확인 플래그 (중복 화면 전환 방지)
    private var hasNavigatedToFinalVote = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeVoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("groupId") ?: run {
            Toast.makeText(this, "그룹 ID가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupWeekHeader()
        setupTimeGrid()
        setupButton()
        observeFirestoreVotes()
        
        // 초기화 시 모든 날짜에 대해 모든 멤버 투표 완료 확인
        lifecycleScope.launch {
            checkAllDatesVoted()
        }

        // 스크롤뷰 설정 & 드래그 리스너
        binding.scrollViewTimeSlots.isNestedScrollingEnabled = false
        binding.scrollViewTimeSlots.setOnTouchListener { _, event ->
            handleDragSelect(event)
            true
        }
    }

    // ----------------------- 드래그 선택 -----------------------

    private fun handleDragSelect(event: MotionEvent) {
        binding.scrollViewTimeSlots.requestDisallowInterceptTouchEvent(true)

        val x = event.rawX.toInt()
        val y = event.rawY.toInt()

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                timeButtons.forEach { button ->
                    val rect = Rect()
                    button.getGlobalVisibleRect(rect)

                    if (rect.contains(x, y)) {
                        selectTimeSlot(button)
                    }
                }
            }
        }
    }

    private fun selectTimeSlot(button: MaterialButton) {
        // ⚠️ tag를 Boolean으로 안전하게 캐스팅
        val isAlreadySelected = button.tag as? Boolean == true
        if (isAlreadySelected) return  // 이미 선택된 버튼이면 무시

        val time = button.text.toString()
        button.tag = true
        selectedTimes.add(time)

        button.backgroundTintList =
            ContextCompat.getColorStateList(this, R.color.brand_blue)
        button.setTextColor(ContextCompat.getColor(this, R.color.white))

        updateButtonState()
    }

    // ----------------------- 상단 UI -----------------------

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupWeekHeader() {
        updateWeekTitle()

        binding.btnPrevWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, -1)
            setupWeekDays()
            updateWeekTitle()
            // 주 변경 시 모든 날짜 관찰 다시 시작
            observeFirestoreVotes()
        }

        binding.btnNextWeek.setOnClickListener {
            calendar.add(Calendar.WEEK_OF_YEAR, 1)
            setupWeekDays()
            updateWeekTitle()
            // 주 변경 시 모든 날짜 관찰 다시 시작
            observeFirestoreVotes()
        }

        setupWeekDays()
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

            val button = LinearLayout(this).apply {
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

            val dayText = TextView(this).apply {
                text = dayLabel
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(this@TimeVoteActivity, R.color.black))
                textSize = 13f
            }

            val dateText = TextView(this).apply {
                text = dayFormat.format(date)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                setTextColor(ContextCompat.getColor(this@TimeVoteActivity, R.color.text_secondary))
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
        
        // 현재 선택된 날짜의 선택된 시간 가져오기
        val dateStr = dateFormat.format(selectedDate)
        val currentDateSelectedTimes = selectedTimesByDate.getOrPut(dateStr) { mutableSetOf() }

        val times = (0..23).map { String.format(Locale.KOREA, "%02d시", it) }

        times.forEach { time ->
            val isSelected = currentDateSelectedTimes.contains(time)
            
            val button = MaterialButton(this).apply {
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
                        ContextCompat.getColorStateList(this@TimeVoteActivity, R.color.brand_blue)
                    setTextColor(ContextCompat.getColor(this@TimeVoteActivity, R.color.white))
                } else {
                    backgroundTintList =
                        ContextCompat.getColorStateList(this@TimeVoteActivity, R.color.white)
                    setTextColor(ContextCompat.getColor(this@TimeVoteActivity, R.color.black))
                }

                setOnClickListener {
                    val wasSelected = tag as Boolean
                    tag = !wasSelected
                    if (!wasSelected) {
                        selectedTimes.add(time)
                        backgroundTintList =
                            ContextCompat.getColorStateList(this@TimeVoteActivity, R.color.brand_blue)
                        setTextColor(
                            ContextCompat.getColor(
                                this@TimeVoteActivity,
                                R.color.white
                            )
                        )
                    } else {
                        selectedTimes.remove(time)
                        backgroundTintList =
                            ContextCompat.getColorStateList(this@TimeVoteActivity, R.color.white)
                        setTextColor(
                            ContextCompat.getColor(
                                this@TimeVoteActivity,
                                R.color.black
                            )
                        )
                    }
                    updateButtonState()
                }
            }

            grid.addView(button)
            timeButtons.add(button)
        }
        
        // 버튼 상태 업데이트
        updateButtonState()
    }

    // ----------------------- Firestore 연동 -----------------------
    
    private var currentVoteObserver: kotlinx.coroutines.Job? = null

    private fun observeFirestoreVotes() {
        val uid = auth.currentUser?.uid ?: return

        // 이전 관찰자 취소
        currentVoteObserver?.cancel()
        
        // 현재 주의 모든 날짜를 관찰
        currentVoteObserver = lifecycleScope.launch {
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
                        android.util.Log.e("TimeVoteActivity", "투표 확인 중 오류: ${e.message}", e)
                    }
                }
            }
        }
    }
    

    private fun updateGridFromFirestore(data: Map<String, List<String>>, uid: String) {
        val grid = binding.gridTimeSlots
        val dateStr = dateFormat.format(selectedDate)
        val currentDateSelectedTimes = selectedTimesByDate.getOrPut(dateStr) { mutableSetOf() }

        for (i in 0 until grid.childCount) {
            val button = grid.getChildAt(i) as MaterialButton
            val timeKey = button.text.toString().replace("시", ":00")
            val voters = data[timeKey] ?: emptyList()
            val isMyVote = voters.contains(uid)

            if (isMyVote) {
                button.tag = true
                button.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.brand_blue)
                button.setTextColor(ContextCompat.getColor(this, R.color.white))
                // ⚠️ selectedTimes에도 추가해야 함 (중복 방지)
                val timeText = button.text.toString()
                if (!currentDateSelectedTimes.contains(timeText)) {
                    currentDateSelectedTimes.add(timeText)
                }
            } else {
                button.tag = false
                button.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.white)
                button.setTextColor(ContextCompat.getColor(this, R.color.black))
                // ⚠️ selectedTimes에서도 제거해야 함
                val timeText = button.text.toString()
                currentDateSelectedTimes.remove(timeText)
            }
        }

        updateButtonState()
    }

    // ----------------------- 하단 버튼 -----------------------

    private fun updateButtonState() {
        // 모든 날짜의 선택된 시간 개수 계산
        val totalCount = selectedTimesByDate.values.sumOf { it.size }
        val dateCount = selectedTimesByDate.count { it.value.isNotEmpty() }
        
        if (dateCount > 0) {
            binding.btnCompleteVote.text = "저장 (${dateCount}개 날짜, ${totalCount}개 시간)"
        } else {
            binding.btnCompleteVote.text = "저장"
        }

        val enabled = totalCount > 0
        binding.btnCompleteVote.isEnabled = enabled
        binding.btnCompleteVote.backgroundTintList =
            ContextCompat.getColorStateList(
                this,
                if (enabled) R.color.black else R.color.light_gray
            )
    }

    private fun setupButton() {
        binding.btnCompleteVote.setOnClickListener {
            // 모든 날짜의 선택된 시간 확인
            val datesWithTimes = selectedTimesByDate.filter { it.value.isNotEmpty() }
            
            if (datesWithTimes.isEmpty()) {
                Toast.makeText(this, "선택된 시간이 없습니다", Toast.LENGTH_SHORT).show()
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
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("시간 저장 확인")
                .setMessage(message)
                .setPositiveButton("저장") { _, _ ->
                    saveAllSelectedTimes(datesWithTimes)
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }
    
    /**
     * 모든 날짜의 선택된 시간을 한 번에 저장
     */
    private fun saveAllSelectedTimes(datesWithTimes: Map<String, MutableSet<String>>) {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                // 중복 클릭 방지
                binding.btnCompleteVote.isEnabled = false
                
                android.util.Log.d("TimeVoteActivity", "📝 저장 시작: ${datesWithTimes.size}개 날짜의 시간 투표 저장")
                
                // ⚠️ 모든 날짜의 모든 시간 저장 작업을 병렬로 실행하고 완료될 때까지 대기
                coroutineScope {
                    val allTasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
                    
                    datesWithTimes.forEach { (dateStr, times) ->
                        times.forEach { time ->
                            val task = async(Dispatchers.IO) {
                                val formattedTime = time.replace("시", ":00")
                                timeVoteRepository.voteTime(groupId, dateStr, formattedTime, uid)
                            }
                            allTasks.add(task)
                        }
                    }
                    
                    allTasks.awaitAll() // 모든 저장 작업이 완료될 때까지 여기서 대기
                }
                
                val totalCount = datesWithTimes.values.sumOf { it.size }
                android.util.Log.d("TimeVoteActivity", "✅ 모든 시간 저장 작업 완료 (${datesWithTimes.size}개 날짜, ${totalCount}개 시간). 이제 멤버 투표 완료 여부를 확인합니다.")
                
                // 그룹 상태 확인 및 업데이트
                val group = groupRepository.getGroupDetail(groupId)
                if (group != null && group.status == "GROUP_CREATED") {
                    // 첫 투표 시 상태를 TIME_VOTE_REQUIRED로 변경
                    groupRepository.updateGroupStatus(groupId, "TIME_VOTE_REQUIRED")
                }

                Toast.makeText(this@TimeVoteActivity, "${datesWithTimes.size}개 날짜의 시간이 저장되었습니다 ✅", Toast.LENGTH_SHORT).show()
                
                // Firestore 동기화를 위해 잠시 대기 (1초)
                delay(1000)
                
                // 저장 직후 모든 멤버 투표 완료 여부 확인
                val allVoted = checkAllDatesVotedSync()
                
                if (allVoted != null) {
                    dismissWaitingDialog()
                    
                    if (allVoted.second.isEmpty()) {
                        // 모든 멤버가 투표했지만 겹치는 시간이 없음
                        android.util.Log.d("TimeVoteActivity", "⚠️ 모든 멤버 투표 완료했지만 겹치는 시간이 없음")
                        showNoOverlappingTimeDialog()
                    } else {
                        // 모든 멤버가 투표 완료하고 겹치는 시간이 있음 -> 바로 확인 팝업 표시
                        android.util.Log.d("TimeVoteActivity", "✅ 저장 직후 모든 멤버 투표 완료 확인!")
                        showFinalVoteConfirmationDialog(allVoted.first, allVoted.second)
                    }
                } else {
                    // 아직 다른 멤버가 남았다면 대기 다이얼로그 표시
                    android.util.Log.d("TimeVoteActivity", "⏳ 다른 멤버들의 투표를 기다리는 중...")
                    showWaitingForMembersDialog()
                }
                
            } catch (e: CancellationException) {
                // 사용자가 화면을 나가는 등 정상적인 취소는 오류로 보지 않음
                android.util.Log.w("TimeVoteActivity", "저장 작업이 취소되었습니다: ${e.message}")
                // CancellationException은 다시 throw하지 않음 (정상적인 취소)
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteActivity", "투표 저장 중 오류 발생: ${e.message}", e)
                Toast.makeText(this@TimeVoteActivity, "투표 저장 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                // 작업이 끝나면 버튼 다시 활성화 (화면이 아직 살아있다면)
                if (!isFinishing && !isDestroyed) {
                    binding.btnCompleteVote.isEnabled = true
                }
            }
        }
    }
    
    private var waitingDialog: androidx.appcompat.app.AlertDialog? = null
    
    /**
     * 다른 멤버 투표 대기 중 메시지 표시
     */
    private fun showWaitingForMembersDialog() {
        // 이미 표시 중이면 무시
        if (waitingDialog?.isShowing == true) {
            return
        }
        
        waitingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("시간 투표 대기 중")
            .setMessage("다른 멤버의 시간 투표를 기다리는 중입니다...")
            .setCancelable(false)
            .create()
        
        waitingDialog?.show()
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
                android.util.Log.w("TimeVoteActivity", "그룹 조회 취소됨: ${e.message}")
                return null
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteActivity", "그룹 조회 실패: ${e.message}", e)
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
            val overlapping = timeVoteRepository.getOverlappingTimes(groupId, dateWithAllVoted, memberUids)
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
            android.util.Log.e("TimeVoteActivity", "모든 날짜 투표 확인 중 오류: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 겹치는 시간이 없을 때 팝업 표시
     */
    private fun showNoOverlappingTimeDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("겹치는 시간 없음")
            .setMessage("겹치는 시간이 없습니다.\n상의 후 투표를 다시 진행해주세요.")
            .setPositiveButton("확인") { _, _ ->
                // 확인 버튼 클릭 시 대기 다이얼로그 닫기
                dismissWaitingDialog()
                // 사용자가 다시 시간을 선택할 수 있도록 화면 유지
                android.util.Log.d("TimeVoteActivity", "겹치는 시간 없음 - 사용자가 다시 시간 선택 가능")
            }
            .setCancelable(true)
            .show()
    }
    
    /**
     * 최종 투표 확인 팝업 표시
     */
    private fun showFinalVoteConfirmationDialog(dateWithAllVoted: String, overlappingTimes: List<String>) {
        if (hasNavigatedToFinalVote) return
        
        val dateDisplayFormat = SimpleDateFormat("yyyy년 MM월 dd일 (E)", Locale.KOREA)
        val date = dateFormat.parse(dateWithAllVoted)
        val dateDisplay = date?.let { dateDisplayFormat.format(it) } ?: dateWithAllVoted
        
        val message = buildString {
            append("모든 멤버의 시간 투표가 완료되었습니다.\n\n")
            append("겹치는 시간:\n")
            overlappingTimes.forEach { time ->
                append("• $time\n")
            }
            append("\n최종 시간 투표로 넘어가시겠습니까?")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("최종 시간 투표")
            .setMessage(message)
            .setPositiveButton("이동") { _, _ ->
                hasNavigatedToFinalVote = true
                
                lifecycleScope.launch {
                    try {
                        // 그룹 상태를 TIME_FINALIZING으로 변경
                        groupRepository.updateGroupStatus(groupId, "TIME_FINALIZING")
                        
                        // 최종 시간 투표 화면으로 이동
                        val intent = android.content.Intent(this@TimeVoteActivity, FinalTimeVoteActivity::class.java).apply {
                            putExtra("groupId", groupId)
                            putExtra("date", dateWithAllVoted)
                        }
                        startActivity(intent)
                        finish()
                    } catch (e: Exception) {
                        android.util.Log.e("TimeVoteActivity", "최종 투표 화면 이동 중 오류: ${e.message}", e)
                        Toast.makeText(this@TimeVoteActivity, "오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
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
                android.util.Log.w("TimeVoteActivity", "그룹 조회 취소됨: ${e.message}")
                return
            } catch (e: Exception) {
                android.util.Log.e("TimeVoteActivity", "그룹 조회 실패: ${e.message}", e)
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
                // 대기 중 메시지 닫기
                dismissWaitingDialog()
                
                if (overlappingTimes.isNotEmpty()) {
                    // 겹치는 시간이 있으면 최종 투표 확인 팝업 표시
                    android.util.Log.d("TimeVoteActivity", "✅ 모든 멤버 투표 완료 확인! 최종 투표 확인 팝업 표시. (날짜: $dateWithAllVoted)")
                    showFinalVoteConfirmationDialog(dateWithAllVoted, overlappingTimes)
                } else if (hasAllVotedButNoOverlap) {
                    // 모든 멤버가 투표했지만 겹치는 시간이 없음
                    android.util.Log.d("TimeVoteActivity", "⚠️ 모든 멤버 투표 완료했지만 겹치는 시간이 없음")
                    showNoOverlappingTimeDialog()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TimeVoteActivity", "모든 날짜 투표 확인 중 오류: ${e.message}", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dismissWaitingDialog()
        currentVoteObserver?.cancel()
    }

}

