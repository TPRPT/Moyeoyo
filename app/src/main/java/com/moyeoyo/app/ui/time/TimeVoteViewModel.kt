package com.moyeoyo.app.ui.time

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moyeoyo.app.data.repository.TimeVoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 시간 투표 화면의 ViewModel
 * 주가 바뀌어도 선택 상태를 유지하는 '장바구니' 역할
 */
@HiltViewModel
class TimeVoteViewModel @Inject constructor(
    private val timeVoteRepository: TimeVoteRepository
) : ViewModel() {

    companion object {
        private const val TAG = "TimeVoteViewModel"
    }

    /**
     * ⭐ [핵심] 전체 선택 상태를 저장할 '장바구니'
     * Key: "yyyy-MM-dd", Value: Set<"HH시">
     * 주가 바뀌어도 절대 초기화되지 않음
     */
    private val _pendingSelections = MutableStateFlow<MutableMap<String, MutableSet<String>>>(mutableMapOf())
    val pendingSelections: StateFlow<Map<String, Set<String>>> = _pendingSelections.asStateFlow()

    /**
     * 사용자가 시간을 선택/해제할 때 호출되는 함수
     */
    fun toggleTimeSelection(date: String, time: String) {
        val currentSelections = _pendingSelections.value.toMutableMap()
        val timesForDate = currentSelections.getOrPut(date) { mutableSetOf() }.toMutableSet()

        if (timesForDate.contains(time)) {
            timesForDate.remove(time)
            Log.d(TAG, "시간 선택 해제: $date $time")
        } else {
            timesForDate.add(time)
            Log.d(TAG, "시간 선택: $date $time")
        }

        currentSelections[date] = timesForDate
        _pendingSelections.value = currentSelections
    }

    /**
     * 특정 날짜의 선택된 시간 목록 가져오기
     */
    fun getSelectedTimesForDate(date: String): Set<String> {
        return _pendingSelections.value[date]?.toSet() ?: emptySet()
    }

    /**
     * 특정 날짜와 시간이 선택되어 있는지 확인
     */
    fun isTimeSelected(date: String, time: String): Boolean {
        return _pendingSelections.value[date]?.contains(time) == true
    }

    /**
     * Firestore에서 로드한 데이터로 장바구니 초기화 (앱 시작 시 또는 주 변경 시)
     * 기존에 저장된 투표 데이터를 장바구니에 반영
     */
    fun initializeSelectionsFromFirestore(date: String, firestoreData: Map<String, List<String>>, uid: String) {
        val currentSelections = _pendingSelections.value.toMutableMap()
        val timesForDate = currentSelections.getOrPut(date) { mutableSetOf() }.toMutableSet()

        // Firestore에서 현재 사용자가 투표한 시간만 추출하여 장바구니에 반영
        firestoreData.forEach { (timeKey, voters) ->
            if (voters.contains(uid)) {
                // "14:00" -> "14시" 형식으로 변환
                val hour = timeKey.split(":")[0].toIntOrNull() ?: 0
                val timeText = "${hour}시"
                timesForDate.add(timeText)
            }
        }

        currentSelections[date] = timesForDate
        _pendingSelections.value = currentSelections
        Log.d(TAG, "Firestore 데이터로 장바구니 초기화: $date - ${timesForDate.size}개 시간")
    }

    /**
     * '저장' 버튼을 눌렀을 때, 장바구니에 담긴 모든 내용을 Firestore에 한번에 저장
     */
    suspend fun saveAllPendingSelections(groupId: String, uid: String): Result<Unit> {
        return try {
            val selectionsToSave = _pendingSelections.value

            if (selectionsToSave.isEmpty()) {
                Log.d(TAG, "저장할 선택 항목이 없음")
                return Result.success(Unit)
            }

            Log.d(TAG, "장바구니 내용 저장 시작: ${selectionsToSave.size}개 날짜")

            // 모든 날짜의 모든 시간을 병렬로 저장
            selectionsToSave.forEach { (dateStr, times) ->
                times.forEach { time ->
                    val formattedTime = time.replace("시", ":00")
                    timeVoteRepository.voteTime(groupId, dateStr, formattedTime, uid)
                }
            }

            Log.d(TAG, "✅ 장바구니 내용 저장 완료")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "전체 투표 저장 실패", e)
            Result.failure(e)
        }
    }

    /**
     * 장바구니 초기화 (필요한 경우)
     */
    fun clearPendingSelections() {
        _pendingSelections.value = mutableMapOf()
        Log.d(TAG, "장바구니 초기화")
    }

    /**
     * 특정 날짜의 선택 상태 초기화
     */
    fun clearSelectionsForDate(date: String) {
        val currentSelections = _pendingSelections.value.toMutableMap()
        currentSelections.remove(date)
        _pendingSelections.value = currentSelections
        Log.d(TAG, "날짜별 선택 상태 초기화: $date")
    }
}

