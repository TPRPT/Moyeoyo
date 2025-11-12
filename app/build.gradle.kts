import java.util.Properties

val apiKey = System.getenv("GOOGLE_MAPS_API_KEY") ?: "YOUR_FALLBACK_KEY_IF_ENV_FAILS"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.moyeoyo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moyeoyo.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Google Maps API Key placeholder
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = apiKey
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    // AndroidX 기본 구성
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // 테스트
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth:24.0.1")
    implementation("com.google.firebase:firebase-firestore:26.0.2")
    implementation("com.google.firebase:firebase-storage:22.0.1")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("com.google.firebase:firebase-dynamic-links:22.1.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Google Maps / Places / Location
    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.libraries.places:places:3.5.0")

    // 이미지 로딩 (Glide)
    implementation("com.github.bumptech.glide:glide:4.15.1")
    annotationProcessor("com.github.bumptech.glide:compiler:4.15.1")

    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Material UI / 기타
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.prolificinteractive:material-calendarview:1.4.3") {
        exclude(group = "com.android.support")
    }
}
