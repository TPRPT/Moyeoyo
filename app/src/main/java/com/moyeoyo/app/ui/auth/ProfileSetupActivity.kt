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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R

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

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            imageUri = result.data?.data
            Glide.with(this).load(imageUri).circleCrop().into(imgProfile)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_setup)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()
        progressDialog = ProgressDialog(this).apply {
            setMessage("저장 중...")
            setCancelable(false)
        }

        imgProfile = findViewById(R.id.profile_image)
        btnChangePhoto = findViewById(R.id.btn_change_photo)
        inputNickname = findViewById(R.id.input_nickname)
        inputHome = findViewById(R.id.input_home)
        inputWork = findViewById(R.id.input_work)
        btnSave = findViewById(R.id.btn_save_profile)

        // 🔹 프로필 이미지 클릭 시 갤러리 열기
        val openGallery = {
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
            }
            galleryLauncher.launch(intent)
        }
        imgProfile.setOnClickListener { openGallery() }
        btnChangePhoto.setOnClickListener { openGallery() }

        // 🔹 저장 버튼
        btnSave.setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        val nickname = inputNickname.text.toString().trim()
        val home = inputHome.text.toString().trim()
        val work = inputWork.text.toString().trim()

        if (nickname.isEmpty()) {
            Snackbar.make(inputNickname, "닉네임을 입력해주세요", Snackbar.LENGTH_SHORT).show()
            return
        }

        progressDialog.show()
        val user = auth.currentUser ?: return

        if (imageUri != null) {
            val ref = storage.reference.child("profile_images/${user.uid}.jpg")
            ref.putFile(imageUri!!)
                .continueWithTask { ref.downloadUrl }
                .addOnSuccessListener { downloadUrl ->
                    saveUserToFirestore(user.uid, nickname, home, work, downloadUrl.toString())
                }
                .addOnFailureListener {
                    progressDialog.dismiss()
                    Snackbar.make(btnSave, "이미지 업로드 실패", Snackbar.LENGTH_LONG).show()
                }
        } else {
            saveUserToFirestore(user.uid, nickname, home, work, null)
        }
    }

    private fun saveUserToFirestore(uid: String, nickname: String, home: String, work: String, imageUrl: String?) {
        val userData = hashMapOf(
            "nickname" to nickname,
            "home" to home,
            "work" to work,
            "photoUrl" to imageUrl
        )

        firestore.collection("users").document(uid).set(userData)
            .addOnSuccessListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "프로필이 저장되었습니다", Snackbar.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .addOnFailureListener {
                progressDialog.dismiss()
                Snackbar.make(btnSave, "저장 실패: ${it.message}", Snackbar.LENGTH_LONG).show()
                Log.e("PROFILE", "저장 실패", it)
            }
    }
}
