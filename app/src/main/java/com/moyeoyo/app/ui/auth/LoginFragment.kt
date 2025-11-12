package com.moyeoyo.app.ui.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.R

class LoginFragment : Fragment(R.layout.fragment_login) {

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    // 구글 로그인 요청 코드
    private val RC_SIGN_IN = 1001

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // 🔹 Google 로그인 옵션 설정
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Firebase 콘솔의 웹 클라이언트 ID
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(requireContext(), gso)

        // 🔹 Google 로그인 버튼 클릭
        val googleLoginButton = view.findViewById<LinearLayout>(R.id.btn_google_login)
        googleLoginButton.setOnClickListener {
            startGoogleLogin()
        }
    }

    /**
     * 🔹 구글 로그인 시작
     */
    private fun startGoogleLogin() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    /**
     * 🔹 로그인 결과 처리
     */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account)
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), "Google 로그인 실패: ${e.statusCode}", Toast.LENGTH_SHORT).show()
                Log.e("LOGIN", "Google sign in failed", e)
            }
        }
    }

    /**
     * 🔹 Firebase에 Google 계정 인증 연결
     */
    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount?) {
        if (account == null) return

        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener {
                val user = auth.currentUser
                if (user != null) {
                    checkUserProfile(user.uid)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Firebase 로그인 실패: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e("LOGIN", "Firebase auth failed", e)
            }
    }

    /**
     * 🔹 Firestore에 사용자 프로필 데이터 존재 여부 확인
     */
    private fun checkUserProfile(uid: String) {
        firestore.collection("users").document(uid).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    // ✅ 프로필 존재 → 메인화면으로 이동
                    findNavController().navigate(R.id.action_loginFragment_to_mainFragment)
                } else {
                    // ❌ 프로필 없음 → 프로필 설정 화면으로 이동
                    findNavController().navigate(R.id.action_loginFragment_to_profileSetupFragment)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "사용자 정보 확인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
