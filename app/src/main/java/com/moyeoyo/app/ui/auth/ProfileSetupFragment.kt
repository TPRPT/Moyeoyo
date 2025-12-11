package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import androidx.fragment.app.setFragmentResultListener
import androidx.navigation.fragment.findNavController
import com.moyeoyo.app.ui.location.CurrentLocationFragment
import com.moyeoyo.app.databinding.FragmentProfileSetupBinding
import java.io.File

class ProfileSetupFragment : Fragment() {

    private var _binding: FragmentProfileSetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var progressDialog: ProgressDialog

    // Location Services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isSettingHomeLocation: Boolean = true // 현재 위치를 Home/Work 중 어디에 설정할지 결정

    // 프로필 이미지 Uri (갤러리/카메라 공통)
    private var imageUri: Uri? = null

    // 카메라로 찍을 사진이 저장될 Uri
    private var cameraImageUri: Uri? = null

    private lateinit var imgProfile: ImageView
    private lateinit var btnChangePhoto: ImageView
    private lateinit var inputNickname: EditText
    private lateinit var inputHome: EditText
    private lateinit var inputWork: EditText
    private lateinit var btnSave: Button

    // 현재 위치 버튼
    private lateinit var btnSetCurrentHomeLocation: Button
    private lateinit var btnSetCurrentWorkLocation: Button

    // 위치 데이터를 저장할 Map 변수 (LatLng 객체를 포함하여 임시 저장)
    private var homeLocationData: Map<String, Any>? = null
    private var workLocationData: Map<String, Any>? = null

    // Fragment Result 리스너는 onViewCreated에서 설정

