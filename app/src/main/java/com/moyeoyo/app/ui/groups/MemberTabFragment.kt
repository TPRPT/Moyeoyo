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
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.ui.groups.adapter.GroupPhotoAdapter
import com.moyeoyo.app.ui.groups.adapter.MemberListAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MemberTabFragment : Fragment() {

    private val groupId: String by lazy {
        arguments?.getString("groupId") ?: ""
    }

    @Inject lateinit var groupRepository: GroupRepository
    @Inject lateinit var friendRepository: FriendRepository

    private lateinit var recyclerMemberList: RecyclerView
    private lateinit var btnInviteMember: View
    private lateinit var recyclerPhotos: RecyclerView
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
        return inflater.inflate(R.layout.view_member_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerMemberList = view.findViewById(R.id.recyclerMemberList)
        recyclerMemberList.layoutManager = LinearLayoutManager(requireContext())

        btnInviteMember = view.findViewById(R.id.btnInviteMember)
        btnInviteMember.setOnClickListener {
            navigateToSelectFriends()
        }
        
        // ⭐ 이전 리스너 제거 (중복 방지)
        requireActivity().supportFragmentManager.clearFragmentResultListener(SelectFriendsFragment.RESULT_KEY)
        
        // ⭐ 친구 선택 결과 리스너를 onViewCreated에서 미리 등록 (항상 활성화)
        // ⭐ 멤버 추가용 Result Key만 사용
        requireActivity().supportFragmentManager.setFragmentResultListener(SelectFriendsFragment.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            android.util.Log.d("MemberTabFragment", "친구 선택 결과 수신 (멤버 추가용)")
            
            val selected = bundle.getStringArrayList(SelectFriendsFragment.EXTRA_SELECTED_UIDS)
            val selectedUids = selected?.toList() ?: emptyList()
            
            android.util.Log.d("MemberTabFragment", "멤버 추가 진행: groupId=$groupId, friends=${selectedUids.size}명")
            if (selectedUids.isNotEmpty()) {
                addMembersToGroup(selectedUids)
            } else {
                android.util.Log.d("MemberTabFragment", "선택된 친구가 없습니다.")
            }
        }

        // 사진 관련 초기화
        recyclerPhotos = view.findViewById(R.id.recyclerPhotos)

        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("사진 업로드 중...")
            setCancelable(false)
        }

        // 그리드 레이아웃 설정 (3열)
        recyclerPhotos.layoutManager = GridLayoutManager(requireContext(), 3)

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

        loadMemberList()
        loadPhotos()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // ⭐ Fragment Result Listener 제거 (멤버 추가용 Result Key만 사용)
        requireActivity().supportFragmentManager.clearFragmentResultListener(SelectFriendsFragment.RESULT_KEY)
        // ⭐ 오버레이 정리
        currentOverlayView?.let {
            val rootView = requireActivity().window.decorView.rootView as? android.view.ViewGroup
            rootView?.removeView(it)
            currentOverlayView = null
        }
        onBackPressedCallback?.remove()
        onBackPressedCallback = null
    }

    private fun loadMemberList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            if (group != null) {
                val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
                val isHost = group.hostUid == currentUid
                
                // 투표 시작 여부 확인 (status가 GROUP_CREATED가 아니면 투표 시작됨)
                val isVotingStarted = group.status != null && group.status != "GROUP_CREATED"

                val nicknames = group.memberUids.map { uid ->
                    async { uid to (friendRepository.getUserNickname(uid) ?: uid.take(8)) }
                }.awaitAll()

                recyclerMemberList.adapter = MemberListAdapter(
                    nicknames,
                    group.hostUid,
                    currentUid,
                    isHost,
                    isVotingStarted,
                    onKick = { uid, name ->
                        showKickConfirmationDialog(uid, name)
                    },
                    onLeave = {
                        showLeaveGroupConfirmationDialog()
                    }
                )
                
                // 초대 버튼 상태 업데이트: 방장 여부와 관계없이 모든 멤버가 사용 가능
                // 단, 투표가 시작되면 비활성화
                if (isVotingStarted) {
                    btnInviteMember.alpha = 0.5f
                    btnInviteMember.isEnabled = false
                } else {
                    // 투표가 시작되지 않았으면 모든 멤버가 멤버 추가 가능
                    btnInviteMember.alpha = 1.0f
                    btnInviteMember.isEnabled = true
                }
            }
        }
    }

    private fun showKickConfirmationDialog(uid: String, nickname: String) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("강퇴")
            .setMessage("${nickname} 님을 강퇴할까요?")
            .setPositiveButton("강퇴") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    if (groupRepository.removeMember(groupId, uid)) {
                        Toast.makeText(requireContext(), "강퇴 완료", Toast.LENGTH_SHORT).show()
                        loadMemberList()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showLeaveGroupConfirmationDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val groupName = group?.groupName ?: "그룹"
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("그룹 나가기")
                .setMessage("'$groupName' 그룹을 나가시겠습니까?")
                .setPositiveButton("나가기") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (groupRepository.leaveGroup(groupId)) {
                            Toast.makeText(requireContext(), "그룹을 나왔습니다.", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        } else {
                            Toast.makeText(requireContext(), "그룹 나가기에 실패했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun navigateToSelectFriends() {
        // ⭐ 리스너는 이미 onViewCreated에서 등록되어 있음
        // ⭐ groupId를 전달하여 멤버 추가용임을 명시
        android.util.Log.d("MemberTabFragment", "친구 선택 화면으로 이동: groupId=$groupId")
        findNavController().navigate(
            R.id.selectFriendsFragment,
            Bundle().apply {
                putString("groupId", groupId)
            }
        )
    }
    
    private fun addMembersToGroup(friendUids: List<String>) {
        // ⭐ 로딩 다이얼로그 표시
        val loadingDialog = ProgressDialog(requireContext()).apply {
            setMessage("멤버 추가 중...")
            setCancelable(false)
            show()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                android.util.Log.d("MemberTabFragment", "멤버 추가 시작: groupId=$groupId, friends=${friendUids.size}명")
                
                val success = groupRepository.addMembersToGroup(groupId, friendUids)
                
                loadingDialog.dismiss()
                
                if (success) {
                    android.util.Log.d("MemberTabFragment", "멤버 추가 성공")
                    Toast.makeText(requireContext(), "멤버가 추가되었습니다.", Toast.LENGTH_SHORT).show()
                    
                    // ⭐ 약간의 지연 후 멤버 목록 새로고침 (Firestore 업데이트 반영 시간 확보)
                    kotlinx.coroutines.delay(500)
                    loadMemberList()
                } else {
                    android.util.Log.e("MemberTabFragment", "멤버 추가 실패: success=false")
                    Toast.makeText(requireContext(), "멤버 추가에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                loadingDialog.dismiss()
                android.util.Log.e("MemberTabFragment", "멤버 추가 중 예외 발생: ${e.message}", e)
                Toast.makeText(requireContext(), "멤버 추가 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ===================== 사진 관련 함수 =====================

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

    private fun showPhotoViewer(photo: PhotoTabFragment.GroupPhoto) {
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

    private fun downloadPhoto(photo: PhotoTabFragment.GroupPhoto) {
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

    private fun loadPhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val photosSnapshot = firestore.collection("groups").document(groupId)
                    .collection("photos")
                    .orderBy("uploadedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .get()
                    .await()

                val photos = photosSnapshot.documents.map { doc ->
                    PhotoTabFragment.GroupPhoto(
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



    private fun deletePhoto(photo: PhotoTabFragment.GroupPhoto) {
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
}

