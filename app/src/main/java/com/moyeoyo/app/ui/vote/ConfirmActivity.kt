package com.moyeoyo.app.ui.vote

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.material.snackbar.Snackbar
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.databinding.ActivityConfirmBinding
import kotlinx.coroutines.launch
import java.util.Calendar

import com.google.firebase.Timestamp

class ConfirmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmBinding
    private val groupRepository = GroupRepository()
    private lateinit var groupId: String
    private lateinit var rootView: View

    private var confirmedPlaceData: Map<String, Any>? = null
    private val confirmedTime = Calendar.getInstance()

    // Google Calendar의 패키지명
    private val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"

    // Place Autocomplete 결과를 받는 런처
    private val placeAutocompleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            handlePlaceSelection(place)
        } else if (result.resultCode == RESULT_CANCELED) {
            Snackbar.make(rootView, "장소 검색을 취소했습니다.", Snackbar.LENGTH_SHORT).show()
        } else {
            val status = Autocomplete.getStatusFromIntent(result.data!!)
            Snackbar.make(rootView, "장소 검색 실패: ${status.statusMessage} (코드: ${status.statusCode})", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmBinding.inflate(layoutInflater)
        setContentView(binding.root)
        rootView = binding.root

        initializePlacesSdk()

        groupId = intent.getStringExtra("GROUP_ID") ?: return finish().also {
            Toast.makeText(this, "그룹 ID를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
        }

        binding.backButtonConfirm.setOnClickListener { finish() }

        val groupName = intent.getStringExtra("GROUP_NAME") ?: "새 모임"
        binding.inputTitle.setText(groupName)

        setupInputListeners()

        binding.inputDate.setOnClickListener { showDatePicker() }
        binding.inputTime.setOnClickListener { showTimePicker() }

        binding.inputPlace.setOnClickListener { startPlaceAutocomplete() }

        binding.btnConfirmSchedule.setOnClickListener {
            if (validateInputs()) {
                confirmScheduleAndSaveToDb()
            }
        }

        binding.btnAddToGoogleCalendar.setOnClickListener {
            saveToCalendar(isGoogleCalendar = true)
        }
        binding.btnAddToOtherApp.setOnClickListener {
            saveToCalendar(isGoogleCalendar = false)
        }
    }

    private fun initializePlacesSdk() {
        if (Places.isInitialized()) return
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")

            if (!apiKey.isNullOrEmpty()) {
                Places.initialize(applicationContext, apiKey)
            } else {
                Snackbar.make(rootView, "🚨 Places API Key가 Manifest에 없습니다. 장소 검색 불가.", Snackbar.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Snackbar.make(rootView, "🚨 Places SDK 초기화 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun setupInputListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                binding.btnConfirmSchedule.isEnabled = validateInputs()
            }
        }
        binding.inputTitle.addTextChangedListener(watcher)
        binding.inputDate.addTextChangedListener(watcher)
        binding.inputTime.addTextChangedListener(watcher)
        binding.inputPlace.addTextChangedListener(watcher)
    }

    private fun validateInputs(): Boolean {
        return binding.inputTitle.text.isNotBlank() &&
                binding.inputDate.text.isNotBlank() &&
                binding.inputTime.text.isNotBlank() &&
                confirmedPlaceData != null
    }

    private fun showDatePicker() {
        val cal = confirmedTime
        val dialog = DatePickerDialog(this, { _, year, month, dayOfMonth ->
            cal.set(year, month, dayOfMonth)
            binding.inputDate.setText(String.format("%d년 %d월 %d일", year, month + 1, dayOfMonth))
            binding.btnConfirmSchedule.isEnabled = validateInputs()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dialog.show()
    }

    private fun showTimePicker() {
        val cal = confirmedTime
        val dialog = TimePickerDialog(this, { _, hourOfDay, minute ->
            cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
            cal.set(Calendar.MINUTE, minute)
            binding.inputTime.setText(String.format("%02d:%02d", hourOfDay, minute))
            binding.btnConfirmSchedule.isEnabled = validateInputs()
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false)
        dialog.show()
    }

    private fun startPlaceAutocomplete() {
        try {
            val fields = listOf(
                Place.Field.LAT_LNG,
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.ID
            )

            val intent = Autocomplete.IntentBuilder(
                AutocompleteActivityMode.OVERLAY,
                fields
            ).build(this)
            placeAutocompleteLauncher.launch(intent)
        } catch (e: GooglePlayServicesRepairableException) {
            Snackbar.make(rootView, "Google Play 서비스 오류: 수리 필요.", Snackbar.LENGTH_LONG).show()
        } catch (e: GooglePlayServicesNotAvailableException) {
            Snackbar.make(rootView, "Google Play 서비스 사용 불가.", Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(rootView, "장소 검색 시작 실패: ${e.message}.", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handlePlaceSelection(place: Place) {
        val latLng = place.latLng ?: return

        binding.inputPlace.setText(place.name ?: place.address ?: "선택된 장소")

        confirmedPlaceData = mapOf(
            "placeId" to (place.id ?: ""),
            "name" to (place.name ?: place.address ?: "장소"),
            "latitude" to latLng.latitude,
            "longitude" to latLng.longitude,
            "address" to (place.address ?: "")
        )
        binding.btnConfirmSchedule.isEnabled = validateInputs()
    }

    /**
     * 최종 일정 정보를 Firestore groups 컬렉션에 업데이트합니다.
     */
    private fun confirmScheduleAndSaveToDb() {
        val placeData = confirmedPlaceData ?: return
        val title = binding.inputTitle.text.toString()

        val confirmedTimeMillis = confirmedTime.timeInMillis
        val confirmedTimestamp = Timestamp(java.util.Date(confirmedTimeMillis))

        lifecycleScope.launch {
            val success = groupRepository.confirmGroupSchedule(
                groupId = groupId,
                confirmedPlace = placeData,
                confirmedTime = confirmedTimestamp,
                newTitle = title
            )

            if (success) {
                Toast.makeText(this@ConfirmActivity, "일정이 확정되고 DB에 저장되었습니다!", Toast.LENGTH_LONG).show()
                binding.btnAddToGoogleCalendar.isEnabled = true
                binding.btnAddToOtherApp.isEnabled = true
            } else {
                Toast.makeText(this@ConfirmActivity, "일정 확정 및 DB 저장 실패.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * ⭐ [FIX] Android Intent를 사용하여 캘린더 앱에 일정을 저장합니다.
     */
    private fun saveToCalendar(isGoogleCalendar: Boolean) {
        val beginTime = confirmedTime.timeInMillis
        val endTime = beginTime + 3600 * 1000

        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, binding.inputTitle.text.toString())
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
            putExtra(CalendarContract.Events.EVENT_LOCATION, confirmedPlaceData?.get("address") as? String)
            putExtra(CalendarContract.Events.DESCRIPTION, binding.inputMemo.text.toString())
        }

        if (isGoogleCalendar) {
            // ⭐ [FIX]: 패키지 명시 전, 해당 패키지를 처리할 수 있는 컴포넌트가 있는지 먼저 확인
            //            (구글 캘린더가 설치되어 있는지 확인하는 역할)
            intent.setPackage(GOOGLE_CALENDAR_PACKAGE)

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // 구글 캘린더 패키지가 명시되었으나 찾을 수 없는 경우,
                // 패키지 명시 없이 표준 캘린더 목록을 띄우거나 기본 앱으로 이동하도록 fallback
                intent.setPackage(null) // 패키지 명시 제거
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(intent, "캘린더 앱 선택"))
                } else {
                    Toast.makeText(this, "구글 캘린더 또는 다른 캘린더 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // '다른 캘린더 앱에 추가' 버튼은 항상 표준 인텐트 (선택창 제공)
            intent.setPackage(null)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "캘린더 앱 선택"))
            } else {
                Toast.makeText(this, "캘린더 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}