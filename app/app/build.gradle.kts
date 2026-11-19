plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.lagoon.home"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lagoon.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 112
        versionName = "1.1.2"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("dev.mobile:dadb:1.2.10")          // talks to the box's own adb to start the key injector
    implementation("com.google.zxing:core:3.5.3")     // QR code for pairing the phone remote
}
