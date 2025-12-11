package com.moyeoyo.app.ui.groups

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import com.google.firebase.storage.FirebaseStorage
import androidx.lifecycle.lifecycleScope
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.groups.adapter.GroupPhotoAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class PhotoTabFragment : Fragment() {

    private val groupId: String by lazy {
        arguments?.getString("groupId") ?: ""
    }

    @Inject lateinit var groupRepository: GroupRepository

    private lateinit var recyclerPhotos: RecyclerView
    private lateinit var btnAddPhoto: View
    private lateinit var photoAdapter: GroupPhotoAdapter
    private lateinit var progressDialog: ProgressDialog

    private val auth = FirebaseAuth.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private var imageUri: Uri? = null
    private var cameraImageUri: Uri? = null
    
    // ⭐ 오버레이 상태 추적
    private var currentOverlayView: View? = null
    private var onBackPressedCallback: androidx.activity.OnBackPressedCallback? = null

    // 갤러리 런처
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            imageUri = result.data?.data
            imageUri?.let { uploadPhoto(it) }
        }
    }

    // 카메라 런처
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraImageUri != null) {
            imageUri = cameraImageUri
            uploadPhoto(cameraImageUri!!)
        }
    }

    // 카메라 권한 런처
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openCamera()
        } else {
            Toast.makeText(requireContext(), "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.view_photo_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerPhotos = view.findViewById(R.id.recyclerPhotos)
        btnAddPhoto = view.findViewById(R.id.btnAddPhoto)

        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("사진 업로드 중...")
            setCancelable(false)
        }

        // 그리드 레이아웃 설정 (3열)
        recyclerPhotos.layoutManager = GridLayoutManager(requireContext(), 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (position == 0) 3 else 1 // 첫 번째 항목(업로드 버튼)은 전체 너비
                }
            }
        }

        val currentUser = auth.currentUser
        photoAdapter = GroupPhotoAdapter(
            onPhotoClick = { photo ->
                showPhotoViewer(photo)
            },
            onAddPhotoClick = {
                showImagePickerDialog()
            },
            onDeletePhoto = { photo ->
                deletePhoto(photo)
            },
            currentUserId = currentUser?.uid ?: ""
        )
        recyclerPhotos.adapter = photoAdapter

        btnAddPhoto.setOnClickListener {
            showImagePickerDialog()
        }

        loadPhotos()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // ⭐ 오버레이 정리
        currentOverlayView?.let {
            val rootView = requireActivity().window.decorView.rootView as? android.view.ViewGroup
            rootView?.removeView(it)
            currentOverlayView = null
        }
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
    }

    private fun showImagePickerDialog() {
        val options = arrayOf("사진 찍기", "갤러리에서 선택", "취소")
        AlertDialog.Builder(requireContext())
            .setTitle("사진 추가")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCameraWithPermission()
                    1 -> openGallery()
                    2 -> { /* 취소 */ }
                }
            }
            .show()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
        }
        galleryLauncher.launch(intent)
    }

    private fun openCameraWithPermission() {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            if (shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(requireContext())
                    .setTitle("카메라 권한 필요")
                    .setMessage("사진을 찍기 위해 카메라 권한이 필요합니다.")
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
            "group_photo_${System.currentTimeMillis()}.jpg"
        )
        return FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.provider",
            image
        )
    }

    private fun uploadPhoto(uri: Uri) {
        val currentUser = auth.currentUser ?: run {
            Toast.makeText(requireContext(), "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        progressDialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val timestamp = System.currentTimeMillis()
                val photoId = "${currentUser.uid}_$timestamp"
                val storageRef = storage.reference.child("group_images/$groupId/$photoId.jpg")

                // 이미지 업로드
                val uploadTask = storageRef.putFile(uri)
                uploadTask.await()

                // 다운로드 URL 가져오기
                val downloadUrl = storageRef.downloadUrl.await()

                // Firestore에 메타데이터 저장
                val photoData = hashMapOf(
                    "photoId" to photoId,
                    "url" to downloadUrl.toString(),
                    "uploadedBy" to currentUser.uid,
                    "uploadedAt" to Timestamp.now(),
                    "groupId" to groupId
                )

                firestore.collection("groups").document(groupId)
                    .collection("photos")
                    .document(photoId)
                    .set(photoData)
                    .await()

                progressDialog.dismiss()
                Toast.makeText(requireContext(), "사진이 업로드되었습니다.", Toast.LENGTH_SHORT).show()
                loadPhotos()
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(requireContext(), "업로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadPhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val photosSnapshot = firestore.collection("groups").document(groupId)
                    .collection("photos")
                    .orderBy("uploadedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .get()
                    .await()

                val photos = photosSnapshot.documents.map { doc ->
                    GroupPhoto(
                        photoId = doc.getString("photoId") ?: doc.id,
                        url = doc.getString("url") ?: "",
                        uploadedBy = doc.getString("uploadedBy") ?: "",
                        uploadedAt = doc.getTimestamp("uploadedAt")?.toDate() ?: Date()
                    )
                }

                photoAdapter.submitList(photos)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "사진 불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPhotoViewer(photo: GroupPhoto) {
        val rootView = requireActivity().window.decorView.rootView as? android.view.ViewGroup
            ?: return
        
        // ⭐ 기존 오버레이가 있으면 제거
        currentOverlayView?.let {
            rootView.removeView(it)
            currentOverlayView = null
        }
        
        val overlayView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_photo_viewer, rootView, false)
        val imageView = overlayView.findViewById<android.widget.ImageView>(R.id.photoViewerImage)
        val btnDownload = overlayView.findViewById<android.widget.ImageButton>(R.id.btnDownloadPhoto)
        val btnDelete = overlayView.findViewById<android.widget.ImageButton>(R.id.btnDeletePhoto)
        
        // ⭐ 오버레이 닫기 함수
        val closeOverlay = {
            rootView.removeView(overlayView)
            currentOverlayView = null
            onBackPressedCallback?.remove()
            onBackPressedCallback = null
        }
        
        // 이미지 로드 - 원본 비율 유지하며 전체 화면에 맞춤
        Glide.with(requireContext())
            .load(photo.url)
            .fitCenter()
            .into(imageView)
        
        // 삭제 버튼은 본인이 업로드한 사진만 표시
        val currentUser = auth.currentUser
        if (currentUser?.uid != photo.uploadedBy) {
            btnDelete.visibility = View.GONE
        } else {
            btnDelete.visibility = View.VISIBLE
            btnDelete.setOnClickListener {
                closeOverlay()
                deletePhoto(photo)
            }
        }
        
        // 다운로드 버튼
        btnDownload.setOnClickListener {
            downloadPhoto(photo)
        }
        
        // 오버레이 클릭 시 닫기
        overlayView.setOnClickListener { 
            closeOverlay()
        }
        
        // 이미지 클릭은 오버레이 클릭으로 전파되지 않도록
        imageView.setOnClickListener { 
            closeOverlay()
        }
        
        // ⭐ 뒤로가기 처리: 오버레이가 표시되어 있으면 오버레이만 닫기
        onBackPressedCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                closeOverlay()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback!!)
        
        // 오버레이를 root view에 추가
        overlayView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootView.addView(overlayView)
        currentOverlayView = overlayView
    }

    private fun downloadPhoto(photo: GroupPhoto) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 이미지를 바이트 배열로 다운로드
                val bytes = storage.reference.child("group_images/$groupId/${photo.photoId}.jpg")
                    .getBytes(Long.MAX_VALUE)
                    .await()

                // MediaStore를 사용하여 갤러리에 저장
                val imageUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Moyeoyo_${photo.photoId}.jpg")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Moyeoyo")
                    }
                    requireContext().contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                } else {
                    val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val moyeoyoDir = File(picturesDir, "Moyeoyo")
                    moyeoyoDir.mkdirs()
                    val localFile = File(moyeoyoDir, "Moyeoyo_${photo.photoId}.jpg")
                    localFile.outputStream().use { it.write(bytes) }
                    
                    // 미디어 스캔
                    val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                        data = Uri.fromFile(localFile)
                    }
                    requireContext().sendBroadcast(mediaScanIntent)
                    Uri.fromFile(localFile)
                }

                // Android 10 이상에서 파일 쓰기
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && imageUri != null) {
                    requireContext().contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                        outputStream.write(bytes)
                    }
                }

                Toast.makeText(requireContext(), "다운로드 완료\n갤러리에 저장되었습니다", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "다운로드 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deletePhoto(photo: GroupPhoto) {
        val currentUser = auth.currentUser
        if (currentUser?.uid != photo.uploadedBy) {
            Toast.makeText(requireContext(), "본인이 업로드한 사진만 삭제할 수 있습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("사진 삭제")
            .setMessage("이 사진을 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        // Storage에서 삭제
                        val storageRef = storage.reference.child("group_images/$groupId/${photo.photoId}.jpg")
                        storageRef.delete().await()

                        // Firestore에서 삭제
                        firestore.collection("groups").document(groupId)
                            .collection("photos")
                            .document(photo.photoId)
                            .delete()
                            .await()

                        Toast.makeText(requireContext(), "사진이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        loadPhotos()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "삭제 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    data class GroupPhoto(
        val photoId: String,
        val url: String,
        val uploadedBy: String,
        val uploadedAt: Date
    )
}

