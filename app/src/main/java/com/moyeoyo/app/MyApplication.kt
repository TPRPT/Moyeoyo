package com.moyeoyo.app

import android.app.Application
import com.google.firebase.FirebaseApp

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ✅ Firebase 기본 초기화 (클라우드 연결)
        FirebaseApp.initializeApp(this)
    }
}
