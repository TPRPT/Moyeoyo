@file:Suppress("DEPRECATION")

package com.moyeoyo.app.data.repository

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.moyeoyo.app.data.model.User
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await


class AuthRepository(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val googleSignInClient: GoogleSignInClient
    private val usersCollection = db.collection("users") // users 컬렉션 참조 추가

    // AuthRepository 초기화 시 GoogleSignInClient 설정
    init {
        // R.string.default_web_client_id는 google-services.json에 의해 자동으로 생성됩니다.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(com.moyeoyo.app.R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }

    // Google 로그인 Intent 반환
    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    // 현재 로그인 상태 확인
    fun isLoggedIn(): Boolean = auth.currentUser != null

    /**
     * Firebase 인증 및 Firestore 사용자 등록 처리 (신규 유저 초기 데이터 구조 반영)
     */
    suspend fun firebaseAuthWithGoogle(credential: AuthCredential): Boolean {
        return try {
            val result = auth.signInWithCredential(credential).await()
            val firebaseUser = result.user

            if (firebaseUser != null) {
                // Firestore에서 기존 유저인지 확인 (users 컬렉션 접근)
                val userRef = usersCollection.document(firebaseUser.uid)
                val userSnapshot = userRef.get().await()

                if (!userSnapshot.exists()) {
                    // 1. 신규 유저: Firestore에 초기 데이터 생성

                    // ⭐ [수정] email 필드 추가
                    val initialUserData = hashMapOf<String, Any>(
                        "uid" to firebaseUser.uid,
                        "email" to (firebaseUser.email ?: ""), // ⭐ 이메일 필드 추가
                        "nickname" to (firebaseUser.displayName ?: "User-${firebaseUser.uid.take(4)}"),
                        "profileImageUrl" to (firebaseUser.photoUrl?.toString() ?: ""),
                        "homeLocation" to emptyMap<String, Any>(),
                        "groups" to emptyList<String>(),
                        "friends" to emptyList<String>()
                    )

                    userRef.set(initialUserData).await()
                    Log.d("AUTH", "신규 유저 Firestore 등록 완료: ${initialUserData["nickname"]}")

                    return true
                }
                Log.d("AUTH", "기존 유저 로그인 성공")
            }
            // 기존 유저이거나 인증 실패
            return false
        } catch (e: Exception) {
            Log.e("AUTH", "Firebase 인증 실패: ${e.message}", e)
            false
        }
    }

    // 로그아웃 (선택 사항)
    fun signOut() {
        auth.signOut()
        googleSignInClient.signOut()
    }
}