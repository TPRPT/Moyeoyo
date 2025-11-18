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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
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
import java.io.File

class ProfileSetupFragment : Fragment() {

    private var _binding: FragmentProfileSetupBinding? = null
    private val binding get() = _binding!!

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    /** 현재 선택된 이미지 경로 */
    private var imageUri: Uri? = null

    /** 카메라로 찍을 사진이 저장될 URI */
    private var cameraImageUri: Uri? = null

    private lateinit var progressDialog: ProgressDialog

    /** -------------------------------
     *  갤러리 런처
     * ------------------------------- */
    private val galleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                imageUri = result.data?.data
                Glide.with(requireContext()).load(imageUri).circleCrop()
                    .into(binding.profileImage)
            }
        }

    /** -------------------------------
     *  카메라 런처
     * ------------------------------- */
    private val cameraLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) {
                imageUri = cameraImageUri
                Glide.with(requireContext()).load(cameraImageUri).circleCrop()
                    .into(binding.profileImage)
            }
        }

    /** -------------------------------
     *  카메라 사진 저장용 파일 생성
     * ------------------------------- */
    private fun createImageUri(): Uri {
        val context = requireContext()
        val image = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "profile_${System.currentTimeMillis()}.jpg"
        )
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            image
        )
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

        /** -------------------------------
         * Places API 초기화
         * ------------------------------- */
        if (!Places.isInitialized()) {
            try {
                val appInfo = requireActivity().packageManager.getApplicationInfo(
                    requireActivity().packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                val apiKey = appInfo.metaData.getString("com.google.android.geo.API_KEY")
                if (!apiKey.isNullOrEmpty()) {
                    Places.initialize(requireContext(), apiKey)
                }
            } catch (e: Exception) {
                Log.e("PLACE_INIT", "Failed to init Places API", e)
            }
        }

        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("프로필 저장 중...")
            setCancelable(false)
        }

        /** -------------------------------
         * 프로필 이미지 클릭 → 다이얼로그 표시
         * ------------------------------- */
        binding.profileImage.setOnClickListener { showImagePickerDialog() }
        binding.btnChangePhoto.setOnClickListener { showImagePickerDialog() }

        /** -------------------------------
         * 주소 입력 클릭 (자동완성)
         * ------------------------------- */
        binding.inputHome.apply {
            isFocusable = false
            setOnClickListener { startPlaceAutocomplete(true) }
        }
        binding.inputWork.apply {
            isFocusable = false
            setOnClickListener { startPlaceAutocomplete(false) }
        }

        binding.btnSaveProfile.setOnClickListener { saveProfile() }

        loadCurrentUserData()
    }

    /** -------------------------------
     *  이미지 선택 다이얼로그
     * ------------------------------- */
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

    /** -------------------------------
     *  카메라 권한 체크 후 열기
     * ------------------------------- */
    private fun openCameraWithPermission() {
        val permission = android.Manifest.permission.CAMERA

        when {
            requireContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> {
                // 이미 권한 있음 → 카메라 실행
                openCamera()
            }

            shouldShowRequestPermissionRationale(permission) -> {
                AlertDialog.Builder(requireContext())
                    .setTitle("카메라 권한 필요")
                    .setMessage("프로필 사진을 찍기 위해 카메라 권한이 필요합니다.")
                    .setPositiveButton("허용") { _, _ ->
                        requestPermissions(arrayOf(permission), 1001)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }

            else -> {
                requestPermissions(arrayOf(permission), 1001)
            }
        }
    }

    private fun openCamera() {
        val uri = createImageUri()
        cameraImageUri = uri
        cameraLauncher.launch(uri)
    }

    /** -------------------------------
     *  권한 요청 결과 처리
     * ------------------------------- */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Toast.makeText(requireContext(), "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** -------------------------------
     *  기존 유저 데이터 로드
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
                        Glide.with(requireContext())
                            .load(photoUrl)
                            .circleCrop()
                            .into(binding.profileImage)
                    }

                    val homeMap = doc.get("homeLocation") as? Map<*, *>
                    val workMap = doc.get("workLocation") as? Map<*, *>

                    (homeMap?.get("address") as? String)?.let {
                        binding.inputHome.setText(it)
                    }
                    (workMap?.get("address") as? String)?.let {
                        binding.inputWork.setText(it)
                    }
                }
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(binding.root, "프로필 로드 실패", Snackbar.LENGTH_LONG).show()
            }
    }

    /** -------------------------------
     *  주소 자동완성 (집/회사)
     * ------------------------------- */
    private val homeAddressLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, true)
        }

    private val workAddressLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handlePlaceResult(result, false)
        }

    private fun startPlaceAutocomplete(isHome: Boolean) {
        val fields = listOf(
            Place.Field.LAT_LNG,
            Place.Field.NAME,
            Place.Field.ADDRESS
        )

        val intent =
            Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
                .build(requireContext())

        if (isHome) homeAddressLauncher.launch(intent)
        else workAddressLauncher.launch(intent)
    }

    private fun handlePlaceResult(
        result: androidx.activity.result.ActivityResult,
        isHome: Boolean
    ) {
        if (result.resultCode == Activity.RESULT_OK) {
            val place = Autocomplete.getPlaceFromIntent(result.data!!)
            val address = place.address ?: place.name ?: ""

            if (isHome) binding.inputHome.setText(address)
            else binding.inputWork.setText(address)
        }
    }

    /** -------------------------------
     *  프로필 저장
     * ------------------------------- */
    private fun saveProfile() {
        val nickname = binding.inputNickname.text.toString().trim()

        if (nickname.isEmpty()) {
            Snackbar.make(binding.root, "닉네임을 입력해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }

        val user = auth.currentUser ?: return
        progressDialog.show()

        if (imageUri != null) {
            val ref = storage.reference.child("profile_images/${user.uid}.jpg")

            ref.putFile(imageUri!!)
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { url ->
                    saveUserToFirestore(nickname, url.toString())
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    Snackbar.make(binding.root, "이미지 업로드 실패", Snackbar.LENGTH_LONG).show()
                }
        } else {
            saveUserToFirestore(nickname, null)
        }
    }

    private fun saveUserToFirestore(nickname: String, imageUrl: String?) {
        val uid = auth.currentUser?.uid ?: return

        val userData = hashMapOf(
            "nickname" to nickname,
            "photoUrl" to (imageUrl ?: "")
        )

        firestore.collection("users").document(uid)
            .set(userData, SetOptions.merge())
            .addOnSuccessListener {
                progressDialog.dismiss()
                Toast.makeText(requireContext(), "프로필 저장 완료", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_profileSetupFragment_to_mainFragment)
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(binding.root, "저장 실패", Snackbar.LENGTH_LONG).show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
