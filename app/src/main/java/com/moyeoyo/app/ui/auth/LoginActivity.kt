package com.moyeoyo.app.ui.auth

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.MainActivity
import com.moyeoyo.app.R

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var progressDialog: ProgressDialog
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        rootView = findViewById(android.R.id.content)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        progressDialog = ProgressDialog(this).apply {
            setMessage("로그인 중...")
            setCancelable(false)
        }

        // 이미 로그인된 유저가 있다면 바로 메인으로
        auth.currentUser?.let {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Google 로그인 런처
        val googleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) handleSignIn(result.data)
            else Snackbar.make(rootView, "로그인 취소됨", Snackbar.LENGTH_SHORT).show()
        }

        // Google 로그인 버튼 클릭
        findViewById<LinearLayout>(R.id.btn_google_login).setOnClickListener {
            progressDialog.show()
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(this, gso)
            googleLauncher.launch(client.signInIntent)
        }
    }

    private fun handleSignIn(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).result
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)

            auth.signInWithCredential(credential).addOnCompleteListener { task ->
                progressDialog.dismiss()
                if (task.isSuccessful) {
                    val user = auth.currentUser ?: return@addOnCompleteListener
                    firestore.collection("users").document(user.uid).get()
                        .addOnSuccessListener { doc ->
                            if (doc.exists()) {
                                startActivity(Intent(this, MainActivity::class.java))
                            } else {
                                startActivity(Intent(this, ProfileSetupActivity::class.java))
                            }
                            finish()
                        }
                } else {
                    Snackbar.make(rootView, "Firebase 인증 실패", Snackbar.LENGTH_LONG).show()
                    Log.e("LOGIN", "Auth 실패: ${task.exception?.message}")
                }
            }

        } catch (e: Exception) {
            progressDialog.dismiss()
            Snackbar.make(rootView, "로그인 오류: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }
}
