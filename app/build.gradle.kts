plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.dagger.hilt.android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
}

// 시스템 환경 변수에서 통합 API 키를 읽어옵니다.
// ※ 팀원 모두가 ~/.zshrc에 GOOGLE_MAPS_API_KEY 환경 변수를 설정해야 합니다.
val googleMapsApiKey = System.getenv("GOOGLE_MAPS_API_KEY") 
    ?: System.getProperty("GOOGLE_MAPS_API_KEY")
    ?: ""

// 환경변수가 비어있으면 경고 출력
if (googleMapsApiKey.isEmpty()) {
    println("⚠️  WARNING: GOOGLE_MAPS_API_KEY가 설정되지 않았습니다!")
    println("   권장 방법: ~/.zshrc에 다음을 추가하세요:")
    println("   export GOOGLE_MAPS_API_KEY=\"your-api-key-here\"")
    println("   그 후 터미널을 재시작하거나 'source ~/.zshrc'를 실행하세요.")
    println("")
    println("   Android Studio에서 빌드할 때 환경변수가 전달되지 않는 경우:")
    println("   gradle.properties에 추가 (⚠️ git에 커밋하지 마세요!):")
    println("   GOOGLE_MAPS_API_KEY=your-api-key-here")
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


        // 환경 변수에서 통합 API 키를 읽어서 BuildConfig 및 리소스로 설정
        // 모든 Google Maps 관련 API (Maps SDK, Places SDK, Distance Matrix, Roads API)에 동일한 키 사용
        buildConfigField("String", "MAPS_API_KEY", "\"$googleMapsApiKey\"")
        resValue("string", "google_maps_key", googleMapsApiKey)
        
        // maps_web_key도 동일한 키로 통합 (Distance Matrix, Roads API, Places Nearby Search 등에서 사용)
        buildConfigField("String", "DISTANCE_MATRIX_API_KEY", "\"$googleMapsApiKey\"")
        resValue("string", "maps_web_key", googleMapsApiKey)

        // AndroidManifest.xml의 manifestPlaceholders에도 동일한 키 사용
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.android.gms:play-services-maps:19.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("com.google.android.libraries.places:places:3.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-analytics:23.0.0")

    implementation("com.google.firebase:firebase-auth:24.0.1")
    implementation("com.google.firebase:firebase-firestore:26.0.2")
    implementation("com.google.firebase:firebase-storage:22.0.1")
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("com.google.firebase:firebase-dynamic-links:22.1.0")
    implementation("com.google.firebase:firebase-messaging:25.0.1")

    // Distance Matrix 호출용 (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // 이미지 로딩
    implementation("com.github.bumptech.glide:glide:4.15.1")
    kapt("com.github.bumptech.glide:compiler:4.15.1")

    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")
}
