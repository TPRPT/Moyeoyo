package com.moyeoyo.app.data

import android.content.Context
import android.location.Geocoder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Locale
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object LocationModule {

    // FusedLocationProviderClient 제작 설명서
    @Provides
    @Singleton
    fun provideFusedLocationClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    // Geocoder 제작 설명서
    @Provides
    @Singleton
    fun provideGeocoder(@ApplicationContext context: Context): Geocoder {
        return Geocoder(context, Locale.KOREA)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // "OkHttpClient는 이렇게 만드는 거야!" 라고 Hilt에게 알려줌
        return OkHttpClient()
    }
}
