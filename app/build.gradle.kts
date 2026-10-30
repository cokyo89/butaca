plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val lt4j = "2.1.0-39"
val media3 = "1.4.1"

android {
    namespace = "com.cokyo.butaca"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cokyo.butaca"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = false }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.7.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    implementation("org.libtorrent4j:libtorrent4j:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt4j")
}
