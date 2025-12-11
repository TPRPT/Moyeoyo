package com.moyeoyo.app.di

import android.content.Context
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.moyeoyo.app.data.repository.NotificationRepository
import com.moyeoyo.app.data.repository.FriendRepository
import com.moyeoyo.app.data.repository.GroupRepository
import com.moyeoyo.app.data.repository.VoteRepository
import com.moyeoyo.app.data.repository.TimeVoteRepository
import com.moyeoyo.app.data.repository.MapRepository
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.Provides
import okhttp3.OkHttpClient
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

    // TimeVoteRepository
    @Provides
    @Singleton
    fun provideTimeVoteRepository(
        db: FirebaseFirestore
    ): TimeVoteRepository {
        return TimeVoteRepository(db)
    }

    // MapRepository
    @Provides
    @Singleton
    fun provideMapRepository(
        fusedClient: FusedLocationProviderClient,
        geocoder: android.location.Geocoder,
        @ApplicationContext context: Context,
        http: OkHttpClient,
        firestore: FirebaseFirestore,
        auth: FirebaseAuth
    ): MapRepository {
        return MapRepository(fusedClient, geocoder, context, http, firestore, auth)
    }

    // GroupRepository
    @Provides
    @Singleton
    fun provideGroupRepository(
        db: FirebaseFirestore,
        auth: FirebaseAuth,
        voteRepository: VoteRepository,
        timeVoteRepository: TimeVoteRepository,
        mapRepository: MapRepository
    ): GroupRepository {
        return GroupRepository(db, auth, voteRepository, timeVoteRepository, mapRepository)
    }
}
