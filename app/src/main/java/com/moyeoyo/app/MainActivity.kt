package com.moyeoyo.app

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.ui.auth.LoginActivity
import com.moyeoyo.app.R

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog

    private lateinit var profileImage: ImageView
    private lateinit var textNickname: TextView
    private lateinit var textEmail: TextView
    private lateinit var btnLogout: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        profileImage = findViewById(R.id.profile_image)
        textNickname = findViewById(R.id.text_nickname)
        textEmail = findViewById(R.id.text_email)
        btnLogout = findViewById(R.id.btn_logout)

        progressDialog = ProgressDialog(this).apply {
            setMessage("정보 불러오는 중...")
            setCancelable(false)
            show()
        }

        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // 이메일 표시
        textEmail.text = user.email ?: "이메일 없음"

        // Firestore에서 유저 정보 불러오기
        firestore.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                progressDialog.dismiss()
                if (doc.exists()) {
                    val nickname = doc.getString("nickname") ?: "닉네임 없음"
                    val photoUrl = doc.getString("photoUrl")

                    textNickname.text = nickname

                    if (!photoUrl.isNullOrEmpty()) {
                        Glide.with(this)
                            .load(photoUrl)
                            .placeholder(R.drawable.ic_user_placeholder)
                            .circleCrop()
                            .into(profileImage)
                    } else {
                        profileImage.setImageResource(R.drawable.ic_user_placeholder)
                    }
                } else {
                    Snackbar.make(textNickname, "사용자 정보를 찾을 수 없습니다.", Snackbar.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                progressDialog.dismiss()
                Snackbar.make(textNickname, "불러오기 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
                Log.e("MAIN", "Firestore error", e)
            }

        // 로그아웃
        btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }


        setSupportActionBar(findViewById(R.id.toolbar))

    }
}
