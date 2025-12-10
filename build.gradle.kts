// Top-level build file...
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.8.2" apply false
}
