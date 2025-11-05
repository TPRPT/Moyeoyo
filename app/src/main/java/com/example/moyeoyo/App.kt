package com.example.moyeoyo

// App: Application 클래스
// - Google Places SDK를 앱 시작 시 1회 초기화하여 전역에서 사용 가능하도록 함

import android.app.Application
import com.google.android.libraries.places.api.Places

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Places SDK 초기화 (한 번)
        if (!Places.isInitialized()) {
            Places.initialize(this, getString(R.string.google_maps_key))
        }
    }
}