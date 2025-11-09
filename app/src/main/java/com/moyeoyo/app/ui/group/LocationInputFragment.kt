package com.moyeoyo.app.ui.group

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar
import com.moyeoyo.app.R
import com.moyeoyo.app.model.SelectedLocation


class LocationInputFragment : Fragment(R.layout.fragment_location_input) {

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private lateinit var btnConfirmLocation: Button
    private var locationSelected = false // 위치 선택 여부 추적

    // ✅ 선택된 위치 표시용 View
    private lateinit var layoutSelectedLocation: LinearLayout
    private lateinit var tvSelectedType: TextView
    private lateinit var tvSelectedAddress: TextView

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) getCurrentLocation()
        else Snackbar.make(requireView(), "위치 권한이 필요합니다.", Snackbar.LENGTH_SHORT).show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        // ✅ 선택된 위치 박스 초기화
        layoutSelectedLocation = view.findViewById(R.id.layout_selected_location)
        tvSelectedType = view.findViewById(R.id.tv_selected_type)
        tvSelectedAddress = view.findViewById(R.id.tv_selected_address)

        // 🔙 뒤로가기
        view.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            findNavController().navigateUp()
        }

        // 📍 현재 위치 버튼
        val btnUseCurrentLocation = view.findViewById<LinearLayout>(R.id.btn_use_current_location)
        btnUseCurrentLocation?.setOnClickListener {
            checkLocationPermissionAndRequest()
            setLocationSelected(true)
            showSelectedLocation("현재 위치", "서울시 강남구 역삼동 123-45") // 예시 주소
        }

        // ✅ 집/회사 선택
        val layoutHome = view.findViewById<RelativeLayout>(R.id.layoutHome)
        val layoutWork = view.findViewById<RelativeLayout>(R.id.layoutWork)
        val icHomeCheck = view.findViewById<ImageView>(R.id.icHomeCheck)
        val icWorkCheck = view.findViewById<ImageView>(R.id.icWorkCheck)

        layoutHome?.setOnClickListener {
            layoutHome.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_selected_outline_box)
            layoutWork?.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_light_gray_outline_box)
            icHomeCheck?.visibility = View.VISIBLE
            icWorkCheck?.visibility = View.GONE
            Snackbar.make(requireView(), "‘집’ 위치가 선택되었습니다.", Snackbar.LENGTH_SHORT).show()
            setLocationSelected(true)
            showSelectedLocation("집", "서울시 강남구 테헤란로 123")
        }

        layoutWork?.setOnClickListener {
            layoutWork.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_selected_outline_box)
            layoutHome?.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_light_gray_outline_box)
            icWorkCheck?.visibility = View.VISIBLE
            icHomeCheck?.visibility = View.GONE
            Snackbar.make(requireView(), "‘회사’ 위치가 선택되었습니다.", Snackbar.LENGTH_SHORT).show()
            setLocationSelected(true)
            showSelectedLocation("회사", "서울시 서초구 서초대로 456")
        }

        // 🧭 주소 검색
        val etSearch = view.findViewById<EditText>(R.id.et_search_address)
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrEmpty()) {
                    setLocationSelected(true)
                    showSelectedLocation("검색된 위치", s.toString())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

// ✅ 위치 확정 버튼
        btnConfirmLocation = view.findViewById(R.id.btn_confirm_location)
        setButtonInactive() // 초기 회색 상태

        btnConfirmLocation.setOnClickListener {
            if (locationSelected) {
                Snackbar.make(requireView(), "위치가 확정되었습니다!", Snackbar.LENGTH_SHORT).show()

                // ✅ 중간 지점 계산 화면으로 이동 (선택된 위치 전달)
                val selectedLocation = SelectedLocation(
                    name = tvSelectedType.text.toString(),
                    latitude = 37.5044, // 실제 구현 시 GPS 좌표 대입
                    longitude = 127.0225
                )

                val action = LocationInputFragmentDirections
                    .actionLocationInputFragmentToMidpointFragment(selectedLocation)
                findNavController().navigate(action)

            } else {
                Snackbar.make(requireView(), "위치를 먼저 선택해주세요.", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    // ✅ 선택된 위치 박스 표시 함수
    private fun showSelectedLocation(type: String, address: String) {
        layoutSelectedLocation.visibility = View.VISIBLE
        tvSelectedType.text = type
        tvSelectedAddress.text = address
    }

    private fun setLocationSelected(selected: Boolean) {
        locationSelected = selected
        if (selected) setButtonActive() else setButtonInactive()
    }

    private fun setButtonActive() {
        val drawable = (btnConfirmLocation.background.mutate() as GradientDrawable)
        drawable.setColor(ContextCompat.getColor(requireContext(), R.color.button_black_active))
    }

    private fun setButtonInactive() {
        val drawable = (btnConfirmLocation.background.mutate() as GradientDrawable)
        drawable.setColor(ContextCompat.getColor(requireContext(), R.color.button_gray_inactive))
    }

    private fun checkLocationPermissionAndRequest() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation()
        } else {
            requestLocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        val client = fusedLocationClient ?: return
        client.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                Snackbar.make(requireView(), "현재 위치가 설정되었습니다.", Snackbar.LENGTH_SHORT).show()
                setLocationSelected(true)
                showSelectedLocation("현재 위치", "위도 ${location.latitude}, 경도 ${location.longitude}")
            } else {
                Snackbar.make(requireView(), "GPS를 켜주세요.", Snackbar.LENGTH_SHORT).show()
            }
        }
    }
}
