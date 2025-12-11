package com.moyeoyo.app

// App: Application 클래스
// - Hilt 의존성 주입을 위한 진입점
// - Places SDK 초기화는 MainActivity에서 처리

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application()

