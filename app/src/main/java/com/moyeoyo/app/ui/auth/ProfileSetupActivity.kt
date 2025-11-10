package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View // 💡 View Import 추가
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R

// Places SDK 및 Maps 관련 Imports
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.GooglePlayServicesNotAvailableException


class ProfileSetupActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var progressDialog: ProgressDialog

    private var imageUri: Uri? = null

    private lateinit var imgProfile: ImageView
    private lateinit var btnChangePhoto: ImageView
    private lateinit var inputNickname: EditText
    private lateinit var inputHome: EditText
    private lateinit var inputWork: EditText
    private lateinit var btnSave: Button

    // 위치 데이터를 저장할 Map 변수
    private var homeLocationData: Map<String, Any>? = null
    private var workLocationData: Map<String, Any>? = null

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

        // Firebase 초기화
        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        // Google Places SDK 초기화 (Manifest 키 로딩 로직 유지)
        if (!Places.isInitialized()) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")

                if (!apiKey.isNullOrEmpty()) {
                    Places.initialize(applicationContext, apiKey)
                } else {
                    Log.e("PLACE_INIT", "ERROR: API_KEY not found in Manifest metadata.")
                    Snackbar.make(findViewById(android.R.id.content), "API 키 설정 오류", Snackbar.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("PLACE_INIT", "Failed to retrieve API Key from Manifest", e)
                Snackbar.make(findViewById(android.R.id.content), "API 키 로딩 중 치명적 오류 발생", Snackbar.LENGTH_LONG).show()
            }
        }

        // 프로그레스 다이얼로그
        progressDialog = ProgressDialog(this).apply {
            setMessage("프로필 저장 중...")
            setCancelable(false)
        }

        // View 연결 및 리스너 설정
        imgProfile = findViewById<ImageView>(R.id.profile_image)
        btnChangePhoto = findViewById<ImageView>(R.id.btn_change_photo)
        inputNickname = findViewById<EditText>(R.id.input_nickname)
        btnSave = findViewById<Button>(R.id.btn_save_profile)

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

        // 갤러리 열기 함수
        val openGallery = {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            galleryLauncher.launch(intent)
        }

        imgProfile.setOnClickListener { openGallery() }
        btnChangePhoto.setOnClickListener { openGallery() }

        // 저장 버튼
        btnSave.setOnClickListener { saveProfile() }

        // 💡 [추가] 기존 유저 데이터 로드 (프로필 수정 모드)
        loadCurrentUserData()
    }

    /**
     * 💡 [새 함수] 현재 로그인된 유저의 Firestore 데이터를 불러와 UI에 채웁니다.
     * (프로필 수정 모드 진입 시)
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

                    // 주소 데이터 로드
                    val homeMap = doc.get("homeLocation") as? Map<*, *>
                    val workMap = doc.get("workLocation") as? Map<*, *>

                    val homeAddress = homeMap?.get("address") as? String
                    val workAddress = workMap?.get("address") as? String

                    // UI 채우기
                    if (!nickname.isNullOrEmpty()) {
                        inputNickname.setText(nickname)
                    }
                    if (!homeAddress.isNullOrEmpty()) {
                        inputHome.setText(homeAddress)
                        // 💡 주소 맵 데이터도 다시 구성하여 저장할 준비를 합니다. (위경도는 0.0으로 초기화)
                        homeLocationData = mapOf("name" to (homeMap?.get("name") ?: "집"),
                            "address" to homeAddress,
                            "latLng" to (homeMap?.get("latLng") ?: GeoPoint(0.0, 0.0)))
                    }
                    if (!workAddress.isNullOrEmpty()) {
                        inputWork.setText(workAddress)
                        workLocationData = mapOf("name" to (workMap?.get("name") ?: "직장"),
                            "address" to workAddress,
                            "latLng" to (workMap?.get("latLng") ?: GeoPoint(0.0, 0.0)))
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


    // =========================================================================
    // Places API 로직 (유지)
    // =========================================================================

    /**
     * Place Autocomplete Intent를 실행합니다.
     */
    private fun startPlaceAutocomplete(isHome: Boolean) {
        try {
            // 원하는 필드 지정 (위경도, 장소 이름, 주소)
            val fields = listOf(
                Place.Field.LAT_LNG,
                Place.Field.NAME,
                Place.Field.ADDRESS
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
            Snackbar.make(btnSave, "Google Play 서비스 오류 (수리 필요): ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Repairable Exception", e)
        } catch (e: GooglePlayServicesNotAvailableException) {
            Snackbar.make(btnSave, "Google Play 서비스 사용 불가: ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Not Available Exception", e)
        } catch (e: Exception) {
            Snackbar.make(btnSave, "주소 검색 시작 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
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
            val targetInput = if (isHome) inputHome else inputWork // 🎯 단일 변수로 텍스트 설정
            targetInput.setText(addressText)

            val latLng = place.latLng

            val locationMap = hashMapOf<String, Any>(
                "name" to (place.name ?: (if (isHome) "집" else "직장")),
                "address" to (place.address ?: ""),
                "latLng" to (latLng as Any)
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


    // =========================================================================
    // 프로필 저장 로직 (업데이트 로직은 기존과 동일)
    // =========================================================================

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

    private fun saveUserToFirestore(
        uid: String,
        nickname: String,
        homeLocation: Map<String, Any>?,
        workLocation: Map<String, Any>?,
        imageUrl: String?
    ) {
        // ... (createFirestoreLocationMap 함수 유지)
        fun createFirestoreLocationMap(locationMap: Map<String, Any>?): Map<String, Any> {
            if (locationMap == null || !locationMap.containsKey("latLng")) {
                return mapOf()
            }

            // LatLng 객체가 아닌 GeoPoint 객체인 경우를 대비하여 처리
            val latLngAny = locationMap["latLng"]
            val latLng = if (latLngAny is LatLng) latLngAny else null

            // 기존 Firestore에서 GeoPoint로 로드된 경우를 대비하여 GeoPoint 타입도 확인 (선택 사항)
            val geoPointLoaded = if (latLngAny is GeoPoint) latLngAny else null

            val geoPoint = if (latLng != null) {
                GeoPoint(latLng.latitude, latLng.longitude)
            } else if (geoPointLoaded != null) {
                geoPointLoaded
            } else {
                GeoPoint(0.0, 0.0)
            }

            return hashMapOf(
                "name" to (locationMap["name"] as String),
                "address" to (locationMap["address"] as String),
                "latLng" to geoPoint
            )
        }

        val firestoreHomeLocation = createFirestoreLocationMap(homeLocation)
        val firestoreWorkLocation = createFirestoreLocationMap(workLocation)

        val userData = hashMapOf<String, Any>(
            "nickname" to nickname,
            "photoUrl" to (imageUrl ?: ""),
            "homeLocation" to firestoreHomeLocation,
            "workLocation" to firestoreWorkLocation
        )

        firestore.collection("users").document(uid)
            // 💡 [중요] 기존 데이터를 유지하면서 업데이트: SetOptions.merge() 사용
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "프로필이 수정되었습니다 🎉", Snackbar.LENGTH_SHORT).show()

                btnSave.postDelayed({
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }, 600)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "수정 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
                Log.e("PROFILE", "Firestore 수정 실패", it)
            }
    }
}