    // ⭐ 위치 권한 요청 런처
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        progressDialog.dismiss()
        if (isGranted) {
            getCurrentLocation()
        } else {
            Snackbar.make(requireView(), "위치 권한이 거부되어 현재 위치를 설정할 수 없습니다.", Snackbar.LENGTH_LONG).show()
        }
    }

    // 갤러리 런처
    private val galleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                imageUri = result.data?.data
                Glide.with(this).load(imageUri).circleCrop().into(imgProfile)
            }
        }

    // 카메라 런처
    private val cameraLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) {
                imageUri = cameraImageUri
                Glide.with(this)
                    .load(cameraImageUri)
                    .circleCrop()
                    .into(imgProfile)
            }
        }

    // 카메라 권한 요청 런처
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Snackbar.make(requireView(), "카메라 권한이 필요합니다.", Snackbar.LENGTH_SHORT).show()
        }
    }

    // Home 주소 검색 런처
    private val homeAddressLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, isHome = true)
        }

    // Work 주소 검색 런처
    private val workAddressLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, isHome = false)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()

        // Fragment Result 리스너 설정
        requireActivity().supportFragmentManager.setFragmentResultListener(CurrentLocationFragment.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            Log.d("ProfileSetup", "Fragment result received in listener: $bundle")
            handleConfirmedLocation(bundle)
        }

        // Firebase 및 Location 초기화
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        // Google Places SDK 초기화
        if (!Places.isInitialized()) {
            try {
                val appInfo =
                    requireContext().packageManager.getApplicationInfo(requireContext().packageName, PackageManager.GET_META_DATA)
                val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")

                if (!apiKey.isNullOrEmpty()) {
                    Places.initialize(requireContext().applicationContext, apiKey)
                } else {
                    Log.e("PLACE_INIT", "ERROR: API_KEY not found in Manifest metadata.")
                }
            } catch (e: Exception) {
                Log.e("PLACE_INIT", "Failed to retrieve API Key from Manifest", e)
            }
        }

        // 프로그레스 다이얼로그
        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("프로필 저장 중...")
            setCancelable(false)
        }

        // View 연결
        imgProfile = binding.profileImage
        btnChangePhoto = binding.btnChangePhoto
        inputNickname = binding.inputNickname
        btnSave = binding.btnSaveProfile

        btnSetCurrentHomeLocation = binding.btnSetCurrentHome
        btnSetCurrentWorkLocation = binding.btnSetCurrentWork

        inputHome = binding.inputHome.apply {
            setOnClickListener { startPlaceAutocomplete(isHome = true) }
            isFocusable = false
            keyListener = null
        }
        inputWork = binding.inputWork.apply {
            setOnClickListener { startPlaceAutocomplete(isHome = false) }
            isFocusable = false
            keyListener = null
        }

        // 현재 위치 버튼 리스너
        btnSetCurrentHomeLocation.setOnClickListener {
            isSettingHomeLocation = true
            requestLocationPermission()
        }
        btnSetCurrentWorkLocation.setOnClickListener {
            isSettingHomeLocation = false
            requestLocationPermission()
        }

        // 프로필 이미지 클릭 → 다이얼로그(사진 찍기 / 갤러리)
        imgProfile.setOnClickListener { showImagePickerDialog() }
        btnChangePhoto.setOnClickListener { showImagePickerDialog() }

        // 저장 버튼
        btnSave.setOnClickListener { saveProfile() }

        // 회원 탈퇴 버튼 (기본적으로 숨김 - 기존 사용자만 보임)
        binding.btnDeleteAccount.visibility = View.GONE
        binding.btnDeleteAccount.setOnClickListener { showDeleteAccountDialog() }

        loadCurrentUserData()
    }

    override fun onResume() {
        super.onResume()
        // Fragment가 다시 나타날 때 Fragment Result를 확인
        // Navigation으로 돌아올 때 Fragment Result가 이미 설정되어 있을 수 있음
        findNavController().previousBackStackEntry?.savedStateHandle?.get<Bundle>(CurrentLocationFragment.RESULT_KEY)?.let { bundle ->
            Log.d("ProfileSetup", "Fragment result found in savedStateHandle: $bundle")
            findNavController().previousBackStackEntry?.savedStateHandle?.remove<Bundle>(CurrentLocationFragment.RESULT_KEY)
            handleConfirmedLocation(bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ===================== 상단 UI =====================

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    // ===================== 위치 관련 =====================

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation()
        } else {
            progressDialog.setMessage("위치 권한 요청 중...")
            progressDialog.show()
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        progressDialog.setMessage("현재 위치 찾는 중...")
        progressDialog.show()

        btnSetCurrentHomeLocation.isEnabled = false
        btnSetCurrentWorkLocation.isEnabled = false

        val cts = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { location ->
                    progressDialog.dismiss()
                    btnSetCurrentHomeLocation.isEnabled = true
                    btnSetCurrentWorkLocation.isEnabled = true

                    if (location != null) {
                        val latLng = LatLng(location.latitude, location.longitude)
                        Log.d(
                            "LOCATION",
                            "현재 위치 획득: lat=${location.latitude}, lng=${location.longitude}"
                        )

                        // CurrentLocationFragment로 Navigation
                        findNavController().navigate(
                            R.id.currentLocationFragment,
                            Bundle().apply {
                                putFloat("lat", latLng.latitude.toFloat())
                                putFloat("lng", latLng.longitude.toFloat())
                                putBoolean("isHome", isSettingHomeLocation)
                            }
                        )

                    } else {
                        Snackbar.make(
                            requireView(),
                            "위치 정보를 가져올 수 없습니다. GPS를 켜고 잠시 후 다시 시도해주세요.",
                            Snackbar.LENGTH_LONG
                        ).show()
                        Log.w("LOCATION", "getCurrentLocation returned null - GPS may be disabled")
                    }
                }
                .addOnFailureListener { e ->
                    progressDialog.dismiss()
                    btnSetCurrentHomeLocation.isEnabled = true
                    btnSetCurrentWorkLocation.isEnabled = true
                    Snackbar.make(requireView(), "위치 가져오기 실패: ${e.message}", Snackbar.LENGTH_LONG)
                        .show()
                    Log.e("LOCATION", "getCurrentLocation failed", e)
                }
        } catch (e: SecurityException) {
            progressDialog.dismiss()
            btnSetCurrentHomeLocation.isEnabled = true
            btnSetCurrentWorkLocation.isEnabled = true
            Snackbar.make(requireView(), "위치 권한이 필요합니다.", Snackbar.LENGTH_LONG).show()
            Log.e("LOCATION", "SecurityException", e)
        } catch (e: Exception) {
            progressDialog.dismiss()
            btnSetCurrentHomeLocation.isEnabled = true
            btnSetCurrentWorkLocation.isEnabled = true
            Snackbar.make(requireView(), "위치 가져오기 중 오류 발생: ${e.message}", Snackbar.LENGTH_LONG)
                .show()
            Log.e("LOCATION", "Exception", e)
        }
    }

    private fun handleConfirmedLocation(bundle: Bundle) {
        Log.d("ProfileSetup", "handleConfirmedLocation called with bundle: $bundle")
        
        @Suppress("UNCHECKED_CAST")
        val locationMap =
            bundle.getSerializable(CurrentLocationFragment.EXTRA_LOCATION_DATA) as? Map<String, Any>
        val isHome = bundle.getBoolean(CurrentLocationFragment.EXTRA_IS_HOME, true)

        Log.d("ProfileSetup", "handleConfirmedLocation: locationMap = $locationMap")
        Log.d("ProfileSetup", "handleConfirmedLocation: isHome = $isHome")

        if (locationMap != null) {
            // handlePlaceResult와 동일한 방식으로 처리
            val name = locationMap["name"] as? String
            val address = locationMap["address"] as? String ?: ""
            
            // name을 우선으로 사용, 없으면 address 사용 (handlePlaceResult와 동일)
            val addressText = name ?: address ?: "선택된 장소"
            val targetInput = if (isHome) inputHome else inputWork
            targetInput.setText(addressText)
            
            Log.d("ProfileSetup", "handleConfirmedLocation: Setting input text to: $addressText")

            // Fragment Result에서는 lat/lng가 분리되어 전달됨
            val lat = (locationMap["lat"] as? Number)?.toDouble()
            val lng = (locationMap["lng"] as? Number)?.toDouble()
            
            if (lat != null && lng != null) {
                val latLng = LatLng(lat, lng)
                
                // handlePlaceResult와 동일한 구조로 locationMap 생성
                val finalLocationMap = hashMapOf<String, Any>(
                    "name" to (name ?: (if (isHome) "집" else "직장")),
                    "address" to address,
                    "latLng" to latLng,
                    "placeId" to (locationMap["placeId"] as? String ?: "")
                )
                
                Log.d("ProfileSetup", "handleConfirmedLocation: finalLocationMap = $finalLocationMap")
                
                if (isHome) {
                    homeLocationData = finalLocationMap
                    Log.d("ProfileSetup", "handleConfirmedLocation: homeLocationData set = $homeLocationData")
                } else {
                    workLocationData = finalLocationMap
                    Log.d("ProfileSetup", "handleConfirmedLocation: workLocationData set = $workLocationData")
                }
                
                Snackbar.make(requireView(), "위치가 지도에서 최종 설정되었습니다.", Snackbar.LENGTH_SHORT).show()
            } else {
                Log.e("ProfileSetup", "handleConfirmedLocation: lat or lng is null")
                Snackbar.make(requireView(), "위치 정보가 올바르지 않습니다.", Snackbar.LENGTH_SHORT).show()
            }
        } else {
            Log.e("ProfileSetup", "handleConfirmedLocation: locationMap is null")
            Snackbar.make(requireView(), "위치 설정 결과 수신 실패.", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ===================== 프로필 데이터 로드 =====================

    private fun loadCurrentUserData() {
        val uid = auth.currentUser?.uid ?: return

        progressDialog.setMessage("프로필 정보 불러오는 중...")
        progressDialog.show()

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()
                if (doc.exists()) {
                    val nickname = doc.getString("nickname")
                    val photoUrl = doc.getString("photoUrl")

                    val homeMap = doc.get("homeLocation") as? Map<*, *>
                    val workMap = doc.get("workLocation") as? Map<*, *>

                    val homeAddress = homeMap?.get("addressName") as? String
                    val workAddress = workMap?.get("addressName") as? String

                    val homeGeoPoint = homeMap?.get("latLng") as? GeoPoint
                    val workGeoPoint = workMap?.get("latLng") as? GeoPoint

                    val homeLat = homeGeoPoint?.latitude
                    val homeLng = homeGeoPoint?.longitude
                    val workLat = workGeoPoint?.latitude
                    val workLng = workGeoPoint?.longitude

                    val homePlaceId = homeMap?.get("placeId") as? String
                    val workPlaceId = workMap?.get("placeId") as? String

                    if (!nickname.isNullOrEmpty()) {
                        inputNickname.setText(nickname)
                    }
                    if (!homeAddress.isNullOrEmpty() && homeLat != null && homeLng != null) {
                        // 이미 사용자가 새로운 위치를 설정했다면 덮어쓰지 않음
                        if (homeLocationData == null) {
                            inputHome.setText(homeAddress)
                            homeLocationData = mapOf(
                                "name" to (homeMap?.get("name") ?: "집"),
                                "address" to homeAddress,
                                "latLng" to LatLng(homeLat, homeLng),
                                "placeId" to (homePlaceId ?: "")
                            )
                        }
                    }
                    if (!workAddress.isNullOrEmpty() && workLat != null && workLng != null) {
                        // 이미 사용자가 새로운 위치를 설정했다면 덮어쓰지 않음
                        if (workLocationData == null) {
                            inputWork.setText(workAddress)
                            workLocationData = mapOf(
                                "name" to (workMap?.get("name") ?: "직장"),
                                "address" to workAddress,
                                "latLng" to LatLng(workLat, workLng),
                                "placeId" to (workPlaceId ?: "")
                            )
                        }
                    }

                    if (!photoUrl.isNullOrEmpty()) {
                        Glide.with(this).load(photoUrl).circleCrop().into(imgProfile)
                    }

                    btnSave.text = "프로필 수정 완료"
                    
                    // 기존 사용자이므로 회원 탈퇴 버튼 표시
                    binding.btnDeleteAccount.visibility = View.VISIBLE

                } else {
                    Log.w("PROFILE", "Firestore에 사용자 문서가 존재하지 않음: $uid")
                    // 신규 사용자이므로 회원 탈퇴 버튼 숨김
                    binding.btnDeleteAccount.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Log.e("PROFILE", "프로필 정보 로드 실패", e)
                Snackbar.make(
                    requireView(),
                    "프로필 로드 실패: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
    }

    // ===================== Place 자동완성 =====================

    private fun startPlaceAutocomplete(isHome: Boolean) {
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
            ).build(requireContext())

            if (isHome) {
                homeAddressLauncher.launch(intent)
            } else {
                workAddressLauncher.launch(intent)
            }
        } catch (e: GooglePlayServicesRepairableException) {
            Snackbar.make(
                binding.btnSaveProfile,
                "Google Play 서비스 오류 (수리 필요): ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
            Log.e("PLACE_API", "Repairable Exception", e)
        } catch (e: GooglePlayServicesNotAvailableException) {
            Snackbar.make(
                binding.btnSaveProfile,
                "Google Play 서비스 사용 불가: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
            Log.e("PLACE_API", "Not Available Exception", e)
        } catch (e: Exception) {
            Snackbar.make(
                binding.btnSaveProfile,
                "주소 검색 시작 실패: ${e.message}",
                Snackbar.LENGTH_LONG
            ).show()
            Log.e(
                "PLACE_API",
                "Generic Intent Launch Failed. CHECK API KEY/MANIFEST!",
                e
            )
        }
    }

    private fun handlePlaceResult(
        result: androidx.activity.result.ActivityResult,
        isHome: Boolean
    ) {
        if (result.resultCode == Activity.RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)

            val addressText = place.name ?: place.address ?: "선택된 장소"
            val targetInput = if (isHome) inputHome else inputWork
            targetInput.setText(addressText)

            val latLng = place.latLng

            val locationMap = hashMapOf<String, Any>(
                "name" to (place.name ?: (if (isHome) "집" else "직장")),
                "address" to (place.address ?: ""),
                "latLng" to (latLng as Any),
                "placeId" to (place.id ?: "")
            )

            if (isHome) {
                homeLocationData = locationMap
            } else {
                workLocationData = locationMap
            }

        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Log.d("PLACE", "사용자가 검색 취소")
        } else {
            val status = Autocomplete.getStatusFromIntent(result.data!!)
            val errorMsg = status.statusMessage ?: "알 수 없는 오류"

            Log.e("PLACE", "Autocomplete failed: $errorMsg. Status Code: ${status.statusCode}")
            Snackbar.make(
                requireView(),
                "주소 검색 오류: $errorMsg (코드: ${status.statusCode})",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ===================== 프로필 저장 =====================

    private fun saveProfile() {
        val nickname = inputNickname.text.toString().trim()

        if (nickname.isEmpty()) {
            Snackbar.make(inputNickname, "닉네임을 입력해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (homeLocationData == null) {
            Snackbar.make(inputHome, "집 주소를 검색해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser
        if (user == null) {
            Snackbar.make(btnSave, "로그인 정보가 없습니다.", Snackbar.LENGTH_LONG).show()
            return
        }

        progressDialog.show()

        if (imageUri != null) {
            val ref = storage.reference.child("profile_images/${user.uid}.jpg")
            ref.putFile(imageUri!!)
                .continueWithTask { task ->
                    if (!task.isSuccessful) {
                        throw task.exception ?: Exception("이미지 업로드 실패")
                    }
                    ref.downloadUrl
                }
                .addOnSuccessListener { downloadUrl ->
                    saveUserToFirestore(
                        user.uid,
                        nickname,
                        homeLocationData,
                        workLocationData,
                        downloadUrl.toString()
                    )
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    Snackbar.make(
                        btnSave,
                        "이미지 업로드 실패: ${it.message}",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
        } else {
            saveUserToFirestore(user.uid, nickname, homeLocationData, workLocationData, null)
        }
    }

    private fun saveUserToFirestore(
        uid: String,
        nickname: String,
        homeLocation: Map<String, Any>?,
        workLocation: Map<String, Any>?,
        imageUrl: String?
    ) {
        fun createFirestoreLocationMap(locationMap: Map<String, Any>?): Map<String, Any>? {
            if (locationMap == null || !locationMap.containsKey("latLng")) {
                return null
            }

            val latLngAny = locationMap["latLng"]
            val latLng = if (latLngAny is LatLng) latLngAny else null

            val geoPoint = if (latLng != null) {
                com.google.firebase.firestore.GeoPoint(latLng.latitude, latLng.longitude)
            } else {
                return null
            }

            return hashMapOf(
                "addressName" to (locationMap["address"] as? String ?: ""),
                "name" to (locationMap["name"] as? String ?: ""),
                "placeId" to (locationMap["placeId"] as? String ?: ""),
                "latLng" to geoPoint
            )
        }

        val firestoreHomeLocation = createFirestoreLocationMap(homeLocation)
        val firestoreWorkLocation = createFirestoreLocationMap(workLocation)

        val userData = hashMapOf<String, Any>(
            "nickname" to nickname
        )

        if (imageUrl != null) {
            userData["photoUrl"] = imageUrl   // 새 이미지 있을 때만 업데이트
        }

        if (firestoreHomeLocation != null) {
            userData["homeLocation"] = firestoreHomeLocation
        }
        if (firestoreWorkLocation != null) {
            userData["workLocation"] = firestoreWorkLocation
        }

        firestore.collection("users").document(uid)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(
                    binding.btnSaveProfile,
                    "프로필이 수정되었습니다 🎉",
                    Snackbar.LENGTH_SHORT
                ).show()

                binding.btnSaveProfile.postDelayed({
                    // MainFragment로 돌아가기
                    findNavController().popBackStack(R.id.mainFragment, false)
                }, 600)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(
                    binding.btnSaveProfile,
                    "수정 실패: ${it.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                Log.e("PROFILE", "Firestore 수정 실패", it)
            }
    }

    // ===================== 카메라 관련 =====================

    private fun showImagePickerDialog() {
        val options = arrayOf("사진 찍기", "갤러리에서 선택", "취소")

        AlertDialog.Builder(requireContext())
            .setTitle("프로필 이미지 선택")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> openCameraWithPermission()
                    1 -> openGallery()
                    2 -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
        galleryLauncher.launch(intent)
    }

    private fun openCameraWithPermission() {
        val permission = android.Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            if (shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(requireContext())
                    .setTitle("카메라 권한 필요")
                    .setMessage("프로필 사진을 찍기 위해 카메라 권한이 필요합니다.")
                    .setPositiveButton("허용") { _, _ ->
                        cameraPermissionLauncher.launch(permission)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            } else {
                cameraPermissionLauncher.launch(permission)
            }
        }
    }

    private fun openCamera() {
        val uri = createImageUri()
        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }

    private fun createImageUri(): Uri {
        val image = File(
            requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "profile_${System.currentTimeMillis()}.jpg"
        )
        return FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            image
        )
    }

    // ===================== 회원 탈퇴 =====================

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("회원 탈퇴")
            .setMessage("정말 회원 탈퇴를 하시겠습니까?\n탈퇴 시 모든 데이터가 삭제되며 복구할 수 없습니다.")
            .setPositiveButton("탈퇴") { _, _ ->
                deleteAccount()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun deleteAccount() {
        val user = auth.currentUser
        if (user == null) {
            Snackbar.make(requireView(), "로그인 정보가 없습니다.", Snackbar.LENGTH_SHORT).show()
            return
        }

        progressDialog.setMessage("회원 탈퇴 처리 중...")
        progressDialog.show()

        val uid = user.uid

        // 1. Firestore에서 사용자 데이터 삭제
        firestore.collection("users").document(uid)
            .delete()
            .addOnSuccessListener {
                // 2. Storage에서 프로필 이미지 삭제 (있는 경우)
                val photoUrl = user.photoUrl?.toString()
                if (!photoUrl.isNullOrEmpty() && photoUrl.contains("firebasestorage")) {
                    try {
                        val storageRef = storage.reference.child("profile_images/${uid}.jpg")
                        storageRef.delete()
                            .addOnSuccessListener {
                                Log.d("DELETE_ACCOUNT", "프로필 이미지 삭제 완료")
                                deleteFirebaseAuthUser(user)
                            }
                            .addOnFailureListener { e ->
                                Log.w("DELETE_ACCOUNT", "프로필 이미지 삭제 실패: ${e.message}")
                                // 이미지 삭제 실패해도 계정 삭제는 진행
                                deleteFirebaseAuthUser(user)
                            }
                    } catch (e: Exception) {
                        Log.w("DELETE_ACCOUNT", "프로필 이미지 삭제 중 오류: ${e.message}")
                        deleteFirebaseAuthUser(user)
                    }
                } else {
                    deleteFirebaseAuthUser(user)
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Snackbar.make(
                    requireView(),
                    "회원 탈퇴 실패: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                Log.e("DELETE_ACCOUNT", "Firestore 사용자 데이터 삭제 실패", e)
            }
    }

    private fun deleteFirebaseAuthUser(user: com.google.firebase.auth.FirebaseUser) {
        user.delete()
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(
                    requireView(),
                    "회원 탈퇴가 완료되었습니다.",
                    Snackbar.LENGTH_SHORT
                ).show()

                // 로그인 화면으로 이동
                requireView().postDelayed({
                    findNavController().navigate(R.id.loginFragment)
                }, 1000)
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Snackbar.make(
                    requireView(),
                    "회원 탈퇴 실패: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                Log.e("DELETE_ACCOUNT", "Firebase Auth 사용자 삭제 실패", e)
            }
    }
}

