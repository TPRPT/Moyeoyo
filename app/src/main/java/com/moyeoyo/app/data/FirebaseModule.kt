package com.moyeoyo.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    // 1. FirebaseFirestore 제작 설명서
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        // "Firestore는 이렇게 구하는 거야!" 라고 Hilt에게 알려줌
        return FirebaseFirestore.getInstance()
    }

    // 2. FirebaseAuth 제작 설명서 (다음 오류를 미리 방지!)
    @Provides
    @Singleton
    fun provideAuth(): FirebaseAuth {
        // "Auth는 이렇게 구하는 거야!" 라고 Hilt에게 알려줌
        return FirebaseAuth.getInstance()
    }
}
