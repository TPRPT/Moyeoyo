package com.moyeoyo.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.VoteRepository
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Provides
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ※ Firestore/Auth는 FirebaseModule에서 제공됨

    // NotificationRepository
    @Provides
    @Singleton
    fun provideNotificationRepository(
        db: FirebaseFirestore,
        auth: FirebaseAuth
    ): NotificationRepository {
        return NotificationRepository(db, auth)
    }

    // FriendRepository
    @Provides
    @Singleton
    fun provideFriendRepository(
        db: FirebaseFirestore,
        auth: FirebaseAuth,
        notificationRepository: NotificationRepository
    ): FriendRepository {
        return FriendRepository(db, auth, notificationRepository)
    }

    // VoteRepository
    @Provides
    @Singleton
    fun provideVoteRepository(
        db: FirebaseFirestore
    ): VoteRepository {
        return VoteRepository(db)
    }

    // GroupRepository
    @Provides
    @Singleton
    fun provideGroupRepository(
        db: FirebaseFirestore,
        auth: FirebaseAuth,
        voteRepository: VoteRepository
    ): GroupRepository {
        return GroupRepository(db, auth, voteRepository)
    }
}
