package com.moyeoyo.app.ui.auth

import android.app.ProgressDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.moyeoyo.app.R
import com.moyeoyo.app.data.repository.AuthRepository
import kotlinx.coroutines.launch

class LoginFragment : Fragment() {

    private lateinit var auth: FirebaseAuth
    private lateinit var progressDialog: ProgressDialog
    private lateinit var authRepository: AuthRepository

    // Google Sign-In 런처
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                // Google 계정 인증에 성공하면, Firebase 인증을 처리합니다.
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)!!
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: com.google.android.gms.common.api.ApiException) {
                // Google Sign In 실패
                progressDialog.dismiss()
                Log.e("LOGIN", "Google Sign In failed", e)
                Snackbar.make(requireView(), "🚨 Google 로그인 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        } else {
            // 사용자가 Google Sign-In 취소
            progressDialog.dismiss()
            Log.d("LOGIN", "Google Sign In canceled")
            Snackbar.make(requireView(), "Google 로그인이 취소되었습니다.", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.activity_login, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔹 Firebase 및 Repository 초기화
        auth = FirebaseAuth.getInstance()
        authRepository = AuthRepository(requireContext())

        // 🔹 로딩 다이얼로그
        progressDialog = ProgressDialog(requireContext()).apply {
            setMessage("로그인 중...")
            setCancelable(false)
        }

        // 🔹 자동 로그인 확인
        if (authRepository.isLoggedIn()) {
            // MainFragment로 이동
            // TODO: Safe Args가 생성되면 Directions 사용
            findNavController().navigate(R.id.mainFragment)
            return
        }

        // 🔹 View 리스너 설정
        val btnGoogleLogin = view.findViewById<LinearLayout>(R.id.btn_google_login)

        btnGoogleLogin.setOnClickListener {
            signInWithGoogle()
        }
    }

    private fun signInWithGoogle() {
        progressDialog.show()
        val signInIntent = authRepository.getSignInIntent()
        googleSignInLauncher.launch(signInIntent)
    }

    /**
     * Firebase 인증을 AuthRepository에 위임하고, 반환값으로 신규 유저 여부를 판단합니다.
     */
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // AuthRepository 호출. 신규 유저이면 true, 기존 유저이면 false 반환
                val isNewUser = authRepository.firebaseAuthWithGoogle(credential)
                progressDialog.dismiss()

                if (isNewUser) {
                    // ✅ 신규 유저 (true) → 프로필 설정 화면으로 이동
                    Log.d("LOGIN", "신규 유저: ProfileSetupFragment로 이동")
                    // TODO: Safe Args가 생성되면 Directions 사용
                    findNavController().navigate(R.id.profileSetupFragment)
                } else {
                    // ✅ 기존 유저 (false) → MainFragment로 이동
                    Log.d("LOGIN", "기존 유저: MainFragment로 이동")
                    // TODO: Safe Args가 생성되면 Directions 사용
                    findNavController().navigate(R.id.mainFragment)
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                // ✅ Firebase 인증 실패 시 에러 메시지를 스낵바에 표시
                Snackbar.make(requireView(), "🚨 Firebase 인증/등록 실패: ${e.message}", Snackbar.LENGTH_LONG).show()
                Log.e("LOGIN", "Firebase 인증/등록 실패", e)
            }
        }
    }
}
