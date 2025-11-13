package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint // ⭐ GeoPoint 임포트
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

// Places SDK 및 Maps 관련 Imports
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import java.io.IOException
import java.util.Locale


class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var progressDialog: ProgressDialog

    // Location Services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var rootView: View
    private var isSettingHomeLocation: Boolean = true // 현재 위치를 Home/Work 중 어디에 설정할지 결정

    private var imageUri: Uri? = null

    private lateinit var imgProfile: ImageView
    private lateinit var btnChangePhoto: ImageView
    private lateinit var inputNickname: EditText
    private lateinit var inputHome: EditText
    private lateinit var inputWork: EditText
    private lateinit var btnSave: Button

    // 현재 위치 버튼 (XML에 추가되었다고 가정)
    private lateinit var btnSetCurrentHomeLocation: Button
    private lateinit var btnSetCurrentWorkLocation: Button


    // 위치 데이터를 저장할 Map 변수 (LatLng 객체를 포함하여 임시 저장)
    private var homeLocationData: Map<String, Any>? = null
    private var workLocationData: Map<String, Any>? = null

    // ⭐ [NEW] ConfirmLocationActivity 결과를 받는 런처
    private val confirmLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ConfirmLocationActivity.RESULT_CODE_LOCATION_CONFIRMED) {
            handleConfirmedLocation(result.data)
        } else {
            // 사용자가 지도 화면에서 취소하거나 뒤로가기를 눌렀을 경우
            Snackbar.make(rootView, "위치 설정을 취소했습니다.", Snackbar.LENGTH_SHORT).show()
        }
    }


    // ⭐ 위치 권한 요청 런처
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        progressDialog.dismiss()
        if (isGranted) {
            getCurrentLocation()
        } else {
            Snackbar.make(rootView, "위치 권한이 거부되어 현재 위치를 설정할 수 없습니다.", Snackbar.LENGTH_LONG).show()
        }
    }


    // 갤러리 런처 (타입 명시)
    private val galleryLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            imageUri = result.data?.data
            Glide.with(this).load(imageUri).circleCrop().into(imgProfile)
        }
    }

    // Home 주소 검색 런처 (타입 명시)
    private val homeAddressLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePlaceResult(result, isHome = true)
    }

    // Work 주소 검색 런처 (타입 명시)
    private val workAddressLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handlePlaceResult(result, isHome = false)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)
        rootView = findViewById(android.R.id.content)

        // Firebase 및 Location 초기화
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Google Places SDK 초기화 (변경 없음)
        if (!Places.isInitialized()) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")

                if (!apiKey.isNullOrEmpty()) {
                    Places.initialize(applicationContext, apiKey)
                } else {
                    Log.e("PLACE_INIT", "ERROR: API_KEY not found in Manifest metadata.")
                }
            } catch (e: Exception) {
                Log.e("PLACE_INIT", "Failed to retrieve API Key from Manifest", e)
            }
        }

        // 프로그레스 다이얼로그
        progressDialog = ProgressDialog(this).apply {
            setMessage("프로필 저장 중...")
            setCancelable(false)
        }

        // View 연결 및 리스너 설정
        imgProfile = findViewById(R.id.profile_image)
        btnChangePhoto = findViewById(R.id.btn_change_photo)
        inputNickname = findViewById(R.id.input_nickname)
        btnSave = findViewById(R.id.btn_save_profile)

        // 버튼 연결
        btnSetCurrentHomeLocation = findViewById<Button>(R.id.btn_set_current_home)
        btnSetCurrentWorkLocation = findViewById<Button>(R.id.btn_set_current_work)


        // inputHome / inputWork 클릭 시 주소 검색 시작 (직접 입력 방지)
        inputHome = findViewById<EditText>(R.id.input_home).apply {
            setOnClickListener { startPlaceAutocomplete(isHome = true) }
            isFocusable = false
            keyListener = null
        }
        inputWork = findViewById<EditText>(R.id.input_work).apply {
            setOnClickListener { startPlaceAutocomplete(isHome = false) }
            isFocusable = false
            keyListener = null
        }

        // ⭐ [NEW] 현재 위치 버튼 리스너
        btnSetCurrentHomeLocation.setOnClickListener {
            isSettingHomeLocation = true
            requestLocationPermission()
        }
        btnSetCurrentWorkLocation.setOnClickListener {
            isSettingHomeLocation = false
            requestLocationPermission()
        }


        // 갤러리 열기 함수
        val openGallery = {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            galleryLauncher.launch(intent)
        }

        imgProfile.setOnClickListener { openGallery() }
        btnChangePhoto.setOnClickListener { openGallery() }

        // 저장 버튼
        btnSave.setOnClickListener { saveProfile() }

        loadCurrentUserData()
    }

    // =========================================================================
    // ⭐ [MODIFIED] 현재 위치 기반 로직 (지도 확인 화면으로 이동) ⭐
    // =========================================================================

    /**
     * 위치 권한을 요청합니다.
     */
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getCurrentLocation()
        } else {
            progressDialog.setMessage("위치 권한 요청 중...")
            progressDialog.show()
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * 현재 디바이스의 위치를 가져와 ConfirmLocationActivity로 전달합니다.
     */
    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        progressDialog.setMessage("현재 위치 찾는 중...")
        progressDialog.show()

        // 버튼 비활성화 (중복 요청 방지)
        btnSetCurrentHomeLocation.isEnabled = false
        btnSetCurrentWorkLocation.isEnabled = false


        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                progressDialog.dismiss()
                btnSetCurrentHomeLocation.isEnabled = true
                btnSetCurrentWorkLocation.isEnabled = true

                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)

                    // ConfirmLocationActivity로 이동하여 지도에서 위치를 확인하도록 함
                    val intent = ConfirmLocationActivity.newIntent(this, latLng, isSettingHomeLocation)
                    confirmLocationLauncher.launch(intent)

                } else {
                    Snackbar.make(rootView, "위치 정보를 가져올 수 없습니다. GPS를 확인하세요.", Snackbar.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                btnSetCurrentHomeLocation.isEnabled = true
                btnSetCurrentWorkLocation.isEnabled = true
                Snackbar.make(rootView, "위치 가져오기 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
                Log.e("LOCATION", "Last location failed", e)
            }
    }


    /**
     * ⭐ [NEW] ConfirmLocationActivity로부터 최종 확인된 위치 데이터를 받아서 처리합니다.
     */
    private fun handleConfirmedLocation(data: Intent?) {
        data?.extras?.let { extras ->
            @Suppress("UNCHECKED_CAST")
            val locationMap = extras.getSerializable(ConfirmLocationActivity.EXTRA_LOCATION_DATA) as? Map<String, Any>
            val isHome = extras.getBoolean(ConfirmLocationActivity.EXTRA_IS_HOME, true)

            if (locationMap != null) {
                // ConfirmLocationActivity에서 Geocoding을 통해 얻은 주소로 UI 업데이트
                val address = locationMap["address"] as? String ?: "주소 확인됨"
                val targetInput = if (isHome) inputHome else inputWork
                targetInput.setText(address)

                // 최종 데이터를 내부 Map 변수에 저장 (저장 시 Firestore 포맷으로 변환됨)
                if (isHome) {
                    homeLocationData = locationMap
                } else {
                    workLocationData = locationMap
                }
                Snackbar.make(rootView, "위치가 지도에서 최종 설정되었습니다.", Snackbar.LENGTH_SHORT).show()
            } else {
                Snackbar.make(rootView, "위치 설정 결과 수신 실패.", Snackbar.LENGTH_SHORT).show()
            }
        }
    }


    // =========================================================================
    // 기존 로직 (DB 구조 변경 반영)
    // =========================================================================

    /**
     * 💡 [수정] 현재 로그인된 유저의 Firestore 데이터를 불러와 UI에 채웁니다. (GeoPoint 읽기)
     */
    private fun loadCurrentUserData() {
        val uid = auth.currentUser?.uid ?: return

        // 로딩 시작
        progressDialog.setMessage("프로필 정보 불러오는 중...")
        progressDialog.show()

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()
                if (doc.exists()) {
                    val nickname = doc.getString("nickname")
                    val photoUrl = doc.getString("photoUrl")

                    // ⭐ [GeoPoint Read] GeoPoint와 주소 데이터를 로드
                    val homeMap = doc.get("homeLocation") as? Map<*, *>
                    val workMap = doc.get("workLocation") as? Map<*, *>

                    val homeAddress = homeMap?.get("addressName") as? String // ⭐ addressName 사용
                    val workAddress = workMap?.get("addressName") as? String

                    // ⭐ GeoPoint를 읽어와 LatLng으로 변환
                    val homeGeoPoint = homeMap?.get("latLng") as? GeoPoint // ⭐ latLng 키에서 GeoPoint 읽기
                    val workGeoPoint = workMap?.get("latLng") as? GeoPoint

                    val homeLat = homeGeoPoint?.latitude
                    val homeLng = homeGeoPoint?.longitude
                    val workLat = workGeoPoint?.latitude
                    val workLng = workGeoPoint?.longitude

                    val homePlaceId = homeMap?.get("placeId") as? String
                    val workPlaceId = workMap?.get("placeId") as? String


                    // UI 채우기
                    if (!nickname.isNullOrEmpty()) {
                        inputNickname.setText(nickname)
                    }
                    if (!homeAddress.isNullOrEmpty() && homeLat != null && homeLng != null) {
                        inputHome.setText(homeAddress)
                        // LocationData Map을 LatLng 객체를 포함하여 재구성
                        homeLocationData = mapOf(
                            "name" to (homeMap?.get("name") ?: "집"),
                            "address" to homeAddress,
                            "latLng" to LatLng(homeLat, homeLng), // LatLng 객체 임시 저장
                            "placeId" to (homePlaceId ?: "")
                        )
                    }
                    if (!workAddress.isNullOrEmpty() && workLat != null && workLng != null) {
                        inputWork.setText(workAddress)
                        workLocationData = mapOf(
                            "name" to (workMap?.get("name") ?: "직장"),
                            "address" to workAddress,
                            "latLng" to LatLng(workLat, workLng), // LatLng 객체 임시 저장
                            "placeId" to (workPlaceId ?: "")
                        )
                    }

                    // 사진 로드
                    if (!photoUrl.isNullOrEmpty()) {
                        Glide.with(this).load(photoUrl).circleCrop().into(imgProfile)
                    }

                    // 버튼 텍스트 변경 (선택 사항)
                    btnSave.text = "프로필 수정 완료"

                } else {
                    Log.w("PROFILE", "Firestore에 사용자 문서가 존재하지 않음: $uid")
                    // 신규 유저는 초기 설정 그대로 진행
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Log.e("PROFILE", "프로필 정보 로드 실패", e)
                Snackbar.make(findViewById(android.R.id.content), "프로필 로드 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
    }


    /**
     * Place Autocomplete Intent를 실행합니다. (변경 없음)
     */
    private fun startPlaceAutocomplete(isHome: Boolean) {
        try {
            // 원하는 필드 지정 (위경도, 장소 이름, 주소, Place ID)
            val fields = listOf(
                Place.Field.LAT_LNG,
                Place.Field.NAME,
                Place.Field.ADDRESS,
                Place.Field.ID // ⭐ Place ID 추가
            )

            // Intent 빌드
            val intent = Autocomplete.IntentBuilder(
                AutocompleteActivityMode.OVERLAY,
                fields
            )
                .build(this)

            // 런처 실행
            if (isHome) {
                homeAddressLauncher.launch(intent)
            } else {
                workAddressLauncher.launch(intent)
            }
            // 🚨 Google Play Services 관련 예외 처리 강화
        } catch (e: GooglePlayServicesRepairableException) {
            Snackbar.make(findViewById(R.id.btn_save_profile), "Google Play 서비스 오류 (수리 필요): ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Repairable Exception", e)
        } catch (e: GooglePlayServicesNotAvailableException) {
            Snackbar.make(findViewById(R.id.btn_save_profile), "Google Play 서비스 사용 불가: ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Not Available Exception", e)
        } catch (e: Exception) {
            Snackbar.make(findViewById(R.id.btn_save_profile), "주소 검색 시작 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Generic Intent Launch Failed. CHECK API KEY/MANIFEST!", e)
        }
    }

    /**
     * Place Autocomplete 결과를 처리하고 UI와 내부 변수를 업데이트합니다.
     */
    private fun handlePlaceResult(
        result: androidx.activity.result.ActivityResult,
        isHome: Boolean
    ) {
        if (result.resultCode == Activity.RESULT_OK) {
            // 결과 성공 처리
            val place = Autocomplete.getPlaceFromIntent(result.data!!)

            val addressText = place.name ?: place.address ?: "선택된 장소"
            val targetInput = if (isHome) inputHome else inputWork
            targetInput.setText(addressText)

            val latLng = place.latLng

            // ⭐ LocationMap에 Place ID를 포함
            val locationMap = hashMapOf<String, Any>(
                "name" to (place.name ?: (if (isHome) "집" else "직장")),
                "address" to (place.address ?: ""), // 임시 주소 (addressName 필드로 변환 예정)
                "latLng" to (latLng as Any),
                "placeId" to (place.id ?: "") // ⭐ Place ID
            )

            if (isHome) {
                homeLocationData = locationMap
            } else {
                workLocationData = locationMap
            }

        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            Log.d("PLACE", "사용자가 검색 취소")
        } else {
            // Autocomplete Activity 내부 오류 상세 진단
            val status = Autocomplete.getStatusFromIntent(result.data!!)
            val errorMsg = status.statusMessage ?: "알 수 없는 오류"

            Log.e("PLACE", "Autocomplete failed: $errorMsg. Status Code: ${status.statusCode}")
            Snackbar.make(findViewById(android.R.id.content), "주소 검색 오류: ${errorMsg} (코드: ${status.statusCode})", Snackbar.LENGTH_LONG).show()
        }
    }


    private fun saveProfile() {
        // ... (유효성 검사 로직 유지)

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

        // ... (이미지 업로드 로직 유지)

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
                    saveUserToFirestore(user.uid, nickname, homeLocationData, workLocationData, downloadUrl.toString())
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    Snackbar.make(btnSave, "이미지 업로드 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
                }
        } else {
            saveUserToFirestore(user.uid, nickname, homeLocationData, workLocationData, null)
        }
    }

    /**
     * ⭐ [GeoPoint Write] Firestore에 GeoPoint를 포함하는 Map을 생성합니다.
     */
    private fun saveUserToFirestore(
        uid: String,
        nickname: String,
        homeLocation: Map<String, Any>?,
        workLocation: Map<String, Any>?,
        imageUrl: String?
    ) {
        // ⭐ [NEW] GeoPoint를 포함하는 Map을 생성하는 함수
        fun createFirestoreLocationMap(locationMap: Map<String, Any>?): Map<String, Any>? {
            if (locationMap == null || !locationMap.containsKey("latLng")) {
                return null
            }

            // LatLng 객체 추출 (Autocomplete/현재 위치 결과에서 넘어옴)
            val latLngAny = locationMap["latLng"]
            val latLng = if (latLngAny is LatLng) latLngAny else null

            // Firestore에 저장할 GeoPoint 객체 생성
            val geoPoint = if (latLng != null) {
                com.google.firebase.firestore.GeoPoint(latLng.latitude, latLng.longitude) // Full import to resolve potential conflict
            } else {
                return null // 유효한 LatLng이 없으면 저장하지 않음
            }

            return hashMapOf(
                "addressName" to (locationMap["address"] as? String ?: ""), // 주소 이름 (메인 표시용)
                "name" to (locationMap["name"] as? String ?: ""),           // POI 이름
                "placeId" to (locationMap["placeId"] as? String ?: ""),     // Place ID
                "latLng" to geoPoint // ⭐ 핵심 변경: GeoPoint 객체 저장
            )
        }

        val firestoreHomeLocation = createFirestoreLocationMap(homeLocation)
        val firestoreWorkLocation = createFirestoreLocationMap(workLocation)

        val userData = hashMapOf<String, Any>(
            "nickname" to nickname,
            "photoUrl" to (imageUrl ?: "")
        )

        // Map이 null이 아니면 추가
        if (firestoreHomeLocation != null) {
            userData["homeLocation"] = firestoreHomeLocation // homeLocation은 필수
        }
        if (firestoreWorkLocation != null) {
            userData["workLocation"] = firestoreWorkLocation // workLocation은 선택
        }


        firestore.collection("users").document(uid)
            // 💡 [중요] 기존 데이터를 유지하면서 업데이트: SetOptions.merge() 사용
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(findViewById(R.id.btn_save_profile), "프로필이 수정되었습니다 🎉", Snackbar.LENGTH_SHORT).show()

                findViewById<Button>(R.id.btn_save_profile).postDelayed({
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }, 600)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(findViewById(R.id.btn_save_profile), "수정 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
                Log.e("PROFILE", "Firestore 수정 실패", it)
            }
    }
}