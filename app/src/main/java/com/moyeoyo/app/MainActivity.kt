package com.moyeoyo.app.ui

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.moyeoyo.app.R

class MainActivity : AppCompatActivity() {

    /**
     * [환경 설정] Firebase 에뮬레이터 연결 설정
     * - 앱이 실제 라이브 서버 대신 로컬 에뮬레이터에 연결되도록 합니다.
     * - 이 코드는 개발 단계에서만 사용되며, 출시(Release) 시에는 반드시 제거되어야 합니다.
     */
    private fun initializeEmulators() {
        try {
            // Firestore, Auth, Storage 모듈을 인스턴스화하기 전에 useEmulator를 호출
            FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
            FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
            FirebaseStorage.getInstance().useEmulator("10.0.2.2", 9199)

            Log.d("INIT", "✅ Local Firebase Emulators에 연결 설정 완료.")
        } catch (e: Exception) {
            // 에뮬레이터 서버가 켜져 있지 않을 경우 발생하는 오류는 경고만 남기고 진행
            Log.e("INIT", "❌ Emulator 연결 실패 (서버가 켜져 있는지 확인): ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 에뮬레이터 설정 및 초기화 (반드시 가장 먼저 실행)
        initializeEmulators()

        // 2. [개발 시작] 로그인 화면으로 이동하는 로직을 여기에 추가합니다.
        // 이 부분이 LoginActivity를 띄우는 코드로 대체됩니다.
        // 예: startActivity(Intent(this, LoginActivity::class.java))

        // 3. 테스트 코드가 모두 제거되었으므로, 이제 각 팀원은
        //    자신의 기능 개발에 집중할 수 있습니다.
    }
}