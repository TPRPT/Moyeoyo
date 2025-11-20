package com.moyeoyo.app.ui.groups

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Timestamp
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityGroupManageBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class GroupManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupManageBinding

    @Inject lateinit var groupRepository: GroupRepository

    private lateinit var groupId: String
    private var originalGroup: Group? = null

    // 일정 수정용 임시 값
    private var editedTimestamp: Timestamp? = null
    private var editedPlaceMap: MutableMap<String, Any>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getStringExtra("GROUP_ID") ?: run {
            Toast.makeText(this, "그룹 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupClickListeners()
        loadGroupInfo()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupClickListeners() {
        // 그룹 이름 저장
        binding.btnSaveGroupName.setOnClickListener {
            saveGroupName()
        }

        // 그룹 삭제
        binding.btnDeleteGroup.setOnClickListener {
            showDeleteGroupConfirmationDialog()
        }

// 날짜 선택
        binding.scheduleSection.fieldDate.setOnClickListener {
            showDatePicker()
        }

// 시간 선택
        binding.scheduleSection.fieldTime.setOnClickListener {
            showTimePicker()
        }

// 일정 저장 버튼
        binding.scheduleSection.btnSaveSchedule.setOnClickListener {
            saveSchedule()
        }
    }

    private fun loadGroupInfo() {
        lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            if (group == null) {
                Toast.makeText(this@GroupManageActivity, "그룹 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            originalGroup = group

            // 그룹 이름
            binding.editGroupName.setText(group.groupName)

            // 확정된 일정이 있다면 섹션 표시
            val hasConfirmedSchedule = group.confirmedTime != null && group.confirmedPlace != null
            if (hasConfirmedSchedule) {
                binding.scheduleSection.root.visibility = android.view.View.VISIBLE

                val cal = Calendar.getInstance().apply {
                    time = group.confirmedTime!!.toDate()
                }

                // 날짜/시간 표시
                val dateStr = String.format(
                    "%04d-%02d-%02d",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1,
                    cal.get(Calendar.DAY_OF_MONTH)
                )
                val timeStr = String.format(
                    "%02d:%02d",
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                )

                binding.scheduleSection.fieldDate.setText(dateStr)
                binding.scheduleSection.fieldTime.setText(timeStr)

                // 장소 이름
                val placeName = group.confirmedPlace?.get("name") as? String
                    ?: group.confirmedPlace?.get("address") as? String
                    ?: ""
                binding.scheduleSection.editPlaceName.setText(placeName)

                editedTimestamp = group.confirmedTime
                editedPlaceMap = (group.confirmedPlace as? MutableMap<String, Any>)?.toMutableMap()
                    ?: mutableMapOf()
            } else {
                binding.scheduleSection.root.visibility = android.view.View.GONE
            }
        }
    }

    // ---------------------------
    // 그룹 이름 저장
    // ---------------------------
    private fun saveGroupName() {
        val newName = binding.editGroupName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(this, "그룹 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val success = groupRepository.updateGroupName(groupId, newName)
            if (success) {
                Toast.makeText(this@GroupManageActivity, "그룹 이름을 수정했습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@GroupManageActivity, "그룹 이름 수정에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------
    // 일정 수정 (날짜/시간)
    // ---------------------------
    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        editedTimestamp?.let { cal.time = it.toDate() }

        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                    // 시간은 기존 값 유지
                    val hour = cal.get(Calendar.HOUR_OF_DAY)
                    val minute = cal.get(Calendar.MINUTE)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                }
                editedTimestamp = Timestamp(picked.time)
                val dateStr = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                binding.scheduleSection.fieldDate.setText(dateStr)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker() {
        val cal = Calendar.getInstance()
        editedTimestamp?.let { cal.time = it.toDate() }

        TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                val picked = Calendar.getInstance().apply {
                    if (editedTimestamp != null) {
                        time = editedTimestamp!!.toDate()
                    }
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                }
                editedTimestamp = Timestamp(picked.time)
                val timeStr = String.format("%02d:%02d", hourOfDay, minute)
                binding.scheduleSection.fieldTime.setText(timeStr)
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    // ---------------------------
    // 일정 저장
    // ---------------------------
    private fun saveSchedule() {
        val currentGroup = originalGroup
        if (currentGroup?.confirmedTime == null || currentGroup.confirmedPlace == null) {
            Toast.makeText(this, "확정된 일정이 없는 그룹입니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val newTimestamp = editedTimestamp
        val placeName = binding.scheduleSection.editPlaceName.text.toString().trim()

        if (newTimestamp == null) {
            Toast.makeText(this, "날짜와 시간을 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        if (placeName.isEmpty()) {
            Toast.makeText(this, "장소 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val placeMap = (editedPlaceMap ?: mutableMapOf()).apply {
            this["name"] = placeName
        }

        lifecycleScope.launch {
            val success = groupRepository.updateConfirmedSchedule(groupId, newTimestamp, placeMap)
            if (success) {
                Toast.makeText(this@GroupManageActivity, "일정을 수정했습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@GroupManageActivity, "일정 수정에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------
    // 그룹 삭제
    // ---------------------------
    private fun showDeleteGroupConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("그룹 삭제")
            .setMessage("정말로 이 그룹을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                deleteGroup()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteGroup() {
        lifecycleScope.launch {
            val success = groupRepository.deleteGroup(groupId)
            if (success) {
                Toast.makeText(this@GroupManageActivity, "그룹을 삭제했습니다.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this@GroupManageActivity, MainActivity::class.java))
                finishAffinity()
            } else {
                Toast.makeText(this@GroupManageActivity, "그룹 삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
