package com.moyeoyo.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase 기본 초기화
        FirebaseApp.initializeApp(this)

        // 🔥 Firestore Emulator 연결 (로컬 환경)
        val firestore = FirebaseFirestore.getInstance()
        firestore.useEmulator("10.0.2.2", 8180)  // 8180 = 지금 에뮬레이터 포트

        // 🔐 Auth Emulator 연결 (선택사항)
        val auth = FirebaseAuth.getInstance()
        auth.useEmulator("10.0.2.2", 9099)  // 9099 = Auth 에뮬레이터 포트
    }
}
