package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
// 💡 모드 Import
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

        // 💡 [수정] Google Places SDK 초기화: Manifest에서 API 키를 직접 읽어와 사용
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
    }

    // =========================================================================
    // Places API 로직
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
                // 🌟 [수정] 모드를 FULLSCREEN에서 OVERLAY로 변경 🌟
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
            // 예외 발생 시 Logcat에 오류를 더 자세히 남깁니다.
        } catch (e: Exception) {
            Snackbar.make(btnSave, "주소 검색 시작 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e("PLACE_API", "Autocomplete intent launch failed. CHECK API KEY/MANIFEST!", e)
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
            // 결과 성공 처리 (기존과 동일)
            val place = Autocomplete.getPlaceFromIntent(result.data!!)

            val addressText = place.name ?: place.address ?: "선택된 장소"
            if (isHome) {
                inputHome.setText(addressText)
            } else {
                inputWork.setText(addressText)
            }

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
            Log.e("PLACE", "Autocomplete failed with status: ${status.statusMessage}. Status Code: ${status.statusCode}")
            Snackbar.make(btnSave, "주소 검색 오류: ${status.statusMessage}", Snackbar.LENGTH_LONG).show()
        }
    }


    // =========================================================================
    // 프로필 저장 로직 (기존과 동일)
    // =========================================================================

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
        fun createFirestoreLocationMap(locationMap: Map<String, Any>?): Map<String, Any> {
            if (locationMap == null || !locationMap.containsKey("latLng")) {
                return mapOf()
            }

            val latLng = locationMap["latLng"] as? LatLng

            val geoPoint = if (latLng != null) {
                GeoPoint(latLng.latitude, latLng.longitude)
            } else GeoPoint(0.0, 0.0)

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
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "프로필이 저장되었습니다 🎉", Snackbar.LENGTH_SHORT).show()

                btnSave.postDelayed({
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }, 600)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "저장 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
                Log.e("PROFILE", "Firestore 저장 실패", it)
            }
    }
}