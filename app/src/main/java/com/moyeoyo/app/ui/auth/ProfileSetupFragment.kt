package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.R
import com.moyeoyo.app.databinding.FragmentProfileSetupBinding
import kotlinx.coroutines.launch

/**
 * ✅ ProfileSetupFragment
 * 로그인 후 첫 프로필 설정 / 수정 화면
 */
class ProfileSetupFragment : Fragment() {

    private var _binding: FragmentProfileSetupBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private var imageUri: Uri? = null
    private var homeLocationData: Map<String, Any>? = null
    private var workLocationData: Map<String, Any>? = null

    private lateinit var progressDialog: ProgressDialog

    /** -------------------------------
     * ✅ Activity Result Launchers
     * ------------------------------- */
    private val galleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                imageUri = result.data?.data
                Glide.with(requireContext()).load(imageUri).circleCrop()
                    .into(binding.profileImage)
            }
        }

    private val homeAddressLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, isHome = true)
        }

    private val workAddressLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, isHome = false)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔹 Places SDK 초기화
        if (!Places.isInitialized()) {
            try {
                val appInfo =
                    requireActivity().packageManager.getApplicationInfo(
                        requireActivity().packageName,
                        android.content.pm.PackageManager.GET_META_DATA
                    )
                val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")
                if (!apiKey.isNullOrEmpty()) {
                    Places.initialize(requireContext(), apiKey)
                } else {
                    Log.e("PLACE_INIT", "API_KEY not found in Manifest metadata.")
                }
            } catch (e: Exception) {
                Log.e("PLACE_INIT", "Failed to retrieve API Key from Manifest", e)
            }
        }

        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("프로필 저장 중...")
            setCancelable(false)
        }

        // 🔹 이미지 변경 버튼
        val openGallery = {
            val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
            galleryLauncher.launch(intent)
        }
        binding.profileImage.setOnClickListener { openGallery() }
        binding.btnChangePhoto.setOnClickListener { openGallery() }

        // 🔹 주소 검색 입력 비활성화 후 클릭 이벤트 연결
        binding.inputHome.apply {
            setOnClickListener { startPlaceAutocomplete(true) }
            isFocusable = false
            keyListener = null
        }
        binding.inputWork.apply {
            setOnClickListener { startPlaceAutocomplete(false) }
            isFocusable = false
            keyListener = null
        }

        // 🔹 저장 버튼
        binding.btnSaveProfile.setOnClickListener { saveProfile() }

        // 🔹 기존 유저 정보 로드 (수정 모드일 경우)
        loadCurrentUserData()
    }

    /** -------------------------------
     * ✅ 기존 유저 데이터 불러오기
     * ------------------------------- */
    private fun loadCurrentUserData() {
        val uid = auth.currentUser?.uid ?: return
        progressDialog.setMessage("프로필 불러오는 중...")
        progressDialog.show()

        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()
                if (doc.exists()) {
                    binding.inputNickname.setText(doc.getString("nickname") ?: "")
                    val photoUrl = doc.getString("photoUrl")
                    if (!photoUrl.isNullOrEmpty()) {
                        Glide.with(requireContext()).load(photoUrl).circleCrop()
                            .into(binding.profileImage)
                    }

                    val homeMap = doc.get("homeLocation") as? Map<*, *>
                    val workMap = doc.get("workLocation") as? Map<*, *>
                    val homeAddress = homeMap?.get("address") as? String
                    val workAddress = workMap?.get("address") as? String

                    if (!homeAddress.isNullOrEmpty()) {
                        binding.inputHome.setText(homeAddress)
                        homeLocationData = homeMap as? Map<String, Any>
                    }
                    if (!workAddress.isNullOrEmpty()) {
                        binding.inputWork.setText(workAddress)
                        workLocationData = workMap as? Map<String, Any>
                    }
                }
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(binding.root, "프로필 로드 실패: ${it.message}", Snackbar.LENGTH_LONG)
                    .show()
            }
    }

    /** -------------------------------
     * ✅ 장소 자동완성
     * ------------------------------- */
    private fun startPlaceAutocomplete(isHome: Boolean) {
        try {
            val fields = listOf(
                Place.Field.LAT_LNG,
                Place.Field.NAME,
                Place.Field.ADDRESS
            )
            val intent = Autocomplete.IntentBuilder(
                AutocompleteActivityMode.OVERLAY,
                fields
            ).build(requireContext())

            if (isHome) homeAddressLauncher.launch(intent)
            else workAddressLauncher.launch(intent)
        } catch (e: GooglePlayServicesRepairableException) {
            Snackbar.make(binding.root, "Google Play 서비스 오류: ${e.message}", Snackbar.LENGTH_LONG).show()
        } catch (e: GooglePlayServicesNotAvailableException) {
            Snackbar.make(binding.root, "Google Play 서비스 사용 불가: ${e.message}", Snackbar.LENGTH_LONG).show()
        } catch (e: Exception) {
            Snackbar.make(binding.root, "주소 검색 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun handlePlaceResult(result: androidx.activity.result.ActivityResult, isHome: Boolean) {
        if (result.resultCode == Activity.RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            val latLng = place.latLng
            val address = place.address ?: place.name ?: "선택된 장소"

            val map = hashMapOf<String, Any>(
                "name" to (place.name ?: if (isHome) "집" else "직장"),
                "address" to (place.address ?: ""),
                "latLng" to (latLng as Any)
            )

            if (isHome) {
                homeLocationData = map
                binding.inputHome.setText(address)
            } else {
                workLocationData = map
                binding.inputWork.setText(address)
            }
        }
    }

    /** -------------------------------
     * ✅ 프로필 저장
     * ------------------------------- */
    private fun saveProfile() {
        val nickname = binding.inputNickname.text.toString().trim()
        if (nickname.isEmpty()) {
            Snackbar.make(binding.root, "닉네임을 입력해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (homeLocationData == null) {
            Snackbar.make(binding.root, "집 주소를 선택해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser ?: return
        progressDialog.show()

        if (imageUri != null) {
            val ref = storage.reference.child("profile_images/${user.uid}.jpg")
            ref.putFile(imageUri!!)
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { url ->
                    saveUserToFirestore(user.uid, nickname, homeLocationData, workLocationData, url.toString())
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    Snackbar.make(binding.root, "이미지 업로드 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
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
        fun createFirestoreLocationMap(location: Map<String, Any>?): Map<String, Any> {
            val latLngAny = location?.get("latLng")
            val geoPoint = when (latLngAny) {
                is LatLng -> GeoPoint(latLngAny.latitude, latLngAny.longitude)
                is GeoPoint -> latLngAny
                else -> GeoPoint(0.0, 0.0)
            }

            return hashMapOf(
                "name" to (location?.get("name") ?: ""),
                "address" to (location?.get("address") ?: ""),
                "latLng" to geoPoint
            )
        }

        val userData = hashMapOf<String, Any>(
            "nickname" to nickname,
            "photoUrl" to (imageUrl ?: ""),
            "homeLocation" to createFirestoreLocationMap(homeLocation),
            "workLocation" to createFirestoreLocationMap(workLocation)
        )

        firestore.collection("users").document(uid)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Toast.makeText(requireContext(), "프로필 저장 완료 🎉", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_profileSetupFragment_to_mainFragment)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(binding.root, "저장 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
