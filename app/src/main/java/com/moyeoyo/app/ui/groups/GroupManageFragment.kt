package com.moyeoyo.app.ui.groups

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.firebase.Timestamp
import com.moyeoyo.app.R
import com.moyeoyo.app.data.local.deleteMeetingFromLocal
import com.moyeoyo.app.data.model.Group
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.FragmentGroupManageBinding
import com.moyeoyo.app.widget.NextMeetingWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class GroupManageFragment : Fragment() {

    private var _binding: FragmentGroupManageBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var groupRepository: GroupRepository

    private lateinit var groupId: String
    private var originalGroup: Group? = null

    private var editedTimestamp: Timestamp? = null
    private var editedPlaceMap: MutableMap<String, Any>? = null

    // ⭐ 주소 검색 런처
    private val placeSearchLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handlePlaceResult(result)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGroupManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigation arguments에서 groupId 가져오기
        groupId = arguments?.getString("groupId") ?: run {
            Toast.makeText(requireContext(), "그룹 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        // ⭐ Google Places 초기화
        if (!Places.isInitialized()) {
            val appInfo = requireContext().packageManager.getApplicationInfo(
                requireContext().packageName,
                PackageManager.GET_META_DATA
            )
            val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")
            if (apiKey != null) {
                Places.initialize(requireContext().applicationContext, apiKey)
            }
        }

        setupToolbar()
        setupClickListeners()
        loadGroupInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { 
            findNavController().popBackStack()
        }
    }

    private fun setupClickListeners() {

        binding.btnSaveGroupName.setOnClickListener { saveGroupName() }
        binding.btnDeleteGroup.setOnClickListener { showDeleteGroupConfirmationDialog() }
        binding.btnResetAllVotes.setOnClickListener { showResetAllVotesConfirmationDialog() }

        binding.scheduleSection.fieldDate.setOnClickListener { showDatePicker() }
        binding.scheduleSection.fieldTime.setOnClickListener { showTimePicker() }

        // ⭐ 장소 입력창 → 검색창 열기 (활성화된 경우에만)
        binding.scheduleSection.editPlaceName.apply {
            isFocusable = false
            keyListener = null
            setOnClickListener {
                if (isEnabled) {
                    startPlaceAutocomplete()
                }
            }
        }

        binding.scheduleSection.btnSaveSchedule.setOnClickListener { saveSchedule() }
    }

    // ----------------------------------------------------
    // 그룹 정보 불러오기
    // ----------------------------------------------------
    private fun loadGroupInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            if (group == null) {
                Toast.makeText(requireContext(), "그룹 정보를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
                return@launch
            }

            originalGroup = group
            binding.editGroupName.setText(group.groupName)

            // 시간이 확정된 경우 (시간만 확정 또는 시간+장소 모두 확정)
            if (group.confirmedTime != null) {
                binding.scheduleSection.root.visibility = View.VISIBLE

                val cal = Calendar.getInstance().apply { time = group.confirmedTime!!.toDate() }

                binding.scheduleSection.fieldDate.setText(
                    "%04d-%02d-%02d".format(
                        cal[Calendar.YEAR],
                        cal[Calendar.MONTH] + 1,
                        cal[Calendar.DAY_OF_MONTH]
                    )
                )

                binding.scheduleSection.fieldTime.setText(
                    "%02d:%02d".format(
                        cal[Calendar.HOUR_OF_DAY],
                        cal[Calendar.MINUTE]
                    )
                )

                editedTimestamp = group.confirmedTime

                // 장소가 확정된 경우에만 장소 필드 표시 및 편집 가능
                if (group.confirmedPlace != null) {
                    val placeName = group.confirmedPlace?.get("name") as? String
                        ?: group.confirmedPlace?.get("address") as? String
                        ?: ""
                    binding.scheduleSection.editPlaceName.setText(placeName)
                    binding.scheduleSection.editPlaceName.isEnabled = true
                    binding.scheduleSection.editPlaceName.alpha = 1f
                    editedPlaceMap = group.confirmedPlace?.toMutableMap() ?: mutableMapOf()
                } else {
                    // 시간만 확정된 경우: 장소 필드 비활성화
                    binding.scheduleSection.editPlaceName.setText("")
                    binding.scheduleSection.editPlaceName.isEnabled = false
                    binding.scheduleSection.editPlaceName.alpha = 0.5f
                    binding.scheduleSection.editPlaceName.hint = "장소는 아직 확정되지 않았습니다"
                    editedPlaceMap = null
                }
            } else {
                // 시간이 확정되지 않았으면 섹션 숨기기
                binding.scheduleSection.root.visibility = View.GONE
            }
        }
    }

    // ----------------------------------------------------
    // ⭐ 주소 검색 로직
    // ----------------------------------------------------
    private fun startPlaceAutocomplete() {
        try {
            val fields = listOf(
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.LAT_LNG,
                Place.Field.ID
            )

            val intent = Autocomplete.IntentBuilder(
                AutocompleteActivityMode.OVERLAY,
                fields
            ).build(requireContext())

            placeSearchLauncher.launch(intent)

        } catch (e: GooglePlayServicesRepairableException) {
            Toast.makeText(requireContext(), "Google Play 서비스 오류: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: GooglePlayServicesNotAvailableException) {
            Toast.makeText(requireContext(), "Google Play 서비스 사용 불가: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "주소 검색 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handlePlaceResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {

            val place = Autocomplete.getPlaceFromIntent(result.data!!)

            val addressText = place.name ?: place.address ?: ""
            binding.scheduleSection.editPlaceName.setText(addressText)

            val latLng = place.latLng
            editedPlaceMap = mutableMapOf(
                "name" to (place.name ?: ""),
                "address" to (place.address ?: ""),
                "latitude" to (latLng?.latitude ?: 0.0),
                "longitude" to (latLng?.longitude ?: 0.0),
                "placeId" to (place.id ?: "")
            )
        }
    }

    // ----------------------------------------------------
    // 날짜/시간 picker
    // ----------------------------------------------------
    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        editedTimestamp?.let { cal.time = it.toDate() }

        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
                }
                editedTimestamp = Timestamp(picked.time)
                binding.scheduleSection.fieldDate.setText(
                    "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                )
            },
            cal[Calendar.YEAR],
            cal[Calendar.MONTH],
            cal[Calendar.DAY_OF_MONTH]
        ).show()
    }

    private fun showTimePicker() {
        val cal = Calendar.getInstance()
        editedTimestamp?.let { cal.time = it.toDate() }

        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                val picked = Calendar.getInstance().apply {
                    if (editedTimestamp != null) time = editedTimestamp!!.toDate()
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                }
                editedTimestamp = Timestamp(picked.time)
                binding.scheduleSection.fieldTime.setText(
                    "%02d:%02d".format(hourOfDay, minute)
                )
            },
            cal[Calendar.HOUR_OF_DAY],
            cal[Calendar.MINUTE],
            true
        ).show()
    }

    // ----------------------------------------------------
    // ⭐ 일정 저장 (수정)
    // ----------------------------------------------------
    private fun saveSchedule() {
        if (editedTimestamp == null) {
            Toast.makeText(requireContext(), "날짜/시간을 선택해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val currentPlace = group?.confirmedPlace

            // 시간만 확정된 경우: 기존 장소 값(null) 유지
            // 시간+장소 모두 확정된 경우: 수정된 장소 값 사용
            val placeToSave = editedPlaceMap ?: currentPlace

            if (placeToSave == null && currentPlace == null) {
                // 시간만 업데이트
                val success = groupRepository.updateConfirmedTime(
                    context = requireContext(),
                    groupId = groupId,
                    confirmedTime = editedTimestamp!!
                )

                if (success) {
                    Toast.makeText(requireContext(), "시간을 수정했습니다.", Toast.LENGTH_SHORT).show()
                    NextMeetingWidgetProvider.requestUpdateAll(requireContext())
                } else {
                    Toast.makeText(requireContext(), "시간 수정 실패", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 시간과 장소 모두 업데이트
                val success = groupRepository.updateConfirmedSchedule(
                    context = requireContext(),
                    groupId = groupId,
                    confirmedTime = editedTimestamp!!,
                    confirmedPlace = placeToSave!!
                )

                if (success) {
                    Toast.makeText(requireContext(), "일정을 수정했습니다.", Toast.LENGTH_SHORT).show()
                    NextMeetingWidgetProvider.requestUpdateAll(requireContext())
                } else {
                    Toast.makeText(requireContext(), "일정 수정 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ----------------------------------------------------
    // 그룹 삭제
    // ----------------------------------------------------
    private fun showDeleteGroupConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("그룹 삭제")
            .setMessage("이 그룹을 정말 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> deleteGroup() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun saveGroupName() {
        val newName = binding.editGroupName.text.toString().trim()
        if (newName.isEmpty()) {
            Toast.makeText(requireContext(), "그룹 이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val success = groupRepository.updateGroupName(groupId, newName)
            if (success) {
                Toast.makeText(requireContext(), "그룹 이름을 수정했습니다.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "그룹 이름 수정 실패", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val success = groupRepository.deleteGroup(requireContext(), groupId)

            if (success) {

                // ⭐ Room 삭제 추가
                deleteMeetingFromLocal(requireContext(), groupId)

                // ⭐ 위젯 갱신
                NextMeetingWidgetProvider.requestUpdateAll(requireContext())

                Toast.makeText(requireContext(), "그룹이 삭제되었습니다.", Toast.LENGTH_LONG).show()
                
                // MainFragment로 이동 (모든 백 스택 제거)
                findNavController().popBackStack(R.id.mainFragment, false)
            }
        }
    }

    // ----------------------------------------------------
    // 전체 투표 초기화
    // ----------------------------------------------------
    private fun showResetAllVotesConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("전체 투표 초기화")
            .setMessage("모든 투표 결과를 삭제하고 처음부터 다시 시작하시겠습니까?\n\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("초기화") { _, _ -> resetAllVotes() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun resetAllVotes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = groupRepository.resetAllVotes(requireContext(), groupId)

                if (success) {
                    // ⭐ 위젯 갱신
                    NextMeetingWidgetProvider.requestUpdateAll(requireContext())

                    Toast.makeText(requireContext(), "모든 투표가 초기화되었습니다.", Toast.LENGTH_LONG).show()
                    
                    // 그룹 정보 다시 불러오기
                    loadGroupInfo()
                } else {
                    android.util.Log.e("GroupManageFragment", "❌ 투표 초기화 실패: resetAllVotes가 false 반환")
                    Toast.makeText(requireContext(), "투표 초기화에 실패했습니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("GroupManageFragment", "❌ 투표 초기화 중 예외 발생: ${e.message}", e)
                Toast.makeText(requireContext(), "투표 초기화 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

