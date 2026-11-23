plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mythingsscraper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mythingsscraper"
        // minSdk 30 (Android 11) is required because the per-card image
        // capture uses AccessibilityService.takeScreenshot(), added in API 30.
        // If your phone is older than Android 11, see README.md for the
        // MediaProjection-based fallback (image capture only - everything
        // else in this project works on older versions too).
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
