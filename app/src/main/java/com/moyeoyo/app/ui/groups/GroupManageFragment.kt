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
import com.moyeoyo.app.databinding.ActivityGroupManageBinding
import com.moyeoyo.app.widget.NextMeetingWidgetProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@AndroidEntryPoint
class GroupManageFragment : Fragment() {

    private var _binding: ActivityGroupManageBinding? = null
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
        _binding = ActivityGroupManageBinding.inflate(inflater, container, false)
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

        binding.scheduleSection.fieldDate.setOnClickListener { showDatePicker() }
        binding.scheduleSection.fieldTime.setOnClickListener { showTimePicker() }

        // ⭐ 장소 입력창 → 검색창 열기
        binding.scheduleSection.editPlaceName.apply {
            isFocusable = false
            keyListener = null
            setOnClickListener { startPlaceAutocomplete() }
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

            if (group.confirmedTime != null && group.confirmedPlace != null) {
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

                val placeName = group.confirmedPlace?.get("name") as? String
                    ?: group.confirmedPlace?.get("address") as? String
                    ?: ""

                binding.scheduleSection.editPlaceName.setText(placeName)

                editedTimestamp = group.confirmedTime
                editedPlaceMap = group.confirmedPlace?.toMutableMap() ?: mutableMapOf()
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
        if (editedPlaceMap == null) {
            Toast.makeText(requireContext(), "장소를 검색해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {

            // ⭐ 여기서 context 넣어서 호출해야함!
            val success = groupRepository.updateConfirmedSchedule(
                context = requireContext(),
                groupId = groupId,
                confirmedTime = editedTimestamp!!,
                confirmedPlace = editedPlaceMap!!
            )

            if (success) {
                Toast.makeText(requireContext(), "일정을 수정했습니다.", Toast.LENGTH_SHORT).show()

                // ⭐ 위젯 갱신
                NextMeetingWidgetProvider.requestUpdateAll(requireContext())

            } else {
                Toast.makeText(requireContext(), "일정 수정 실패", Toast.LENGTH_SHORT).show()
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
}

